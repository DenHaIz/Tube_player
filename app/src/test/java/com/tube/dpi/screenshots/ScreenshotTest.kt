package com.tube.dpi.screenshots

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.StrategyScore
import com.tube.dpi.TubeApp
import com.tube.strategy.NetworkResults
import com.tube.dpi.ui.MainViewModel
import com.tube.dpi.ui.ShellActions
import com.tube.dpi.ui.TubeAppShell
import com.tube.dpi.vpn.FailReason
import com.tube.dpi.vpn.Traffic
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.TunnelStatus
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.theme.TubeTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Снимки экранов на JVM (без эмулятора). Запуск: gradlew testDebugUnitTest -Pscreenshots
 * Картинки — в app/build/outputs/roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<TubeApp>()
    private val noActions = ShellActions({}, {}, {}, {}, {})

    @After
    fun reset() {
        TunnelState.set(TunnelStatus.Stopped)
        app.container.results.clear()
    }

    private fun render(dark: Boolean, lang: AppLanguage = AppLanguage.Russian) {
        val vm = MainViewModel(app as Application)
        compose.setContent {
            CompositionLocalProvider(LocalLanguage provides lang) {
                TubeTheme(darkTheme = dark) { TubeAppShell(vm, noActions) }
            }
        }
        // Статус-бар 40dp и жестовая панель 16dp, как на современном телефоне.
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 120, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .build()
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun fakeResults() {
        val probes = listOf(Probe("www.youtube.com", "/", 1), Probe("i.ytimg.com", "/", 1), Probe("redirector.googlevideo.com", "/", 0))
        fun score(s: com.tube.byedpi.Strategy, passed: Int, ms: Long) = StrategyScore(s, probes.mapIndexed { j, p -> ProbeResult(p, j < passed, ms, 0, if (j < passed) null else "SocketTimeoutException") })
        val all = Strategies.builtIn + Strategies.extended.take(6)
        val scores = all.mapIndexed { i, s ->
            val passed = when (i % 5) {
                0, 3 -> 3
                1 -> 1
                else -> 0
            }
            s.id to score(s, passed, 280L + i * 23)
        }.toMap()
        app.container.network.refresh()
        val key = app.container.network.current.value?.key ?: "test"
        app.container.results.update(key) {
            NetworkResults(
                testedAt = System.currentTimeMillis() - 12 * 60_000,
                systemDns = false,
                baseline = StrategyScore(com.tube.byedpi.Strategy("direct", "DIRECT", "", "", ""), probes.map { ProbeResult(it, it.host.startsWith("i."), 100, 0, "reset") }),
                scores = scores,
            )
        }
    }

    @Test
    fun homeStoppedLight() {
        render(dark = false)
        shot("home_stopped_light")
    }

    @Test
    fun homeRunningDark() {
        TunnelState.set(TunnelStatus.Running(System.currentTimeMillis(), "DISORDER SNI", byAutostart = true))
        TunnelState.setTraffic(Traffic(rxBytes = 734_003_200, txBytes = 12_582_912, rxRate = 2_411_724))
        TunnelState.setWatchdog(com.tube.dpi.vpn.WatchdogStatus(System.currentTimeMillis() - 3 * 60_000, ok = true))
        fakeResults()
        render(dark = true)
        shot("home_running_dark")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun homeLandscapePhone() {
        TunnelState.set(TunnelStatus.Running(System.currentTimeMillis(), "DISORDER SNI", byAutostart = false))
        TunnelState.setWatchdog(com.tube.dpi.vpn.WatchdogStatus(System.currentTimeMillis(), ok = true))
        fakeResults()
        render(dark = true)
        shot("home_landscape_phone")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun homeTabletLandscape() {
        fakeResults()
        render(dark = false)
        shot("home_tablet_land")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun strategySheetTabletPortrait() {
        fakeResults()
        render(dark = true)
        compose.onNodeWithText("Стратегия").performClick()
        compose.waitForIdle()
        shot("sheet_strategy_tablet")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun settingsSheetLandscape() {
        render(dark = false)
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.waitForIdle()
        shot("sheet_settings_landscape")
    }

    @Test
    fun homeFailedEnglish() {
        TunnelState.set(TunnelStatus.Failed(FailReason.ProxyFailed, "ciadpi: invalid value: -s"))
        render(dark = true, lang = AppLanguage.English)
        shot("home_failed_en")
    }

    @Test
    fun strategySheet() {
        fakeResults()
        render(dark = true)
        compose.onNodeWithText("Стратегия").performClick()
        compose.waitForIdle()
        shot("sheet_strategy_dark")
    }

    @Test
    fun settingsSheet() {
        render(dark = false)
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.waitForIdle()
        shot("sheet_settings_light")
    }

    @Test
    fun autostartSheet() {
        render(dark = true)
        compose.onNodeWithText("Запуск с YouTube").performClick()
        compose.waitForIdle()
        shot("sheet_autostart_dark")
    }

    @Test
    fun appsSheet() {
        render(dark = false)
        compose.onNodeWithText("Приложения").performClick()
        compose.waitForIdle()
        shot("sheet_apps_light")
    }
}
