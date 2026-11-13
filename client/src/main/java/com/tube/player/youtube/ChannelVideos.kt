package com.tube.player.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import com.tube.player.account.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Порядок видео канала — как чипы вкладки «Видео» на YouTube, в том же порядке. */
enum class ChannelSort { Newest, Popular, Oldest }

/** Период публикации, обе даты включительно; null — без границы. */
data class DateRange(val from: LocalDate? = null, val to: LocalDate? = null) {
    val active: Boolean get() = from != null || to != null
}

/** Запрос `youtubei/v1/browse` без аккаунта: поля тела поверх контекста клиента WEB. В тестах — подмена. */
fun interface Browse {
    suspend fun browse(fields: Map<String, String>): JsonObject
}

/**
 * Browse через NewPipe — тот же Downloader, что и у всего остального (byedpi, DoH, cookie согласия).
 * Поле [VISITOR] кладётся в `context.client.visitorData`: токены продолжений (чипы сортировки) выданы сессии
 * посетителя, и без неё YouTube иногда отвечает пустым списком.
 */
val NewPipeBrowse = Browse { fields ->
    withContext(Dispatchers.IO) {
        val loc = NewPipe.getPreferredLocalization()
        val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(loc, NewPipe.getPreferredContentCountry())
        fields.forEach { (k, v) -> if (k != VISITOR) builder.value(k, v) }
        val body = builder.done()
        fields[VISITOR]?.let { body.getObject("context").getObject("client")["visitorData"] = it }
        YoutubeParsingHelper.getJsonPostResponse("browse", JsonWriter.string(body).toByteArray(Charsets.UTF_8), loc)
    }
}

/** Ключ в полях [Browse]: visitorData сессии (не поле тела, а часть контекста клиента). */
const val VISITOR = "\u0000visitorData"

/**
 * Видео канала с сортировкой и периодом — то, чего нет в NewPipe.
 *
 * - **Сортировка** — чипы вкладки «Видео» («Новые / Популярные / Старые»): их токены приходят в ответе,
 *   сортирует сам YouTube, по всем видео канала.
 * - **Период** — поиск по каналу с операторами `after:` (дата включительно) и `before:` (не включая):
 *   YouTube фильтрует по точной дате публикации. Поиск отдаёт «по релевантности», поэтому для «новых/старых»
 *   период режется на трети месяцев (по 10 дней) и они идут по порядку (несколько пустых — параллельно), внутри
 *   части — по примерной дате («3 недели назад»). «Популярные» за период — первые [PopularPages] страниц, по просмотрам.
 *
 * Один объект — один список: [first] начинает заново, [more] продолжает. Не потокобезопасен (Feed грузит по одному).
 */
