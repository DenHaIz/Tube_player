package com.tube.player.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tube.player.PlayerApp
import com.tube.player.account.Account
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.Kiosk
import com.tube.player.youtube.Paged
import com.tube.player.youtube.PlaylistItem
import com.tube.player.youtube.SearchFilter
import com.tube.player.youtube.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Tab { Home, Subscriptions, Library }

/** Разделы главной: «Для вас» (аккаунт или история) и тренды по темам. */
enum class HomeSection(val kiosk: Kiosk?) { ForYou(null), Music(Kiosk.Music), Gaming(Kiosk.Gaming), Movies(Kiosk.Movies), Podcasts(Kiosk.Podcasts), Live(Kiosk.Live) }

/** Экраны поверх вкладок. */
sealed interface Route {
    data object Search : Route
    data class Channel(val url: String, val name: String) : Route
    data class Playlist(val url: String, val name: String) : Route
    data object Login : Route
}

/** Шапка канала и его видео. */
class ChannelModel(val url: String, val feed: Feed) {
    var header by mutableStateOf<ChannelItem?>(null)
    var banner by mutableStateOf<String?>(null)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as PlayerApp).container
    private val repo get() = container.repository

    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Route>()
    /** Страница видео развёрнута; иначе — мини-плеер над вкладками. */
    var playerExpanded by mutableStateOf(false)

    val account: StateFlow<Account?> get() = container.accounts.account

    // Главная
    var section by mutableStateOf(HomeSection.ForYou)
    private val homeFeeds = mutableMapOf<HomeSection, Feed>()

    fun homeFeed(section: HomeSection): Feed = homeFeeds.getOrPut(section) {
        val kiosk = section.kiosk
        if (kiosk != null) {
            Feed(viewModelScope, { repo.kiosk(kiosk) }, { page -> repo.kiosk(kiosk, page) })
        } else {
            Feed(viewModelScope, { Paged(forYou(), null) })
        }
    }

    /**
     * «Для вас»: с аккаунтом — рекомендации YouTube; без него — похожие на недавно просмотренные
     * и свежие видео подписок, вперемешку. Если истории нет — музыка и игры в тренде.
     */
    private suspend fun forYou(): List<FeedItem> {
        if (account.value != null) {
            runCatching { container.innerTube.home() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val recent = container.library.history.items.value.take(3).map { it.video.url }
        if (recent.isEmpty()) {
            val music = runCatching { repo.kiosk(Kiosk.Music).items }.getOrDefault(emptyList())
            val gaming = runCatching { repo.kiosk(Kiosk.Gaming).items }.getOrDefault(emptyList())
            return interleave(listOf(music, gaming)).ifEmpty { error("empty") }
        }
        val related = viewModelScopeAsync(recent) { url -> repo.video(url).related.filterIsInstance<VideoItem>() }
        val watched = container.library.history.items.value.map { it.video.id }.toSet()
        return interleave(related).filter { it !is VideoItem || it.id !in watched }
    }

    private suspend fun <T> viewModelScopeAsync(inputs: List<String>, block: suspend (String) -> List<T>): List<List<T>> {
        val gate = Semaphore(3)
        return kotlinx.coroutines.coroutineScope {
            inputs.map { input -> async { gate.withPermit { runCatching { block(input) }.getOrDefault(emptyList()) } } }.awaitAll()
        }
    }

    // Поиск
    var query by mutableStateOf("")
    var searchFilter by mutableStateOf(SearchFilter.All)
    var searchFeed by mutableStateOf<Feed?>(null)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    private var suggestJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = runCatching { repo.suggestions(text) }.getOrDefault(emptyList()).take(8)
        }
    }

    fun submitSearch(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        suggestions = emptyList()
        container.library.searched(q)
        val filter = searchFilter
        searchFeed = Feed(viewModelScope, { repo.search(q, filter) }, { page -> repo.search(q, filter, page) }).also { it.refresh() }
    }

    fun setFilter(filter: SearchFilter) {
        searchFilter = filter
        if (searchFeed != null) submitSearch()
    }

    // Подписки
    val subscriptionsFeed = Feed(viewModelScope, { Paged(subscriptionVideos(), null) })

    /** Лента подписок: с аккаунтом — от YouTube; без — RSS каждого канала (до 6 параллельно), по дате. */
    private suspend fun subscriptionVideos(): List<FeedItem> {
        if (account.value != null) {
            runCatching { container.innerTube.subscriptionsFeed() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val channels = container.library.subscriptions.items.value
        if (channels.isEmpty()) return emptyList()
        val gate = Semaphore(6)
        val all = kotlinx.coroutines.coroutineScope {
            channels.map { sub -> async { gate.withPermit { runCatching { repo.feed(sub.channel.url) }.getOrDefault(emptyList()) } } }.awaitAll()
        }.flatten()
        return all.sortedByDescending { it.uploadedAt }.distinctBy { it.url }.take(200)
    }

    // Каналы и плейлисты
    private val channels = mutableMapOf<String, ChannelModel>()

    fun channel(url: String): ChannelModel = channels.getOrPut(url) {
        var model: ChannelModel? = null
        var tab: org.schabi.newpipe.extractor.linkhandler.ListLinkHandler? = null
        val feed = Feed(
            viewModelScope,
            first = {
                val page = repo.channel(url)
                model?.header = page.channel
                model?.banner = page.banner
                tab = page.videosTab
                page.videos
            },
            more = { page -> tab?.let { repo.channelMore(it, page) } ?: Paged(emptyList(), null) },
        )
        ChannelModel(url, feed).also { model = it }
    }

    private val playlists = mutableMapOf<String, Feed>()
    var playlistHeaders = mutableStateOf<Map<String, PlaylistItem>>(emptyMap())

    fun playlist(url: String): Feed = playlists.getOrPut(url) {
        Feed(
            viewModelScope,
            first = {
                val (header, page) = repo.playlist(url)
                playlistHeaders.value = playlistHeaders.value + (url to header)
                page
            },
            more = { page -> repo.playlist(url, page).second },
        )
    }

    // Навигация
    fun open(item: FeedItem) {
        when (item) {
            is VideoItem -> openVideo(item)
            is ChannelItem -> push(Route.Channel(item.url, item.name))
            is PlaylistItem -> push(Route.Playlist(item.url, item.name))
        }
    }

    fun openVideo(item: VideoItem) {
        container.playback.open(item.url, item)
        playerExpanded = true
    }

    fun openUrl(url: String) {
        when {
            "/channel/" in url || "/@" in url || "/c/" in url || "/user/" in url -> push(Route.Channel(url, ""))
            "list=" in url && "v=" !in url -> push(Route.Playlist(url, ""))
            else -> {
                container.playback.open(url)
                playerExpanded = true
            }
        }
    }

    fun push(route: Route) {
        if (stack.lastOrNull() != route) stack.add(route)
        playerExpanded = false
    }

    fun selectTab(t: Tab) {
        if (tab == t && stack.isEmpty()) return
        tab = t
        stack.clear()
    }

    /** «Назад»: свернуть плеер, закрыть экран, вернуться на главную. false — выходить из приложения. */
    fun back(): Boolean {
        when {
            playerExpanded -> playerExpanded = false
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            tab != Tab.Home -> tab = Tab.Home
            else -> return false
        }
        return true
    }

    val canGoBack: Boolean get() = playerExpanded || stack.isNotEmpty() || tab != Tab.Home

    // Подписки и аккаунт
    val subscriptions = container.library.subscriptions.items
    val history = container.library.history.items
    val searches = container.library.searches.items

    fun isSubscribed(url: String?) = container.library.isSubscribed(url)

    fun toggleSubscription(channel: ChannelItem) {
        val lib = container.library
        if (lib.isSubscribed(channel.url)) lib.unsubscribe(channel.url) else lib.subscribe(channel)
    }

    var importing by mutableStateOf(false)
        private set
    var importedCount by mutableStateOf<Int?>(null)
        private set

    /** Перенести подписки аккаунта в локальный список (они работают и после выхода). */
    fun importSubscriptions() {
        if (importing) return
        importing = true
        viewModelScope.launch {
            val channels = runCatching { container.innerTube.subscribedChannels() }.getOrDefault(emptyList())
            container.library.subscribeAll(channels)
            importedCount = channels.size
            importing = false
            subscriptionsFeed.refresh()
        }
    }

    fun signedIn(account: Account) {
        container.accounts.save(account)
        viewModelScope.launch {
            val (name, handle, avatar) = runCatching { container.innerTube.accountInfo() }.getOrDefault(Triple(null, null, null))
            container.accounts.account.value?.let { container.accounts.save(it.copy(name = name ?: it.name, handle = handle ?: it.handle, avatar = avatar ?: it.avatar)) }
            homeFeeds.remove(HomeSection.ForYou)
            subscriptionsFeed.refresh()
        }
    }

    fun signOut() {
        container.accounts.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        homeFeeds.remove(HomeSection.ForYou)
        importedCount = null
    }

    val settings = container.settings
    val bypassStatus = container.bypass.status
    val pickProgress = container.autoPick.progress
    val networkResults = container.results.all
    val network = container.network.current
    val hideShorts: StateFlow<Boolean> = container.settings.map { it?.hideShorts == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun settingsAction(block: suspend com.tube.player.data.SettingsRepository.() -> Unit) {
        viewModelScope.launch { container.settingsRepository.block() }
    }

    // Для скриншотов и превью: готовые данные без сети.
    internal fun preloadHome(section: HomeSection, items: List<FeedItem>) {
        homeFeeds[section] = Feed.of(viewModelScope, items)
    }

    internal fun preloadSearch(text: String, items: List<FeedItem>) {
        query = text
        searchFeed = Feed.of(viewModelScope, items)
    }

    internal fun preloadChannel(url: String, header: ChannelItem, items: List<FeedItem>) {
        channels[url] = ChannelModel(url, Feed.of(viewModelScope, items)).apply { this.header = header }
    }

    companion object {
        /** По одному элементу из каждого списка по очереди — разнообразие вместо одного источника подряд. */
        fun <T : FeedItem> interleave(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
            return out.distinctBy { it.url }
        }
    }
}
