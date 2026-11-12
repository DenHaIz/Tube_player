package com.tube.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.player.data.PlayerSettings
import com.tube.player.net.BypassStatus
import com.tube.ui.components.GlassSheet
import com.tube.ui.components.StatusDot
import com.tube.ui.components.bouncyClickable
import com.tube.ui.glass.LocalHazeState
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import com.tube.strategy.NetworkKind
import dev.chrisbanes.haze.rememberHazeState

enum class Sheet { None, Settings, Bypass, Quality, Account, Update }

/** То, что умеет только активность: PiP, полный экран, «поделиться», разрешения. */
class ShellActions(
    val enterPip: () -> Unit,
    val setFullscreen: (Boolean) -> Unit,
    val share: (String) -> Unit,
    /** Плавающее окно; false — нужно разрешение «Поверх других приложений» (открыт экран настроек). */
    val popup: () -> Unit,
    /** Слушать в фоне: свернуть приложение, звук продолжится. */
    val background: () -> Unit,
    /** Открыть скачанный APK в системном установщике (сначала — разрешение «Установка приложений»). */
    val installUpdate: (java.io.File) -> Unit = {},
    /** Системный выбор аккаунта Google телефона; в колбэк — адрес (null — отказались или аккаунтов нет). */
    val chooseAccount: ((String?) -> Unit) -> Unit = { it(null) },
)

private val TopBarHeight = 56.dp
private val BottomBarHeight = 56.dp
private val MiniHeight = 62.dp