class ChannelListing(
    private val api: Browse,
    private val channelId: String,
    private val sort: ChannelSort,
    private val range: DateRange,
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC),
) {
    private class Chunk(val items: List<FeedItem>, val next: String?)

    /** Треть месяца: 1–10, 11–20, 21–конец — мельче месяца, чтобы порядок по дате был точнее. */
    private data class Part(val month: YearMonth, val third: Int) {
        val first: LocalDate get() = month.atDay(third * 10 + 1)
        val last: LocalDate get() = if (third == 2) month.atEndOfMonth() else month.atDay(third * 10 + 10)
    }

    /** Продолжение текущего списка (или текущего месяца). */
    private var token: String? = null
    /** Сессия посетителя из первого ответа — для продолжений. */
    private var visitor: String? = null
    /** Трети месяцев периода, ещё не показанные, — в порядке показа. */
    private val parts = ArrayDeque<Part>()
    /** Первые страницы частей, загруженные заранее (параллельно), но ещё не показанные. */
    private val prefetched = HashMap<Part, Chunk>()

    suspend fun first(): Paged<FeedItem> {
        token = null
        parts.clear()
        prefetched.clear()
        return when {
            !range.active -> sorted()
            sort == ChannelSort.Popular -> {
                var found = all(search(query(range.from, range.to)))
                // Поиск по всему периоду иногда приходит пустым — тогда собираем период по частям.
                if (found.isEmpty()) {
                    planParts(newestFirst = true)
                    val collected = ArrayList<FeedItem>()
                    while (collected.size < PopularPages * 30) {
                        val page = nextParts()
                        collected += page.items
                        if (page.next == null) break
                    }
                    token = null
                    parts.clear()
                    prefetched.clear()
                    found = collected.distinctBy { it.url }
                }
                Paged(found.sortedByDescending(::views), null)
            }
            else -> {
                planParts(newestFirst = sort == ChannelSort.Newest)
                nextParts()
            }
        }
    }

    /** Трети месяцев периода в порядке показа; без начала — от первого видео канала, без конца — по сегодня. */
    private suspend fun planParts(newestFirst: Boolean) {
        val end = minOf(range.to ?: today, today)
        val start = range.from ?: channelStart()
        months(start, end, newestFirst).forEach { m ->
            val thirds = (0..2).map { Part(m, it) }.filter { it.last >= start && it.first <= end }
            parts += if (newestFirst) thirds.reversed() else thirds
        }
    }

    suspend fun more(): Paged<FeedItem> {
        if (range.active) return nextParts()
        val chunk = token?.let { continuation(it) } ?: return Paged(emptyList(), null)
        token = chunk.next
        return Paged(chunk.items, chunk.next?.let { More })
    }

    /**
     * Без периода: чип нужной сортировки; у канала с парой видео чипов нет — сортируем сами всё, что есть.
     * По чипу YouTube иногда отвечает пустым списком (видео при этом есть) — тогда заново, со свежими токенами;
     * не вышло за [ChipAttempts] — ошибка с «Повторить», а не ложное «Видео нет».
     */
    private suspend fun sorted(): Paged<FeedItem> {
        var root = JsonObject()
        for (attempt in 0 until ChipAttempts) {
            if (attempt > 0) kotlinx.coroutines.delay(500L * attempt)
            visitor = null
            root = api.browse(mapOf("browseId" to channelId, "params" to VideosTab))
            visitor = visitorOf(root)
            val chips = chips(root)
            if (chips.size < 3) break
            val chunk = continuation(chips[sort.ordinal])
            if (chunk.items.isNotEmpty()) {
                token = chunk.next
                return Paged(chunk.items, chunk.next?.let { More })
            }
            if (parse(root).items.isEmpty()) return Paged(emptyList(), null)
            if (attempt == ChipAttempts - 1) throw java.io.IOException(emptyAnswer())
        }
        val newest = all(parse(root))
        return Paged(
            when (sort) {
                ChannelSort.Newest -> newest
                ChannelSort.Popular -> newest.sortedByDescending(::views)
                ChannelSort.Oldest -> newest.reversed()
            },
            null,
        )
    }

    /** Следующие видео периода по частям месяцев: набираем хотя бы [MinBatch], пустые части пропускаем. */
    private suspend fun nextParts(): Paged<FeedItem> {
        val out = ArrayList<FeedItem>()
        var requests = 0
        // Пока ничего не нашли — идём дальше, сколько бы пустых частей ни было; иначе — не больше MaxRequests за раз.
        while (out.size < MinBatch && (out.isEmpty() || requests < MaxRequests)) {
            val t = token
            if (t != null) {
                val chunk = continuation(t)
                requests++
                out += ordered(chunk.items)
                token = chunk.next
                continue
            }
            if (parts.isEmpty()) break
            val batch = parts.take(Parallel)
            val missing = batch.filter { it !in prefetched }
            coroutineScope { missing.map { p -> async { p to search(partQuery(p)) } }.awaitAll() }.forEach { (p, c) -> prefetched[p] = c }
            requests += missing.size
            // Части — строго по порядку: у части с продолжением сначала дочитываем её, следующие ждут готовыми.
            for (p in batch) {
                val chunk = prefetched.remove(p) ?: break
                parts.removeFirst()
                out += ordered(chunk.items)
                if (chunk.next != null) {
                    token = chunk.next
                    break
                }
            }
        }
        return Paged(out, if (token != null || parts.isNotEmpty()) More else null)
    }

    private fun ordered(items: List<FeedItem>): List<FeedItem> = when (sort) {
        ChannelSort.Oldest -> items.sortedBy { (it as? VideoItem)?.uploadedAt?.takeIf { at -> at > 0 } ?: Long.MAX_VALUE }
        else -> items.sortedByDescending { (it as? VideoItem)?.uploadedAt ?: 0 }
    }

    private fun partQuery(p: Part): String {
        val from = p.first.let { d -> range.from?.let { maxOf(it, d) } ?: d }
        val to = p.last.let { d -> range.to?.let { minOf(it, d) } ?: d }
        return query(from, to)
    }

    /**
     * Самое раннее, что может быть у канала: дата первого видео (чип «Старые») минус год — дата у YouTube
     * примерная («9 лет назад»), а в поиске есть ещё Shorts и эфиры. Не узнали — с запуска YouTube.
     */
    private suspend fun channelStart(): LocalDate {
        val oldest = runCatching {
            val root = api.browse(mapOf("browseId" to channelId, "params" to VideosTab))
            val chips = chips(root)
            if (chips.size >= 3) continuation(chips[ChannelSort.Oldest.ordinal]).items else all(parse(root))
        }.getOrDefault(emptyList())
        val at = oldest.mapNotNull { (it as? VideoItem)?.uploadedAt?.takeIf { t -> t > 0 } }.minOrNull()
            ?: return YoutubeStart
        return maxOf(YoutubeStart, Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().minusYears(1).withDayOfMonth(1))
    }

    private suspend fun search(query: String): Chunk {
        val root = api.browse(mapOf("browseId" to channelId, "params" to SearchTab, "query" to query) + visitorField())
        if (visitor == null) visitor = visitorOf(root)
        return parse(root)
    }

    private suspend fun continuation(token: String) = parse(api.browse(mapOf("continuation" to token) + visitorField()))

    private fun visitorField(): Map<String, String> = visitor?.let { mapOf(VISITOR to it) }.orEmpty()

    private fun emptyAnswer() = if (NewPipe.getPreferredLocalization().languageCode == "en") {
        "YouTube returned an empty list, try again"
    } else {
        "YouTube вернул пустой список — повторите"
    }

    /** Все страницы списка (не больше [PopularPages]) — для сортировки на телефоне. */
    private suspend fun all(first: Chunk): List<FeedItem> {
        val out = ArrayList(first.items)
        var next = first.next
        var pages = 1
        while (next != null && pages < PopularPages) {
            val chunk = continuation(next)
            out += chunk.items
            next = chunk.next
            pages++
        }
        return out.distinctBy { it.url }
    }

    private fun views(item: FeedItem) = (item as? VideoItem)?.views ?: -1

    companion object {
        /** Вкладка «Видео» и поиск по каналу — те же `params`, что у сайта youtube.com. */
        const val VideosTab = "EgZ2aWRlb3PyBgQKAjoA"
        const val SearchTab = "EgZzZWFyY2jyBgQKAloA"
        private const val MinBatch = 12
        private const val MaxRequests = 12
        private const val Parallel = 6
        private const val PopularPages = 8
        val YoutubeStart: LocalDate = LocalDate.of(2005, 4, 1)
        private const val ChipAttempts = 3

        private fun visitorOf(root: JsonObject): String? =
            root.getObject("responseContext").getString("visitorData")?.takeIf { it.isNotEmpty() }

        /** Отметка «есть ещё» для Feed: продолжение хранит сам [ChannelListing]. */
        private val More = Page("channel-listing")

        /** Операторы поиска YouTube: `after:` — включительно, `before:` — не включая, поэтому +1 день. */
        fun query(from: LocalDate?, to: LocalDate?): String = listOfNotNull(
            from?.let { "after:$it" },
            to?.let { "before:${it.plusDays(1)}" },
        ).joinToString(" ")

        /** Месяцы от [start] до [end] включительно; [newestFirst] — с последнего. */
        fun months(start: LocalDate, end: LocalDate, newestFirst: Boolean): List<YearMonth> {
            if (start > end) return emptyList()
            val list = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(end) }.toList()
            return if (newestFirst) list.reversed() else list
        }

        /** Видео и продолжение из ответа browse — только из содержимого (в шапке канала свои продолжения). */
        private fun parse(root: JsonObject): Chunk {
            val body = JsonObject().apply {
                root["contents"]?.let { put("contents", it) }
                root["onResponseReceivedActions"]?.let { put("onResponseReceivedActions", it) }
            }
            var next: String? = null
            InnerTube.walk(body) { key, value ->
                if (key == "continuationItemRenderer") {
                    value.getObject("continuationEndpoint").getObject("continuationCommand").getString("token")?.let { next = it }
                    false
                } else {
                    true
                }
            }
            return Chunk(InnerTube.items(body), next)
        }

        /** Токены чипов сортировки по порядку: новый вид (chipViewModel) и старый (chipCloudChipRenderer). */
        private fun chips(root: JsonObject): List<String> {
            val tokens = ArrayList<String>()
            InnerTube.walk(root) { key, value ->
                when (key) {
                    "chipViewModel" -> {
                        value.getObject("tapCommand").getObject("innertubeCommand").getObject("continuationCommand").getString("token")?.let(tokens::add)
                        false
                    }
                    "chipCloudChipRenderer" -> {
                        value.getObject("navigationEndpoint").getObject("continuationCommand").getString("token")?.let(tokens::add)
                        false
                    }
                    else -> true
                }
            }
            return tokens
        }
    }
}
