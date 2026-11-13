package com.tube.player.account

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.tube.player.adblock.AdBlock
import com.tube.player.net.Http
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.toFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector

/**
 * Запросы InnerTube от имени аккаунта (клиент WEB, как сайт youtube.com и Metrolist/InnerTune):
 * cookie youtube.com + `Authorization: SAPISIDHASH`. Нужны только для того, что требует входа:
 * рекомендации, лента подписок, список подписок, лайки. Ответ разбирается готовыми экстракторами NewPipe,
 * реклама и промо из выдачи выбрасываются.
 */
class InnerTube(private val client: () -> OkHttpClient, private val account: () -> Account?) {

    class NotSignedIn : IllegalStateException("not signed in")

    /** Рекомендации аккаунта (главная youtube.com). */
    suspend fun home(): List<FeedItem> = items(browse("FEwhat_to_watch"))

    /** Лента подписок аккаунта. */
    suspend fun subscriptionsFeed(): List<FeedItem> = items(browse("FEsubscriptions"))

    /** Каналы, на которые подписан аккаунт, — для импорта в локальные подписки. */
    suspend fun subscribedChannels(): List<ChannelItem> {
        val root = browse("FEchannels")
        val found = ArrayList<ChannelItem>()
        walk(root) { key, value ->
            if (key == "channelRenderer") {
                runCatching { YoutubeChannelInfoItemExtractor(value) }.getOrNull()?.let { e ->
                    runCatching { ChannelItem(e.url, e.name, e.thumbnails.firstOrNull()?.url?.let { if (it.startsWith("//")) "https:$it" else it }, e.subscriberCount) }
                        .getOrNull()?.let(found::add)
                }
                false
            } else {
                true
            }
        }
        return found.distinctBy { it.url }
    }

    /** Имя и аватар для экрана аккаунта. */
    suspend fun accountInfo(): Triple<String?, String?, String?> {
        val root = post("account/account_menu", JsonObject())
        var name: String? = null
        var handle: String? = null
        var avatar: String? = null
        walk(root) { key, value ->
            if (key == "activeAccountHeaderRenderer") {
                name = YoutubeParsingHelper.getTextFromObject(value.getObject("accountName"))
                handle = YoutubeParsingHelper.getTextFromObject(value.getObject("channelHandle"))
                avatar = value.getObject("accountPhoto").getArray("thumbnails").let { a -> if (a.isEmpty()) null else a.getObject(a.size - 1).getString("url") }
                false
            } else {
                true
            }
        }
        return Triple(name, handle, avatar)
    }

    suspend fun like(videoId: String) = post("like/like", JsonObject.builder().`object`("target").value("videoId", videoId).end().done())
    suspend fun dislike(videoId: String) = post("like/dislike", JsonObject.builder().`object`("target").value("videoId", videoId).end().done())
    suspend fun removeLike(videoId: String) = post("like/removelike", JsonObject.builder().`object`("target").value("videoId", videoId).end().done())
    suspend fun subscribe(channelId: String) = post("subscription/subscribe", JsonObject.builder().array("channelIds").value(channelId).end().done())
    suspend fun unsubscribe(channelId: String) = post("subscription/unsubscribe", JsonObject.builder().array("channelIds").value(channelId).end().done())

    private suspend fun browse(browseId: String) = post("browse", JsonObject.builder().value("browseId", browseId).done())