@Composable
fun PlayerShell(vm: MainViewModel, actions: ShellActions, pip: Boolean, fullscreen: Boolean, initialSheet: Sheet = Sheet.None) {
    val container = vm.container
    val playback = container.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: PlayerSettings()

    if (pip) {
        PlayerView(playback.player, state, settings, fullscreen = true, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize(), pipMode = true)
        return
    }

    val hazeState = rememberHazeState()
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    val account by vm.account.collectAsStateWithLifecycle()
    val hideShorts = settings.hideShorts
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val searches by vm.searches.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val disliked by vm.disliked.collectAsStateWithLifecycle()
    val bypass by vm.bypassStatus.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val route = vm.stack.lastOrNull()
    val login = route == Route.Login
    val searching = route == Route.Search
    val hasMini = state.item != null && !vm.playerExpanded

    BackHandler(enabled = vm.canGoBack && sheet == Sheet.None) {
        if (fullscreen) actions.setFullscreen(false) else vm.back()
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(TubeTheme.colors.background))

            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val dir = LocalLayoutDirection.current
            val padding = PaddingValues(
                start = insets.calculateStartPadding(dir),
                end = insets.calculateEndPadding(dir),
                top = insets.calculateTopPadding() + if (login) 0.dp else TopBarHeight,
                bottom = insets.calculateBottomPadding() + BottomBarHeight + (if (hasMini) MiniHeight else 0.dp) + 8.dp,
            )
            val key: Any = route ?: vm.tab
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    key,
                    transitionSpec = {
                        // Вкладки — мягкая смена; экраны поверх (канал, поиск) — короткий сдвиг в сторону открытия.
                        if (targetState is Tab && initialState is Tab) {
                            fadeIn(Anim.fade()) togetherWith fadeOut(Anim.out())
                        } else {
                            val forward = targetState is Route
                            (fadeIn(Anim.fade()) + slideInHorizontally(Anim.move()) { if (forward) it / 10 else -it / 10 }) togetherWith
                                (fadeOut(Anim.out()) + slideOutHorizontally(Anim.move()) { if (forward) -it / 14 else it / 14 })
                        }
                    },
                    label = "screen",
                ) { target ->
                    when (target) {
                        Tab.Home -> {
                            val feed = vm.homeFeed(vm.section)
                            LaunchedEffect(vm.section) { feed.ensure() }
                            HomeScreen(vm.section, feed.state, { vm.section = it }, vm::open, feed::loadMore, feed::refresh, padding, hideShorts) {
                                UpdateBanner(update, onOpen = { sheet = Sheet.Update })
                            }
                        }
                        Tab.Subscriptions -> {
                            LaunchedEffect(subscriptions.size, account) { vm.subscriptionsFeed.refresh() }
                            SubscriptionsScreen(
                                subscriptions, vm.subscriptionsFeed.state, account != null,
                                onChannel = { vm.open(it) }, onOpen = vm::open, onRetry = vm.subscriptionsFeed::refresh,
                                onSignIn = { sheet = Sheet.Account }, padding = padding, hideShorts = hideShorts,
                            )
                        }
                        Tab.Library -> LibraryScreen(history, vm::open, { container.library.removeHistory(it) }, { container.library.clearHistory() }, padding, liked)
                        Route.Search -> SearchScreen(
                            vm.query, vm.searchFilter, vm.searchFeed?.state, vm.suggestions, searches,
                            onSearch = { vm.submitSearch(it) }, onRemoveHistory = { container.library.removeSearch(it) },
                            onFilter = vm::setFilter, onOpen = vm::open,
                            onMore = { vm.searchFeed?.loadMore() }, onRetry = { vm.searchFeed?.refresh() },
                            padding = padding, hideShorts = hideShorts,
                        )
                        is Route.Channel -> {
                            val model = vm.channel(target.url)
                            LaunchedEffect(target.url) { model.feed.ensure() }
                            ChannelScreen(
                                target.name, model.header, model.banner, model.feed.state,
                                subscribed = subscriptions.any { com.tube.player.data.Library.sameChannel(it.channel.url, target.url) },
                                onSubscribe = vm::toggleSubscription, onOpen = vm::open, onMore = model.feed::loadMore, onRetry = model.feed::refresh,
                                padding = padding, hideShorts = hideShorts,
                            )
                        }
                        is Route.Playlist -> {
                            val feed = vm.playlist(target.url)
                            LaunchedEffect(target.url) { feed.ensure() }
                            PlaylistScreen(target.name, vm.playlistHeaders.value[target.url], feed.state, vm::open, feed::loadMore, feed::refresh, padding)
                        }
                        Route.Login -> LoginScreen(container.proxy.port, onDone = { acc ->
                            vm.signedIn(acc)
                            vm.back()
                        }, onClose = { vm.back() }, email = vm.loginEmail)
                    }
                }
            }

            if (!login) {
                TopBar(
                    searching = searching,
                    query = vm.query,
                    onQuery = vm::onQueryChange,
                    onSubmit = { vm.submitSearch() },
                    onOpenSearch = { vm.push(Route.Search) },
                    onBack = { vm.back() },
                    bypass = bypass,
                    onBypass = { sheet = Sheet.Bypass },
                    onSettings = { sheet = Sheet.Settings },
                    canGoBack = route != null,
                    account = account,
                )
                BottomArea(vm, state, hasMini, onClose = { playback.close() })
            }

            // Страница видео поверх всего — «выезжает» снизу, как в YouTube.
            AnimatedVisibility(
                vm.playerExpanded && state.item != null,
                enter = slideInVertically(Anim.move()) { it / 2 } + fadeIn(Anim.fade()),
                exit = slideOutVertically(Anim.move()) { it / 2 } + fadeOut(Anim.out()),
            ) {
                val subscribed = subscriptions.any { s -> state.item?.channelUrl?.let { com.tube.player.data.Library.sameChannel(s.channel.url, it) } == true }
                VideoScreen(
                    playback.player, state, settings, subscribed, fullscreen,
                    VideoActions(
                        onMinimize = { vm.playerExpanded = false },
                        onFullscreen = actions.setFullscreen,
                        onQuality = { sheet = Sheet.Quality },
                        onPopup = actions.popup,
                        onPip = actions.enterPip,
                        onBackground = actions.background,
                        onShare = { state.item?.let { actions.share(it.url) } },
                        onSkip = { state.skipButton?.let(playback::skip) },
                        onUndoSkip = playback::undoSkip,
                        onRetry = playback::retry,
                        onOpen = vm::open,
                        onChannel = { vm.open(it) },
                        onSubscribe = vm::toggleSubscription,
                        onSeekTo = { playback.player.seekTo(it) },
                        onLike = { (state.details?.item ?: state.item)?.let(vm::like) },
                        onDislike = { (state.details?.item ?: state.item)?.let(vm::dislike) },
                    ),
                    hideShorts = hideShorts,
                    liked = state.item?.let { item -> liked.any { it.id == item.id } } == true,
                    disliked = state.item?.id?.let { it in disliked } == true,
                )
            }

            val close = { sheet = Sheet.None }
            GlassSheet(sheet == Sheet.Settings, close, solid = true) {
                SettingsSheet(
                    settings, account, vm::settingsAction,
                    onAccount = { sheet = Sheet.Account }, onBypass = { sheet = Sheet.Bypass },
                    onUpdates = {
                        if (update.release == null) vm.checkUpdate()
                        sheet = Sheet.Update
                    },
                )
            }
            GlassSheet(sheet == Sheet.Bypass, close, solid = true) {
                val progress by vm.pickProgress.collectAsStateWithLifecycle()
                val all by vm.networkResults.collectAsStateWithLifecycle()
                val network by vm.network.collectAsStateWithLifecycle()
                val lang = LocalLanguage.current
                val label = network?.let { id ->
                    when (id.kind) {
                        NetworkKind.Wifi -> "Wi-Fi"
                        NetworkKind.Cellular -> id.operator ?: lang.pick("Мобильная сеть", "Mobile network")
                        NetworkKind.Other -> lang.pick("Сеть", "Network")
                    }
                }
                BypassSheet(
                    bypass, progress, all[network?.key], label, settings.watchdog,
                    onPick = { container.bypass.pick() }, onCancel = { container.bypass.cancelPick() },
                    onChoose = { id -> vm.settingsAction { container.bypass.choose(id) } },
                    onWatchdog = { v -> vm.settingsAction { setWatchdog(v) } },
                    onRestart = { container.bypass.restart() },
                )
            }
            GlassSheet(sheet == Sheet.Quality, close, solid = true) {
                QualitySheet(state, onQuality = { playback.setQuality(it) }, onSpeed = { sp ->
                    playback.setSpeed(sp)
                    if (settings.rememberSpeed) vm.settingsAction { setSpeed(sp) }
                })
            }
            GlassSheet(sheet == Sheet.Update, close, solid = true) {
                UpdateSheet(
                    update,
                    onCheck = { vm.checkUpdate() },
                    onDownload = { vm.downloadUpdate(it) },
                    onInstall = { (update as? com.tube.update.UpdateState.Ready)?.let { actions.installUpdate(it.apk) } },
                    onLater = {
                        vm.dismissUpdate()
                        sheet = Sheet.None
                    },
                )
            }
            GlassSheet(sheet == Sheet.Account, close, solid = true) {
                AccountSheet(
                    account, vm.importing, vm.importedCount,
                    onSignIn = {
                        sheet = Sheet.None
                        // Сначала — аккаунт, уже добавленный на телефоне: вход тогда без ввода адреса и пароля.
                        actions.chooseAccount { email ->
                            vm.loginEmail = email
                            vm.push(Route.Login)
                        }
                    },
                    onImport = vm::importSubscriptions,
                    onSignOut = vm::signOut,
                )
            }
        }
    }
}

