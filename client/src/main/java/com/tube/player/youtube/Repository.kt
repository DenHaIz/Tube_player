package com.tube.player.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    /** id канала (UC…) — для запросов InnerTube (сортировка, период). */
    val id: String,
    val channel: ChannelItem,
    val banner: String?,
    val videos: Paged<FeedItem>,
    val videosTab: ListLinkHandler?,
)

/**
 * Данные YouTube через NewPipeExtractor. Вся сеть — через [Downloader] приложения (byedpi).
 * Методы блокирующие внутри, поэтому выполняются на Dispatchers.IO.
 *
 * **Язык — в каждом запросе явно.** NewPipe 0.27 знает для YouTube единственный язык (`en-GB`) и любой другой
 * молча подменяет им: названия видео (переводы авторов), даты и «Лбс → кг» приходили по-английски, даже при
 * русском в настройках. Поэтому каждый экстрактор создаётся здесь с `forceLocalization`/`forceContentCountry`,
 * а «ещё» берётся тем же способом (статические `getMoreItems` NewPipe снова взяли бы `en-GB`).
 */
class Repository(downloader: Downloader, language: String = "ru", country: String = "RU") {
    private val yt get() = ServiceList.YouTube

    @Volatile private var localization = localizationFor(language, country)
    @Volatile private var contentCountry = ContentCountry(country)

    init {
        NewPipe.init(downloader, localization, contentCountry)
    }

    /** Язык интерфейса YouTube (`hl`) и страна выдачи (`gl`). */
    fun setLocale(language: String, country: String) {
        localization = localizationFor(language, country)
        contentCountry = ContentCountry(country)
        NewPipe.setupLocalization(localization, contentCountry)
        synchronized(cache) { cache.clear() }
    }

    /** Экстрактор с языком приложения. */
    private fun <E : org.schabi.newpipe.extractor.Extractor> E.localized(): E = apply {
        forceLocalization(localization)
        forceContentCountry(contentCountry)
    }