    private suspend fun post(endpoint: String, extra: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val acc = account() ?: throw NotSignedIn()
        val sapisid = acc.sapisid ?: throw NotSignedIn()
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry()).done()
        extra.forEach { (k, v) -> body[k] = v }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(JsonWriter.string(body).toByteArray().toRequestBody(null))
            .header("Content-Type", "application/json")
            .header("User-Agent", Http.USER_AGENT)
            .header("Cookie", acc.cookies)
            .header("Authorization", SapisidHash.header(sapisid))
            .header("Origin", "https://www.youtube.com")
            .header("X-Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", YoutubeParsingHelper.getClientVersion())
            .apply { acc.visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .build()
        // Без переходов: cookie аккаунта заданы заголовком, и OkHttp понёс бы их на адрес из перенаправления.
        client().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw NotSignedIn()
            if (!response.isSuccessful) error("InnerTube $endpoint: HTTP ${response.code}")
            JsonParser.`object`().from(response.body.string())
        }
    }

    companion object {
        private val VideoRenderers = setOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer")

        /**
         * Видео из ответа browse/next: обычные и новые (lockupViewModel), без рекламы и промо.
         * Разбор дат — через сервис YouTube: он берёт шаблоны по языку, если для «ru-RU» отдельных нет
         * (прямой `TimeAgoPatternsManager` для «ru-RU» отдаёт null — даты терялись). Просмотры новых карточек
         * NewPipe понимает только с K/M/B — «13 тыс. просмотров» было бы 13; пересчитываем по тексту ([views]).
         */
        fun items(root: JsonObject): List<FeedItem> {
            val collector = StreamInfoItemsCollector(ServiceList.YouTube.serviceId)
            val parser = runCatching { ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization()) }.getOrNull()
            walk(root) { key, value ->
                when {
                    AdBlock.isAdRenderer(key) -> false
                    key in VideoRenderers -> {
                        runCatching { collector.commit(YoutubeStreamInfoItemExtractor(value, parser)) }
                        false
                    }
                    key == "lockupViewModel" && value.getString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" -> {
                        runCatching { collector.commit(LockupViews(YoutubeStreamInfoItemLockupExtractor(value, parser), lockupViews(value))) }
                        false
                    }
                    else -> true
                }
            }
            return collector.items.mapNotNull { it.toFeedItem() }.distinctBy { it.url }
        }

        /** Карточка с просмотрами, посчитанными по тексту; остальное — как у NewPipe. */
        private class LockupViews(private val base: StreamInfoItemExtractor, private val views: Long?) : StreamInfoItemExtractor by base {
            override fun getViewCount(): Long = views ?: base.viewCount
        }

        /** Текст просмотров новой карточки: «13 тыс. просмотров», «1,2 млн просмотров», «1.2M views», «Нет просмотров». */
        private fun lockupViews(lockup: JsonObject): Long? {
            var found: Long? = null
            walk(lockup.getObject("metadata")) { key, value ->
                if (found == null && key == "text") {
                    value.getString("content")?.let { text -> if (ViewWords.containsMatchIn(text)) found = parseViews(text) }
                }
                found == null
            }
            return found
        }

        private val ViewWords = Regex("""просмотр|view|зрител|смотрят|watching""", RegexOption.IGNORE_CASE)

        /**
         * Число просмотров из текста YouTube на русском или английском. С сокращением (тыс/млн/млрд, K/M/B) запятая —
         * десятичная («1,2 млн»); без сокращения разделители — тысячи («1 234 567», «1,234,567»). Не число — null.
         */
        fun parseViews(text: String): Long? {
            val t = text.lowercase().replace('\u00a0', ' ').replace('\u202f', ' ')
            if (Regex("""^\s*(нет|no)\s""").containsMatchIn(t)) return 0
            // Сокращение — отдельным словом (не начало «views»/«более»): за ним не буква.
            val m = Regex("""(\d[\d\s.,]*)\s*(?:(тыс|млн|млрд|k|m|b)(?![a-zа-яё]))?""").find(t) ?: return null
            val number = m.groupValues[1].trim()
            val mult = when (m.groupValues[2]) {
                "тыс", "k" -> 1_000.0
                "млн", "m" -> 1_000_000.0
                "млрд", "b" -> 1_000_000_000.0
                else -> return number.filter { it.isDigit() }.toLongOrNull()
            }
            val decimal = number.replace(" ", "").replace(',', '.').toDoubleOrNull() ?: return null
            return (decimal * mult).toLong()
        }

        /** Обход JSON в глубину; [visit] возвращает false — внутрь этого объекта не заходить. */
        fun walk(node: Any?, visit: (String, JsonObject) -> Boolean) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (value is JsonObject) {
                        if (visit(key, value)) walk(value, visit)
                    } else {
                        walk(value, visit)
                    }
                }
                is JsonArray -> node.forEach { walk(it, visit) }
            }
        }
    }
}
