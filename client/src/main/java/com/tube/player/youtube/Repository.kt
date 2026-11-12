package com.tube.player.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/** Разделы главной без аккаунта: тренды YouTube по темам. */
enum class Kiosk(val id: String) {
    Music("trending_music"),
    Gaming("trending_gaming"),
    Movies("trending_movies_and_shows"),
    Podcasts("trending_podcasts_episodes"),
    Live("live"),
}

enum class SearchFilter(val newPipe: String) {
    All(YoutubeSearchQueryHandlerFactory.ALL),
    Videos(YoutubeSearchQueryHandlerFactory.VIDEOS),
    Channels(YoutubeSearchQueryHandlerFactory.CHANNELS),
    Playlists(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
}

/** Глава видео (из описания). */
data class Chapter(val title: String, val startSeconds: Int)

/** Всё о видео для страницы просмотра; [info] — исходник для плеера (null в превью и скриншотах). */
data class VideoDetails(
    val item: VideoItem,
    val description: String,
    /** HTML-описание от YouTube (ссылки, таймкоды); пусто — текст без разметки. */
    val descriptionHtml: Boolean,
    val likes: Long,
    val subscribers: Long,
    val related: List<FeedItem>,
    val chapters: List<Chapter>,
    val ageLimit: Int = 0,
    val info: StreamInfo? = null,
)

/** Канал: шапка и первая страница видео. */
data class ChannelPage(
    val channel: ChannelItem,
    val banner: String?,
    val videos: Paged<FeedItem>,
    val videosTab: ListLinkHandler?,
)

/**
 * Данные YouTube через NewPipeExtractor. Вся сеть — через [Downloader] приложения (byedpi).
 * Методы блокирующие внутри, поэтому выполняются на Dispatchers.IO.
 */
class Repository(downloader: Downloader, language: String = "ru", country: String = "RU") {
    private val yt get() = ServiceList.YouTube

    init {
        NewPipe.init(downloader, Localization(language, country), ContentCountry(country))
    }

    fun setLocale(language: String, country: String) {
        NewPipe.setupLocalization(Localization(language, country), ContentCountry(country))
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.All, page: Page? = null): Paged<FeedItem> = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter.newPipe), "")
        if (page == null) {
            val info = SearchInfo.getInfo(yt, handler)
            Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = SearchInfo.getMoreItems(yt, handler, page)
            Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList() else yt.suggestionExtractor.suggestionList(query)
    }

    suspend fun kiosk(kiosk: Kiosk, page: Page? = null): Paged<FeedItem> = io {
        val list = yt.kioskList
        val url = list.getListLinkHandlerFactoryByType(kiosk.id).fromId(kiosk.id).url
        if (page == null) {
            val info = KioskInfo.getInfo(yt, url)
            Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = KioskInfo.getMoreItems(yt, url, page)
            Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    suspend fun channel(url: String): ChannelPage = io {
        val info = ChannelInfo.getInfo(yt, url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: info.tabs.firstOrNull()
        val videos = tab?.let { runCatching { ChannelTabInfo.getInfo(yt, it) }.getOrNull() }
        ChannelPage(
            channel = ChannelItem(info.url, info.name, info.avatars.best(176), info.subscriberCount, info.description),
            banner = info.banners.best(1080),
            videos = Paged(videos?.relatedItems?.mapNotNull { it.toFeedItem() }.orEmpty(), videos?.nextPage),
            videosTab = tab,
        )
    }

    suspend fun channelMore(tab: ListLinkHandler, page: Page): Paged<FeedItem> = io {
        val more = ChannelTabInfo.getMoreItems(yt, tab, page)
        Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
    }

    suspend fun playlist(url: String, page: Page? = null): Pair<PlaylistItem, Paged<FeedItem>> = io {
        val info = PlaylistInfo.getInfo(yt, url)
        val header = PlaylistItem(info.url, info.name, info.thumbnails.best(), info.streamCount, info.uploaderName)
        if (page == null) {
            header to Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = PlaylistInfo.getMoreItems(yt, url, page)
            header to Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    /** Свежие видео канала по RSS — лёгкий запрос для ленты подписок без аккаунта. */
    suspend fun feed(channelUrl: String): List<VideoItem> = io {
        FeedInfo.getInfo(yt, channelUrl).relatedItems.mapNotNull { it.toFeedItem() as? VideoItem }
    }

    /**
     * Видео: повторное открытие (из мини-плеера, истории, «назад») в течение [CACHE_MS] — без сети.
     * Ссылки на потоки живут ~6 часов, поэтому кэш короткий; 403 всё равно переполучает данные ([fresh]).
     */
    suspend fun video(url: String, fresh: Boolean = false): VideoDetails {
        val key = com.tube.player.youtube.videoId(url) ?: url
        if (!fresh) cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }
        val details = io { details(StreamInfo.getInfo(yt, url)) }
        synchronized(cache) {
            cache[key] = System.currentTimeMillis() to details
            while (cache.size > 30) cache.remove(cache.keys.first())
        }
        return details
    }

    private val cache = java.util.Collections.synchronizedMap(LinkedHashMap<String, Pair<Long, VideoDetails>>())

    companion object {
        fun details(info: StreamInfo): VideoDetails {
            val item = VideoItem(
                url = info.url,
                title = info.name,
                channel = info.uploaderName,
                channelUrl = info.uploaderUrl,
                thumbnail = info.thumbnails.best(1280),
                channelAvatar = info.uploaderAvatars.best(88),
                duration = info.duration.coerceAtLeast(0),
                views = info.viewCount,
                uploaded = info.textualUploadDate,
                uploadedAt = runCatching { info.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0,
                live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
                short = info.isShortFormContent,
            )
            val description = info.description ?: Description.EMPTY_DESCRIPTION
            return VideoDetails(
                item = item,
                description = description.content,
                descriptionHtml = description.type == Description.Type.HTML,
                likes = info.likeCount,
                subscribers = info.uploaderSubscriberCount,
                related = info.relatedItems.mapNotNull { it.toFeedItem() },
                chapters = info.streamSegments.map { Chapter(it.title, it.startTimeSeconds) },
                ageLimit = info.ageLimit,
                info = info,
            )
        }

        private const val CACHE_MS = 30 * 60_000L

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}
