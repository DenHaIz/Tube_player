package com.tube.player.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.StrategyScore
import com.tube.player.PlayerApp
import com.tube.player.account.Account
import com.tube.player.net.BypassStatus
import com.tube.player.player.PlaybackState
import com.tube.player.player.SourceKind
import com.tube.player.sponsorblock.Segment
import com.tube.player.sponsorblock.SponsorCategory
import com.tube.player.ui.HomeSection
import com.tube.player.ui.MainViewModel
import com.tube.player.ui.PlayerShell
import com.tube.player.ui.Route
import com.tube.player.ui.Sheet
import com.tube.player.ui.ShellActions
import com.tube.player.ui.Tab
import com.tube.player.youtube.ChannelItem
import com.tube.player.youtube.Chapter
import com.tube.player.youtube.FeedItem
import com.tube.player.youtube.PlaylistItem
import com.tube.player.youtube.VideoDetails
import com.tube.player.youtube.VideoItem
import com.tube.strategy.NetworkResults
import com.tube.strategy.WatchdogStatus
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.theme.TubePalette
import com.tube.ui.theme.TubeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Снимки экранов Tube Player на JVM (эмулятора нет). Сети нет: данные и картинки — подставные.
 * Запуск: gradlew :client:testDebugUnitTest -Pscreenshots → client/build/outputs/roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PlayerApp>()
    private val noActions = ShellActions({}, {}, {}, {}, {})

    @Before
    fun fakeImages() {
        // Вместо превью из сети — градиент своего цвета для каждой ссылки.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(app).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val seed = chain.request.data.toString().hashCode()
                        return SuccessResult(gradient(seed).asImage(), chain.request, DataSource.MEMORY)
                    }
                })
            }.build(),
        )
    }

    private fun gradient(seed: Int): Bitmap {
        val palette = listOf(0xFF2E7D32, 0xFF6A1B9A, 0xFF1565C0, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF283593).map { it.toInt() }
        val a = palette[Math.floorMod(seed, palette.size)]
        val b = palette[Math.floorMod(seed / 7, palette.size)]
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 320f, 180f, a, b, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 320f, 180f, paint)
        return bmp
    }

    private val titles = listOf(
        "Как устроен интернет: от кабеля на дне океана до вашего телефона",
        "Lo-fi для учёбы и работы — 3 часа без рекламы",
        "Обзор нового смартфона: стоит ли обновляться",
        "Готовим пасту за 15 минут",
        "Самые красивые места Карелии зимой",
        "Разбор матча: лучшие моменты",
        "Kotlin за час: корутины простыми словами",
        "Путешествие на поезде через всю страну",
    )
    private val channels = listOf("Наука просто", "Chill Beats", "Техно Обзор", "Кухня за 15", "Север", "Спорт Сегодня", "Код и кофе", "В дороге")

    private fun videos(n: Int = 8): List<VideoItem> = (0 until n).map { i ->
        VideoItem(
            url = "https://www.youtube.com/watch?v=video${i}xxxx".take(43),
            title = titles[i % titles.size],
            channel = channels[i % channels.size],
            channelUrl = "https://www.youtube.com/channel/UC$i",
            thumbnail = "https://i.ytimg.com/vi/$i/hq720.jpg",
            channelAvatar = "https://yt3.ggpht.com/a$i",
            duration = listOf(754L, 10_800L, 612L, 905L, 1_320L, 488L, 3_605L, 2_210L)[i % 8],
            views = listOf(1_250_000L, 384_000L, 92_500L, 12_400L, 2_300_000L, 51_000L, 7_800L, 640_000L)[i % 8],
            uploaded = listOf("2 дня назад", "1 неделю назад", "5 часов назад", "3 месяца назад")[i % 4],
            live = i == 5,
        )
    }

    private fun render(
        dark: Boolean = false,
        amoled: Boolean = false,
        lang: AppLanguage = AppLanguage.Russian,
        sheet: Sheet = Sheet.None,
        setup: (MainViewModel) -> Unit = {},
    ) {
        val vm = MainViewModel(app as Application)
        vm.preloadHome(HomeSection.ForYou, videos())
        setup(vm)
        compose.setContent {
            CompositionLocalProvider(LocalLanguage provides lang) {
                TubeTheme(darkTheme = dark, amoled = amoled, palette = TubePalette.Violet) { PlayerShell(vm, noActions, pip = false, fullscreen = false, initialSheet = sheet) }
            }
        }
        // Системные панели как на настоящем устройстве: телефон — строка состояния 40dp и жестовая панель 16dp,
        // планшет — 24dp и 20dp (в dp, а не в пикселях: иначе на xhdpi-планшете сверху выходила лишняя пустота).
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = compose.activity.resources.displayMetrics
            val tablet = compose.activity.resources.configuration.let { minOf(it.screenWidthDp, it.screenHeightDp) } >= 600
            fun dp(v: Int) = (v * metrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, dp(if (tablet) 24 else 40), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, dp(if (tablet) 20 else 16)))
                .build()
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun openVideo(vm: MainViewModel) {
        val v = videos().first()
        val related: List<FeedItem> = videos().drop(1)
        compose.runOnUiThread {
            app.container.playback.preview(
                PlaybackState(
                    item = v,
                    details = VideoDetails(
                        item = v,
                        description = "Рассказываем, как данные проходят путь от сервера до экрана: кабели, маршрутизаторы, DNS и TLS.\n00:00 Вступление\n03:10 Кабели\n07:45 DNS",
                        descriptionHtml = false,
                        likes = 48_200,
                        subscribers = 1_840_000,
                        related = related,
                        chapters = listOf(Chapter("Вступление", 0), Chapter("Кабели", 190), Chapter("DNS", 465)),
                    ),
                    qualities = listOf(1080, 720, 480, 360, 240, 144),
                    currentHeight = 1080,
                    segments = listOf(Segment(SponsorCategory.Sponsor, 120_000, 180_000, "s"), Segment(SponsorCategory.Intro, 0, 15_000, "i")),
                    dislikes = 1_350,
                    kind = SourceKind.Dash,
                ),
            )
        }
        vm.playerExpanded = true
    }

    @Test
    fun homeLight() {
        render()
        shot("home_light")
    }

    @Test
    fun homeDarkAmoled() {
        render(dark = true, amoled = true)
        shot("home_amoled")
    }

    @Test
    fun homeEnglishDark() {
        render(dark = true, lang = AppLanguage.English)
        shot("home_dark_en")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-xxhdpi")
    fun homeLandscapePhone() {
        render()
        shot("home_landscape_phone")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun homeTabletLandscape() {
        render(dark = true)
        shot("home_tablet_land")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun homeTabletMini() {
        render { vm ->
            compose.runOnUiThread { app.container.playback.preview(PlaybackState(item = videos()[1])) }
            vm.playerExpanded = false
        }
        shot("home_tablet_mini")
    }

    // Планшет: стоя (800×1280) и боком (1280×800).
    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortraitHome() {
        render { vm ->
            compose.runOnUiThread { app.container.playback.preview(PlaybackState(item = videos()[1])) }
            vm.playerExpanded = false
        }
        shot("tablet_port_home")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortraitVideo() {
        render(dark = true) { vm -> openVideo(vm) }
        shot("tablet_port_video")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortraitSettings() {
        render(sheet = Sheet.Settings)
        shot("tablet_port_settings")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortraitComments() = comments(amoled = false, name = "tablet_port_comments")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandMenu() {
        render()
        compose.onNodeWithContentDescription("menu").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        shot("tablet_land_menu")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandSettings() {
        render(sheet = Sheet.Settings)
        shot("tablet_land_settings")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandQuality() {
        render(dark = true, sheet = Sheet.Quality) { vm -> openVideo(vm) }
        shot("tablet_land_quality")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandComments() = comments(amoled = true, name = "tablet_land_comments")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandSearch() {
        render { vm ->
            vm.preloadSearch("lofi", videos().drop(1) + listOf<FeedItem>(ChannelItem("https://www.youtube.com/channel/UCx", "Chill Beats", "https://yt3/x", 2_400_000)))
            vm.push(Route.Search)
        }
        shot("tablet_land_search")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandLibrary() {
        render(dark = true) { vm ->
            videos(5).forEachIndexed { i, v ->
                app.container.library.watched(v)
                if (i % 2 == 0) app.container.library.savePosition(v.id, 200_000L + i * 50_000, 900_000)
            }
            vm.selectTab(Tab.Library)
        }
        shot("tablet_land_library")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tabletLandChannel() {
        render { vm ->
            val url = "https://www.youtube.com/channel/UC0"
            vm.preloadChannel(url, ChannelItem(url, "Наука просто", "https://yt3/a0", 1_840_000, "Объясняем сложное простыми словами."), videos())
            vm.push(Route.Channel(url, "Наука просто"))
        }
        shot("tablet_land_channel")
    }

    @Test
    fun miniPlayer() {
        render { vm ->
            compose.runOnUiThread { app.container.playback.preview(PlaybackState(item = videos()[1])) }
            vm.playerExpanded = false
        }
        shot("home_mini_player")
    }

    @Test
    fun search() {
        render { vm ->
            vm.preloadSearch("lofi", videos().drop(1) + listOf<FeedItem>(ChannelItem("https://www.youtube.com/channel/UCx", "Chill Beats", "https://yt3/x", 2_400_000)) +
                PlaylistItem("https://www.youtube.com/playlist?list=PL1", "Lo-fi на весь день", "https://i.ytimg.com/p", 120, "Chill Beats"))
            vm.push(Route.Search)
        }
        shot("search_results")
    }

    @Test
    fun searchEmpty() {
        render { vm ->
            app.container.library.searched("котики")
            app.container.library.searched("kotlin корутины")
            vm.push(Route.Search)
        }
        shot("search_history")
    }

    @Test
    fun video() {
        render { vm -> openVideo(vm) }
        shot("video_page")
    }

    @Test
    fun videoAmoled() {
        render(dark = true, amoled = true) { vm -> openVideo(vm) }
        shot("video_amoled")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun videoTablet() {
        render(dark = true) { vm -> openVideo(vm) }
        shot("video_tablet_land")
    }

    @Test
    fun commentsPanel() = comments(amoled = false, name = "comments_panel")

    @Test
    fun commentsPanelAmoled() = comments(amoled = true, name = "comments_amoled")

    private fun comments(amoled: Boolean, name: String) {
        val comments = listOf(
            com.tube.player.youtube.Comment("1", "@nauka_prosto", avatar = "https://yt3/a1", text = "Спасибо всем, кто смотрит! Следующий выпуск — про спутниковый интернет.", likes = 1240, date = "2 дня назад", pinned = true, byOwner = true, hearted = true),
            com.tube.player.youtube.Comment("2", "@ivan", avatar = "https://yt3/a2", text = "Наконец-то понятно объяснили, как работает DNS. Лайк!", likes = 312, date = "1 день назад", replies = 4),
            com.tube.player.youtube.Comment("3", "@maria_k", avatar = "https://yt3/a3", text = "А можно отдельное видео про TLS 1.3 и почему его так сложно заблокировать?", likes = 87, date = "20 часов назад", replies = 1),
            com.tube.player.youtube.Comment("4", "@techfan", avatar = "https://yt3/a4", text = "Кабели на дне океана — это вообще магия.", likes = 15, date = "5 часов назад"),
        )
        val v = videos().first()
        val model = CommentsModelHolder.preview(app, comments)
        compose.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.Russian) {
                TubeTheme(darkTheme = amoled, amoled = amoled, palette = TubePalette.Violet) {
                    com.tube.player.ui.VideoScreen(
                        player = null,
                        state = PlaybackState(item = v, details = VideoDetails(v, "Описание", false, 48_200, 1_840_000, emptyList(), emptyList())),
                        settings = com.tube.player.data.PlayerSettings(),
                        subscribed = false,
                        fullscreen = false,
                        actions = com.tube.player.ui.VideoActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                        hideShorts = false,
                        comments = model,
                        startWithComments = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        shot(name)
    }

    @Test
    fun channel() {
        render { vm ->
            val url = "https://www.youtube.com/channel/UC0"
            vm.preloadChannel(url, ChannelItem(url, "Наука просто", "https://yt3/a0", 1_840_000, "Объясняем сложное простыми словами. Новые видео каждую неделю."), videos())
            vm.push(Route.Channel(url, "Наука просто"))
        }
        shot("channel")
    }

    /** Канал: «Популярные» за период — чип периода с датами и крестиком. */
    @Test
    fun channelFiltered() {
        render { vm ->
            val url = "https://www.youtube.com/channel/UC0"
            vm.preloadChannel(url, ChannelItem(url, "Наука просто", "https://yt3/a0", 1_840_000, "Объясняем сложное простыми словами."), videos())
            vm.channel(url).sort = com.tube.player.youtube.ChannelSort.Popular
            vm.channel(url).range = com.tube.player.youtube.DateRange(java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2024, 12, 31))
            vm.push(Route.Channel(url, "Наука просто"))
        }
        shot("channel_filtered")
    }

    @Test
    fun channelPeriodSheet() {
        render(sheet = Sheet.Period) { vm ->
            val url = "https://www.youtube.com/channel/UC0"
            vm.preloadChannel(url, ChannelItem(url, "Наука просто", "https://yt3/a0", 1_840_000), videos())
            vm.channel(url).range = com.tube.player.youtube.DateRange(java.time.LocalDate.of(2023, 3, 1), null)
            vm.push(Route.Channel(url, "Наука просто"))
        }
        shot("channel_period_sheet")
    }

    @Test
    fun library() {
        render { vm ->
            videos(5).forEachIndexed { i, v ->
                app.container.library.watched(v)
                if (i % 2 == 0) app.container.library.savePosition(v.id, 200_000L + i * 50_000, 900_000)
            }
            vm.selectTab(Tab.Library)
        }
        shot("library")
    }

    @Test
    fun subscriptionsEmpty() {
        render { vm -> vm.selectTab(Tab.Subscriptions) }
        shot("subscriptions_empty")
    }

    @Test
    fun settingsSheet() {
        render(sheet = Sheet.Settings)
        shot("sheet_settings")
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi", fontScale = 1.3f)
    fun settingsSheetLargeFont() {
        render(dark = true, sheet = Sheet.Settings)
        shot("sheet_settings_large_font")
    }

    @Test
    fun updateSheet() {
        render(sheet = Sheet.Update) { _ ->
            app.container.updates.previewAvailable(
                com.tube.update.Release(
                    version = "0.2",
                    notes = listOf(
                        "**RU**", "### Новое", "- Самообновление с GitHub и зеркал", "- Шторки в окне обновления",
                        "### Исправлено", "- Загрузка через встроенный обход", "- Проверка сторожа",
                        "**EN**", "### New", "- Self-update", "### Fixed", "- Loading",
                    ).joinToString("\n"),
                    apkUrl = "https://github.com/x.apk", apkSize = 1, pageUrl = "https://github.com/x",
                ),
            )
        }
        shot("sheet_update")
    }

    @Test
    fun settingsSheetExpanded() {
        render(dark = true, amoled = true, sheet = Sheet.Settings)
        compose.onNodeWithText("Воспроизведение").performClick()
        compose.onNodeWithText("Вид").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        shot("sheet_settings_amoled")
    }

    @Test
    fun bypassSheet() {
        render(sheet = Sheet.Bypass) { _ ->
            val probe = Probe("www.youtube.com", "/", 1)
            app.container.network.refresh()
            val key = app.container.network.current.value?.key ?: "test"
            app.container.results.update(key) {
                NetworkResults(
                    testedAt = System.currentTimeMillis(),
                    scores = Strategies.builtIn.take(5).mapIndexed { i, s -> s.id to StrategyScore(s, listOf(ProbeResult(probe, true, 240L + i * 60, 70_000, null))) }.toMap(),
                )
            }
            app.container.bypass.preview(BypassStatus.Running(Strategies.builtIn[1], 41_234, WatchdogStatus(System.currentTimeMillis(), true)))
        }
        shot("sheet_bypass")
    }

    @Test
    fun qualitySheet() {
        render(sheet = Sheet.Quality) { vm -> openVideo(vm) }
        shot("sheet_quality")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-xxhdpi")
    fun qualitySheetLandscape() {
        render(dark = true, sheet = Sheet.Quality) { vm -> openVideo(vm) }
        shot("sheet_quality_landscape")
    }

    @Test
    fun accountSheet() {
        render(sheet = Sheet.Account)
        shot("sheet_account")
    }

    @Test
    fun accountSignedIn() {
        render(sheet = Sheet.Account) { _ ->
            app.container.accounts.save(Account("SAPISID=x", name = "Иван Петров", handle = "@ivan"))
        }
        shot("sheet_account_signed_in")
        app.container.accounts.clear()
    }

    @Test
    @Config(qualifiers = "w320dp-h320dp-xxhdpi")
    fun logo() {
        compose.setContent {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1F1B26)),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.tube.player.R.drawable.ic_launcher_foreground), null,
                    androidx.compose.ui.Modifier.size(160.dp).clip(androidx.compose.foundation.shape.CircleShape),
                )
                com.tube.player.ui.Logo(64.dp)
            }
        }
        compose.waitForIdle()
        shot("logo")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouchInputSwipeUp() {
        performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.2f) }
        compose.waitForIdle()
    }
}

/** Комментарии для скриншота: модель без сети. */
private object CommentsModelHolder {
    fun preview(app: PlayerApp, items: List<com.tube.player.youtube.Comment>) =
        com.tube.player.ui.CommentsModel.preview(kotlinx.coroutines.MainScope(), app.container.repository, items, 1_250)
}
