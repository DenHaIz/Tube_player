package com.tube.player.data

import android.content.Context
import android.util.AtomicFile
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Запись истории: видео, когда смотрели и где остановились. */
data class HistoryEntry(val video: VideoItem, val watchedAt: Long, val positionMs: Long = 0, val durationMs: Long = 0) {
    /** Досмотрено почти до конца — «продолжить» не предлагаем. */
    val finished: Boolean get() = durationMs > 0 && positionMs > durationMs - 15_000
}

/** Подписка на канал (локальная, без аккаунта). */
data class Subscription(val channel: ChannelItem, val since: Long)

/**
 * Список в JSON-файле: память обновляется сразу, файл пишется в фоне не чаще раза в [delayMs] и атомарно
 * (как ResultsStore в Tube) — без базы данных и KSP.
 */
class JsonListStore<T>(
    dir: File,
    name: String,
    private val encode: (T) -> JSONObject,
    private val decode: (JSONObject) -> T?,
    private val delayMs: Long = 800,
) {
    private val file = AtomicFile(File(dir, name))
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    private val scheduled = AtomicBoolean(false)

    @Synchronized
    fun update(transform: (List<T>) -> List<T>) {
        _items.value = transform(_items.value)
        if (scheduled.compareAndSet(false, true)) {
            Writer.schedule({
                scheduled.set(false)
                write(_items.value)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun flush() = write(_items.value)

    private fun write(list: List<T>) = synchronized(file) {
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(JSONArray().apply { list.forEach { put(encode(it)) } }.toString().toByteArray())
            file.finishWrite(out)
        } catch (_: Exception) {
            out?.let { file.failWrite(it) }
        }
    }

    private fun load(): List<T> = runCatching {
        val array = JSONArray(String(file.readFully()))
        (0 until array.length()).mapNotNull { decode(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    companion object {
        private val Writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "library-writer").apply { isDaemon = true } }
    }
}

/** История, подписки и история поиска — всё локально, в filesDir. */
class Library(context: Context) {
    private val dir = context.filesDir

    val history = JsonListStore(dir, "history.json", ::encodeHistory, ::decodeHistory)
    val subscriptions = JsonListStore(dir, "subscriptions.json", ::encodeSubscription, ::decodeSubscription)
    val searches = JsonListStore(dir, "searches.json", { JSONObject().put("q", it) }, { it.optString("q").ifEmpty { null } })

    /** Отметить просмотр: видео поднимается наверх истории, позиция сохраняется. */
    fun watched(video: VideoItem, positionMs: Long = 0, durationMs: Long = 0) = history.update { list ->
        val previous = list.firstOrNull { it.video.id == video.id }
        val entry = HistoryEntry(
            video = video,
            watchedAt = System.currentTimeMillis(),
            positionMs = if (positionMs > 0) positionMs else previous?.positionMs ?: 0,
            durationMs = if (durationMs > 0) durationMs else previous?.durationMs ?: 0,
        )
        (listOf(entry) + list.filter { it.video.id != video.id }).take(MAX_HISTORY)
    }

    /** Позиция просмотра — без переноса наверх (вызывается часто, во время воспроизведения). */
    fun savePosition(videoId: String, positionMs: Long, durationMs: Long) = history.update { list ->
        list.map { if (it.video.id == videoId) it.copy(positionMs = positionMs, durationMs = durationMs) else it }
    }

    fun position(videoId: String): Long =
        history.items.value.firstOrNull { it.video.id == videoId }?.takeIf { !it.finished }?.positionMs ?: 0

    fun removeHistory(videoId: String) = history.update { list -> list.filter { it.video.id != videoId } }

    fun clearHistory() = history.update { emptyList() }

    fun isSubscribed(channelUrl: String?): Boolean =
        channelUrl != null && subscriptions.items.value.any { sameChannel(it.channel.url, channelUrl) }

    fun subscribe(channel: ChannelItem) = subscriptions.update { list ->
        if (list.any { sameChannel(it.channel.url, channel.url) }) list else list + Subscription(channel, System.currentTimeMillis())
    }

    fun unsubscribe(channelUrl: String) = subscriptions.update { list -> list.filter { !sameChannel(it.channel.url, channelUrl) } }

    /** Добавить подписки разом (импорт из аккаунта), без дублей. */
    fun subscribeAll(channels: List<ChannelItem>) = subscriptions.update { list ->
        val now = System.currentTimeMillis()
        list + channels.filter { c -> list.none { sameChannel(it.channel.url, c.url) } }.distinctBy { channelKey(it.url) }.map { Subscription(it, now) }
    }

    fun searched(query: String) = searches.update { list ->
        val q = query.trim()
        if (q.isEmpty()) list else (listOf(q) + list.filter { !it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES)
    }

    fun removeSearch(query: String) = searches.update { list -> list.filter { it != query } }

    companion object {
        const val MAX_HISTORY = 1000
        const val MAX_SEARCHES = 50

        /** Каналы сравниваются по id (UC…), ссылка может быть в разных видах. */
        fun channelKey(url: String): String = url.substringAfterLast("/channel/", url).substringBefore('?').trimEnd('/')

        fun sameChannel(a: String, b: String) = channelKey(a) == channelKey(b)

        internal fun encodeVideo(v: VideoItem) = JSONObject().apply {
            put("url", v.url); put("title", v.title)
            v.channel?.let { put("channel", it) }; v.channelUrl?.let { put("channelUrl", it) }
            v.thumbnail?.let { put("thumb", it) }; v.channelAvatar?.let { put("avatar", it) }
            put("duration", v.duration); put("views", v.views)
            v.uploaded?.let { put("uploaded", it) }; put("uploadedAt", v.uploadedAt)
            put("live", v.live); put("short", v.short)
        }

        internal fun decodeVideo(o: JSONObject): VideoItem? {
            val url = o.optString("url").ifEmpty { return null }
            return VideoItem(
                url = url,
                title = o.optString("title"),
                channel = o.optString("channel").ifEmpty { null },
                channelUrl = o.optString("channelUrl").ifEmpty { null },
                thumbnail = o.optString("thumb").ifEmpty { null },
                channelAvatar = o.optString("avatar").ifEmpty { null },
                duration = o.optLong("duration"),
                views = o.optLong("views", -1),
                uploaded = o.optString("uploaded").ifEmpty { null },
                uploadedAt = o.optLong("uploadedAt"),
                live = o.optBoolean("live"),
                short = o.optBoolean("short"),
            )
        }

        private fun encodeHistory(h: HistoryEntry) = JSONObject().apply {
            put("video", encodeVideo(h.video)); put("at", h.watchedAt); put("pos", h.positionMs); put("dur", h.durationMs)
        }

        private fun decodeHistory(o: JSONObject): HistoryEntry? {
            val video = o.optJSONObject("video")?.let(::decodeVideo) ?: return null
            return HistoryEntry(video, o.optLong("at"), o.optLong("pos"), o.optLong("dur"))
        }

        internal fun encodeSubscription(s: Subscription) = JSONObject().apply {
            put("url", s.channel.url); put("name", s.channel.name)
            s.channel.avatar?.let { put("avatar", it) }
            put("subs", s.channel.subscribers); put("since", s.since)
        }

        internal fun decodeSubscription(o: JSONObject): Subscription? {
            val url = o.optString("url").ifEmpty { return null }
            return Subscription(ChannelItem(url, o.optString("name"), o.optString("avatar").ifEmpty { null }, o.optLong("subs", -1)), o.optLong("since"))
        }
    }
}