/** Логотип: скруглённая кнопка «play» с плавным нежно-фиолетовым переходом и бежевой окантовкой (как значок приложения). */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        // Координаты значка (108×108): треугольник занимает x 37…81, y 29…79 с учётом окантовки.
        val k = this.size.width / 50f
        fun x(v: Float) = (v - 34f) * k
        fun y(v: Float) = (v - 29f) * k
        val path = Path().apply {
            moveTo(x(40f), y(43f))
            lineTo(x(40f), y(65f))
            quadraticTo(x(40f), y(76f), x(49.52f), y(70.49f))
            lineTo(x(68.48f), y(59.51f))
            quadraticTo(x(78f), y(54f), x(68.48f), y(48.49f))
            lineTo(x(49.52f), y(37.51f))
            quadraticTo(x(40f), y(32f), x(40f), y(43f))
            close()
        }
        drawPath(path, Color(0xFFE6D3B3), style = Stroke(width = 7f * k, join = StrokeJoin.Round))
        drawPath(
            path,
            Brush.linearGradient(
                0f to Color(0xFFE9DEFF), 0.5f to Color(0xFFBCA4F7), 1f to Color(0xFF8F72E3),
                start = androidx.compose.ui.geometry.Offset(x(40f), y(32f)),
                end = androidx.compose.ui.geometry.Offset(x(76f), y(74f)),
            ),
        )
    }
}

