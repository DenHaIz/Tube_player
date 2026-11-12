package com.tube.player

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tube.player.data.PlayerSettings
import com.tube.player.player.PlaybackState
import com.tube.player.ui.MainViewModel
import com.tube.player.ui.PlayerShell
import com.tube.player.ui.Sheet
import com.tube.player.ui.ShellActions
import com.tube.player.ui.VideoActions
import com.tube.player.ui.VideoScreen
import com.tube.player.youtube.VideoItem
import com.tube.ui.theme.TubePalette
import com.tube.ui.theme.TubeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Переходы страницы видео во всех положениях: вход и выход из полноэкранного режима не пересоздают видео
 * (тот же TextureView — без мелькания обложки), сворачивание/разворачивание и все листы открываются и закрываются.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class TransitionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PlayerApp>()
    private val video = VideoItem("https://www.youtube.com/watch?v=aaaaaaaaaaa", "Видео", channel = "Канал", channelUrl = "https://www.youtube.com/channel/UC0")

    private fun textures(): List<TextureView> {
        val found = mutableListOf<TextureView>()
        fun walk(v: View) {
            if (v is TextureView) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(compose.activity.window.decorView)
        return found
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun fullscreenKeepsVideo() {
        var fullscreen by mutableStateOf(false)
        compose.setContent {
            TubeTheme(darkTheme = true, palette = TubePalette.Violet) {
                VideoScreen(
                    player = app.container.playback.player,
                    state = PlaybackState(item = video),
                    settings = PlayerSettings(),
                    subscribed = false,
                    fullscreen = fullscreen,
                    actions = VideoActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                    hideShorts = false,
                )
            }
        }
        settle()
        val first = textures().single()
        repeat(4) {
            compose.runOnIdle { fullscreen = !fullscreen }
            settle()
            val now = textures()
            assertEquals("один вид видео", 1, now.size)
            assertSame("видео не пересоздаётся при смене полноэкранного режима", first, now.single())
            assertTrue(first.isAttachedToWindow)
        }
    }

    @Test
    fun fullscreenPhonePortrait() = fullscreenKeepsVideo()

    @Test
    @Config(qualifiers = "w860dp-h400dp-xxhdpi")
    fun fullscreenPhoneLandscape() = fullscreenKeepsVideo()

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun fullscreenTabletPortrait() = fullscreenKeepsVideo()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun fullscreenTabletLandscape() = fullscreenKeepsVideo()

    /** Страница видео ↔ мини-плеер и каждый лист — открыть и закрыть «назад»; ничего не падает, видео на месте. */
    private fun shellTransitions() {
        val vm = MainViewModel(app)
        compose.runOnUiThread { app.container.playback.preview(PlaybackState(item = video)) }
        var fullscreen by mutableStateOf(false)
        compose.setContent {
            TubeTheme(darkTheme = true, palette = TubePalette.Violet) {
                PlayerShell(vm, ShellActions({}, { fullscreen = it }, {}, {}, {}), pip = false, fullscreen = fullscreen)
            }
        }
        repeat(3) {
            compose.runOnIdle { vm.playerExpanded = true }
            settle()
            assertTrue("страница видео с видео", textures().isNotEmpty())
            compose.runOnIdle { fullscreen = true }
            settle()
            compose.runOnIdle { fullscreen = false }
            settle()
            compose.runOnIdle { vm.playerExpanded = false }
            settle()
            assertEquals("в мини-плеере — одно видео", 1, textures().size)
        }
    }

    private fun sheetsOpenAndClose() {
        val vm = MainViewModel(app)
        compose.runOnUiThread { app.container.playback.preview(PlaybackState(item = video)) }
        var sheet by mutableStateOf(Sheet.None)
        compose.setContent {
            TubeTheme(darkTheme = true, palette = TubePalette.Violet) {
                // Новый лист — новое состояние оболочки: лист берётся из initialSheet.
                androidx.compose.runtime.key(sheet) { PlayerShell(vm, ShellActions({}, {}, {}, {}, {}), pip = false, fullscreen = false, initialSheet = sheet) }
            }
        }
        Sheet.entries.filter { it != Sheet.None }.forEach { s ->
            compose.runOnIdle { sheet = s }
            settle()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            settle()
        }
    }

    @Test
    fun shellPhonePortrait() = shellTransitions()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun shellTabletLandscape() = shellTransitions()

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun shellTabletPortrait() = shellTransitions()

    @Test
    fun sheetsPhone() = sheetsOpenAndClose()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun sheetsTabletLandscape() = sheetsOpenAndClose()
}