    private fun <E : org.schabi.newpipe.extractor.Extractor> E.fetched(): E = localized().apply { fetchPage() }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.All, page: Page? = null): Paged<FeedItem> = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter.newPipe), "")
        if (page == null) {
            val info = SearchInfo.getInfo(yt.getSearchExtractor(handler).fetched())
            Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = yt.getSearchExtractor(handler).localized().getPage(page)
            Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) {
            emptyList()
        } else {
            yt.suggestionExtractor.apply {
                forceLocalization(localization)
                forceContentCountry(contentCountry)
            }.suggestionList(query)
        }
    }

    suspend fun kiosk(kiosk: Kiosk, page: Page? = null): Paged<FeedItem> = io {
        val extractor = yt.kioskList.getExtractorById(kiosk.id, page, localization).localized()
        if (page == null) {
            extractor.fetchPage()
            val info = KioskInfo.getInfo(extractor)
            Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = extractor.getPage(page)
            Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    suspend fun channel(url: String): ChannelPage = io {
        val info = ChannelInfo.getInfo(yt.getChannelExtractor(url).fetched())
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: info.tabs.firstOrNull()
        val videos = tab?.let { runCatching { ChannelTabInfo.getInfo(yt.getChannelTabExtractor(it).fetched()) }.getOrNull() }
        ChannelPage(
            id = info.id,
            channel = ChannelItem(info.url, info.name, info.avatars.best(176)?.let { sharpAvatar(it, 264) }, info.subscriberCount, info.description),
            banner = info.banners.best(1080),
            videos = Paged(videos?.relatedItems?.mapNotNull { it.toFeedItem() }.orEmpty(), videos?.nextPage),
            videosTab = tab,
        )
    }

    suspend fun channelMore(tab: ListLinkHandler, page: Page): Paged<FeedItem> = io {
        val more = yt.getChannelTabExtractor(tab).localized().getPage(page)
        Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
    }

    suspend fun playlist(url: String, page: Page? = null): Pair<PlaylistItem, Paged<FeedItem>> = io {
        val info = PlaylistInfo.getInfo(yt.getPlaylistExtractor(url).fetched())
        val header = PlaylistItem(info.url, info.name, info.thumbnails.best(), info.streamCount, info.uploaderName)
        if (page == null) {
            header to Paged(info.relatedItems.mapNotNull { it.toFeedItem() }, info.nextPage)
        } else {
            val more = yt.getPlaylistExtractor(url).localized().getPage(page)
            header to Paged(more.items.mapNotNull { it.toFeedItem() }, more.nextPage)
        }
    }

    /** Комментарии видео: первая страница (с числом комментариев) или следующая/ответы по [page]. */
    suspend fun comments(url: String, page: Page? = null): CommentsPage = io {
        if (page == null) {
            val info = org.schabi.newpipe.extractor.comments.CommentsInfo.getInfo(yt.getCommentsExtractor(url).fetched())
            CommentsPage(info.relatedItems.map(::comment), info.nextPage, info.commentsCount, info.isCommentsDisabled)
        } else {
            val more = yt.getCommentsExtractor(url).localized().getPage(page)
            CommentsPage(more.items.map(::comment), more.nextPage, -1, false)
        }
    }

    private fun comment(c: org.schabi.newpipe.extractor.comments.CommentsInfoItem): Comment {
        val text = c.commentText ?: Description.EMPTY_DESCRIPTION
        return Comment(
            id = c.commentId ?: (c.uploaderName + c.textualUploadDate + text.content.hashCode()),
            author = c.uploaderName.orEmpty(),
            authorUrl = c.uploaderUrl,
            avatar = c.uploaderAvatars.best(96)?.let(::sharpAvatar),
            text = text.content,
            html = text.type == Description.Type.HTML,
            likes = c.likeCount.coerceAtLeast(0),
            likesText = c.textualLikeCount?.takeIf { it.isNotBlank() },
            date = c.textualUploadDate,
            dateAt = runCatching { c.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0,
            replies = c.replyCount.coerceAtLeast(0),
            repliesPage = c.replies,
            pinned = c.isPinned,
            hearted = c.isHeartedByUploader,
            byOwner = c.isChannelOwner,
        )
    }

    /**
     * Свежие видео канала для ленты подписок без аккаунта: RSS (точные даты) и, параллельно, вкладка «Видео»
     * на языке приложения — RSS отдаёт названия только в оригинале, переводы авторов берутся со вкладки.
     * Вкладка не загрузилась — остаются названия из RSS.
     */
    suspend fun feed(channelUrl: String): List<VideoItem> = kotlinx.coroutines.coroutineScope {
        val translated = async { runCatching { localizedTitles(channelUrl) }.getOrDefault(emptyMap()) }
        val rss = io { FeedInfo.getInfo(yt, channelUrl).relatedItems.mapNotNull { it.toFeedItem() as? VideoItem } }
        val titles = translated.await()
        rss.map { v -> titles[v.id]?.let { v.copy(title = it) } ?: v }
    }

    /**
     * id видео → название на языке приложения с вкладок «Видео» и Shorts (первые страницы, параллельно) —
     * в RSS есть и то и другое. Только для ссылок /channel/UC…; вкладка не загрузилась — без неё.
     */
    private suspend fun localizedTitles(channelUrl: String): Map<String, String> = kotlinx.coroutines.coroutineScope {
        val id = ChannelIdPattern.find(channelUrl)?.groupValues?.get(1) ?: return@coroutineScope emptyMap()
        val videos = async {
            runCatching {
                val root = NewPipeBrowse.browse(mapOf("browseId" to id, "params" to ChannelListing.VideosTab))
                com.tube.player.account.InnerTube.items(root).filterIsInstance<VideoItem>().associate { it.id to it.title }
            }.getOrDefault(emptyMap())
        }
        val shorts = async {
            runCatching {
                val root = NewPipeBrowse.browse(mapOf("browseId" to id, "params" to ShortsTab))
                val found = HashMap<String, String>()
                com.tube.player.account.InnerTube.walk(root) { key, value ->
                    if (key == "shortsLockupViewModel") {
                        val vid = value.getObject("onTap").getObject("innertubeCommand").getObject("reelWatchEndpoint").getString("videoId")
                        val title = value.getObject("overlayMetadata").getObject("primaryText").getString("content")
                        if (!vid.isNullOrEmpty() && !title.isNullOrEmpty()) found[vid] = title
                        false
                    } else {
                        true
                    }
                }
                found
            }.getOrDefault(emptyMap())
        }
        shorts.await() + videos.await()
    }

    /**
     * Видео: повторное открытие (из мини-плеера, истории, «назад») в течение [CACHE_MS] — без сети.
     * Ссылки на потоки живут ~6 часов, поэтому кэш короткий; 403 всё равно переполучает данные ([fresh]).
     */
    suspend fun video(url: String, fresh: Boolean = false): VideoDetails {
        val key = com.tube.player.youtube.videoId(url) ?: url
        if (!fresh) cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }
        val details = io {
            val extractor = try {
                yt.getStreamExtractor(url).fetched()
            } catch (e: org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException) {
                // «Подтвердите, что вы не бот» — ещё раз с настройками NewPipe по умолчанию (en-GB), как до 0.4.9.
                yt.getStreamExtractor(url).apply {
                    forceLocalization(Localization.DEFAULT)
                    forceContentCountry(contentCountry)
                    fetchPage()
                }
            }
            val d = details(StreamInfo.getInfo(extractor))
            // NewPipe нарочно берёт исходное название (из ответа плеера); перевод автора — в ответе `next`.
            localizedTitle(extractor)?.takeIf { it != d.item.title }?.let { d.copy(item = d.item.copy(title = it)) } ?: d
        }
        synchronized(cache) {
            cache[key] = System.currentTimeMillis() to details
            while (cache.size > 30) cache.remove(cache.keys.first())
        }
        return details
    }

    private val cache = java.util.Collections.synchronizedMap(LinkedHashMap<String, Pair<Long, VideoDetails>>())

    /**
     * Название на языке запроса из `videoPrimaryInfoRenderer` ответа `next` (поле `nextResponse` экстрактора
     * NewPipe закрыто — берём рефлексией; в R8 оно сохранено правилом в proguard-rules.pro). Не вышло — null.
     */
    private fun localizedTitle(extractor: org.schabi.newpipe.extractor.stream.StreamExtractor): String? = runCatching {
        val field = extractor.javaClass.getDeclaredField("nextResponse").apply { isAccessible = true }
        val next = field.get(extractor) as? com.grack.nanojson.JsonObject ?: return null
        var title: String? = null
        com.tube.player.account.InnerTube.walk(next) { key, value ->
            if (title == null && key == "videoPrimaryInfoRenderer") {
                title = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject(value.getObject("title"))
                false
            } else {
                title == null
            }
        }
        title?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        fun details(info: StreamInfo): VideoDetails {
            val item = VideoItem(
                url = info.url,
                title = info.name,
                channel = info.uploaderName,
                channelUrl = info.uploaderUrl,
                thumbnail = info.thumbnails.best(1280),
                channelAvatar = info.uploaderAvatars.best(88)?.let { sharpAvatar(it) },
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
        /**
         * Язык всегда со страной: из неё NewPipe собирает User-Agent плеера visionOS («…like Mac OS X; RU)»).
         * Без страны («ru») User-Agent выходил битым («…; )») — YouTube отвечал «подтвердите, что вы не бот»
         * и не отдавал ни одного видео (0.4.9). Английский — en-GB: «en-RU» YouTube отклоняет (400).
         */
        fun localizationFor(language: String, country: String): Localization =
            if (language == "en") Localization("en", "GB") else Localization(language, country)

        private val ChannelIdPattern = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")
        /** Вкладка Shorts канала — `params` как у сайта youtube.com. */
        private const val ShortsTab = "EgZzaG9ydHPyBgUKA5oBAA=="

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}
