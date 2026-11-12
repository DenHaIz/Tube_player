package com.tube.player.youtube

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Элемент выдачи: видео, канал или плейлист. Свои модели — экраны и скриншоты не зависят от NewPipe. */
sealed interface FeedItem {
    val url: String
}

data class VideoItem(
    override val url: String,
    val title: String,
    val channel: String? = null,
    val channelUrl: String? = null,
    val thumbnail: String? = null,
    val channelAvatar: String? = null,
    /** Длительность в секундах; 0 — неизвестна или прямой эфир. */
    val duration: Long = 0,
    val views: Long = -1,
    /** «3 дня назад» — как отдаёт YouTube. */
    val uploaded: String? = null,
    /** Время публикации (мс), если известно, — для сортировки ленты подписок. */
    val uploadedAt: Long = 0,
    val live: Boolean = false,
    val short: Boolean = false,
) : FeedItem {
    val id: String get() = videoId(url) ?: url
}

data class ChannelItem(
    override val url: String,
    val name: String,
    val avatar: String? = null,
    val subscribers: Long = -1,
    val description: String? = null,
) : FeedItem

data class PlaylistItem(
    override val url: String,
    val name: String,
    val thumbnail: String? = null,
    val count: Long = -1,
    val uploader: String? = null,
) : FeedItem

/** Страница выдачи и ключ следующей. */
data class Paged<T>(val items: List<T>, val next: Page?)

/** id видео из ссылки youtube.com/watch?v=, youtu.be/, /shorts/, /live/. */
fun videoId(url: String): String? {
    val patterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"""),
    )
    return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
}

fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

/** Картинка подходящего размера: ближайшая не меньше [width], иначе самая большая. */
fun List<Image>.best(width: Int = 480): String? {
    if (isEmpty()) return null
    val sized = filter { it.width > 0 }
    val pick = sized.filter { it.width >= width }.minByOrNull { it.width }
        ?: sized.maxByOrNull { it.width }
        ?: last()
    return pick.url.let { if (it.startsWith("//")) "https:$it" else it }
}

internal fun InfoItem.toFeedItem(): FeedItem? = when (this) {
    is StreamInfoItem -> VideoItem(
        url = url,
        title = name,
        channel = uploaderName,
        channelUrl = uploaderUrl,
        thumbnail = thumbnails.best(),
        channelAvatar = uploaderAvatars.best(88),
        duration = duration.coerceAtLeast(0),
        views = viewCount,
        uploaded = textualUploadDate,
        uploadedAt = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0,
        live = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
        short = isShortFormContent || "/shorts/" in url,
    )
    is ChannelInfoItem -> ChannelItem(url, name, thumbnails.best(176), subscriberCount, description)
    is PlaylistInfoItem -> PlaylistItem(url, name, thumbnails.best(), streamCount, uploaderName)
    else -> null
}