/** Верхняя панель как в YouTube: логотип с названием слева, значки 24dp справа; в поиске — поле на всю ширину. */
@Composable
private fun TopBar(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
    bypass: BypassStatus,
    onBypass: () -> Unit,
    onSettings: () -> Unit,
    canGoBack: Boolean,
    account: com.tube.player.account.Account?,
) {
    val colors = TubeTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background.copy(alpha = 0.97f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                BarIcon(TubeIcons.Back, tr("Назад", "Back"), onBack)
            } else {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Tube Player", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp), color = colors.textPrimary, maxLines = 1)
                }
            }
            if (searching) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(colors.chip, Shapes.Pill)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(tr("Поиск на YouTube", "Search YouTube"), style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary)
                                inner()
                            }
                        },
                    )
                    AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(Anim.fade()), exit = fadeOut(Anim.out())) {
                        Box(Modifier.size(32.dp).bouncyClickable { onQuery("") }, contentAlignment = Alignment.Center) {
                            Icon(TubeIcons.Close, tr("Очистить", "Clear"), tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.weight(1f))
                // Обход: значок щита с точкой — зелёная/фиолетовая работает, жёлтая проверяет, красная — ошибка.
                Box(Modifier.size(48.dp).bouncyClickable(pressedScale = 0.88f, onClick = onBypass), contentAlignment = Alignment.Center) {
                    Icon(TubeIcons.Shield, tr("Обход", "Unblocking"), tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    val dot by animateColorAsState(
                        when (bypass) {
                            is BypassStatus.Running -> when {
                                bypass.appOk == false -> colors.danger
                                bypass.check?.ok == false || bypass.appOk == null -> colors.warning
                                else -> colors.accent
                            }
                            BypassStatus.Starting -> colors.warning
                            is BypassStatus.Failed -> colors.danger
                            BypassStatus.Off -> colors.textTertiary
                        },
                        Anim.fade(),
                        label = "bypass",
                    )
                    StatusDot(dot, Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 11.dp))
                }
                BarIcon(TubeIcons.Search, tr("Поиск", "Search"), onOpenSearch)
                Box(Modifier.size(48.dp).bouncyClickable(pressedScale = 0.88f, onClick = onSettings), contentAlignment = Alignment.Center) {
                    if (account?.avatar != null) Avatar(account.avatar, 28.dp)
                    else Icon(TubeIcons.Gear, tr("Настройки", "Settings"), tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Значок 24dp в области касания 48dp — как кнопки панелей YouTube. */
@Composable
fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, tint: Color = TubeTheme.colors.textPrimary) {
    Box(
        Modifier.size(48.dp).bouncyClickable(pressedScale = 0.88f, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Низ: мини-плеер во всю ширину над панелью вкладок (как в YouTube). */
@Composable
private fun BottomArea(vm: MainViewModel, state: com.tube.player.player.PlaybackState, hasMini: Boolean, onClose: () -> Unit) {
    val colors = TubeTheme.colors
    val playback = vm.container.playback
    val progress = rememberProgress(if (hasMini) playback.player else null)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().background(colors.background.copy(alpha = 0.97f))) {
            AnimatedVisibility(
                hasMini,
                enter = slideInVertically(Anim.move()) { it } + fadeIn(Anim.fade()),
                exit = slideOutVertically(Anim.move()) { it } + fadeOut(Anim.out()),
            ) {
                Column {
                    MiniPlayer(
                        state, progress.playing,
                        onExpand = { vm.playerExpanded = true },
                        onPlayPause = { if (playback.player.isPlaying) playback.player.pause() else playback.player.play() },
                        onClose = onClose,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    )
                    // Полоса просмотренного — 2dp под мини-плеером.
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.chip)) {
                        val f = if (progress.duration > 0) progress.position.toFloat() / progress.duration else 0f
                        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(2.dp).background(colors.accent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.chip))
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .height(BottomBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    val selected = vm.tab == tab
                    val tint by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, Anim.fade(), label = "tab")
                    val scale by animateFloatAsState(if (selected) 1f else 0.94f, Anim.move(), label = "tabScale")
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .bouncyClickable(pressedScale = 0.9f) { vm.selectTab(tab) }
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val pill by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent, Anim.fade(), label = "tabPill")
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 28.dp)
                                .background(pill, Shapes.Pill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (tab) {
                                    Tab.Home -> TubeIcons.Home
                                    Tab.Subscriptions -> TubeIcons.Subscriptions
                                    Tab.Library -> TubeIcons.Account
                                },
                                null,
                                tint = if (selected) colors.accent else tint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            when (tab) {
                                Tab.Home -> tr("Главная", "Home")
                                Tab.Subscriptions -> tr("Подписки", "Subscriptions")
                                Tab.Library -> tr("Вы", "You")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
