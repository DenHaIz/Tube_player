package com.tube.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tube.player.data.ThemeMode
import com.tube.player.ui.MainViewModel
import com.tube.player.ui.PlayerShell
import com.tube.player.ui.ShellActions
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.theme.TubePalette
import com.tube.ui.theme.TubeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as PlayerApp).container
    private val vm: MainViewModel by viewModels()

    private var pip by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var accountCallback: ((String?) -> Unit)? = null

    /** Системный список аккаунтов: приложение получает только выбранный адрес, без пароля и токенов. */
    private val accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        accountCallback?.invoke(email)
        accountCallback = null
    }

    /** Кнопка «пауза/играть» в окне PiP. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = container.playback.player
            if (player.isPlaying) player.pause() else player.play()
            updatePipParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container.playback.onExpandPopup = {
            startActivity(Intent(applicationContext, MainActivity::class.java).setAction(ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            if (container.settingsRepository.countLaunch() == 1) askNotifications()
        }
        handle(intent)

        val actions = ShellActions(
            enterPip = ::enterPip,
            setFullscreen = ::setFullscreenMode,
            share = { url ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            },
            popup = ::openPopup,
            background = {
                askNotifications()
                moveTaskToBack(true)
            },
            chooseAccount = { callback ->
                val intent = android.accounts.AccountManager.newChooseAccountIntent(
                    null, null, arrayOf("com.google"), null, null, null, null,
                )
                accountCallback = callback
                runCatching { accountPicker.launch(intent) }.onFailure {
                    accountCallback = null
                    callback(null)
                }
            },
            installUpdate = { apk ->
                val updates = container.updates
                // Android 8+: сначала разрешить установку из этого приложения, затем — системный установщик.
                val intent = if (updates.canInstall()) updates.installIntent(apk) else updates.installPermissionIntent()
                runCatching { startActivity(intent) }
            },
        )
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            // Пока настройки читаются (миллисекунды), виден фон окна — без мелькания чужой темы.
            val s = settings ?: return@setContent
            val dark = when (s.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // PiP включается сам при выходе на рабочий стол, пока открыто играющее видео.
            LaunchedEffect(Unit) {
                snapshotFlow { vm.playerExpanded }.collect { updatePipParams() }
            }
            CompositionLocalProvider(LocalLanguage provides s.language) {
                TubeTheme(darkTheme = dark, dynamic = s.dynamicColors, amoled = s.amoled, palette = TubePalette.Violet) {
                    PlayerShell(vm, actions, pip = pip, fullscreen = fullscreen)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Ссылка YouTube, «Поделиться» или нажатие на уведомление. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_OPEN_PLAYER -> {
                container.playback.hidePopup()
                if (container.playback.state.value.item != null) vm.playerExpanded = true
            }
            Intent.ACTION_VIEW -> intent.dataString?.let(vm::openUrl)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("""https?://\S+""").find(it)?.value }
                ?.let(vm::openUrl)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val videoOpen: Boolean get() = vm.playerExpanded && container.playback.state.value.item != null

    private fun pipParams(): PictureInPictureParams {
        val player = container.playback.player
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
        val playing = player.isPlaying
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) R.drawable.popup_pause else R.drawable.popup_play),
            if (playing) "Pause" else "Play",
            if (playing) "Pause" else "Play",
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        val builder = PictureInPictureParams.Builder().setAspectRatio(clamped).setActions(listOf(action))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(videoOpen && container.settings.value?.autoPip != false)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Android 8–11: автоматический PiP при выходе на рабочий стол — здесь (на 12+ его делает система). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && videoOpen && container.playback.player.isPlaying &&
            container.settings.value?.autoPip != false
        ) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    /** Полный экран: горизонтально и без системных панелей. */
    private fun setFullscreenMode(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applySystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    /** Телефон боком с открытым видео — тоже полный экран: панели прячутся. */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val landscapePhone = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.screenWidthDp < 900
        if (fullscreen || (landscapePhone && vm.playerExpanded)) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Плавающее окно; без разрешения «Поверх других приложений» — открыть его настройку. */
    private fun openPopup() {
        if (container.playback.showPopup(onShown = { moveTaskToBack(true) })) {
            vm.playerExpanded = false
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Без фонового режима видео встаёт на паузу, когда приложение скрыто (кроме PiP и плавающего окна).
        val s = container.settings.value
        if (!isChangingConfigurations && !pip && s?.background == false && !container.playback.state.value.popup) {
            container.playback.player.pause()
        }
        container.playback.savePosition()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.tube.player.OPEN_PLAYER"
        private const val ACTION_PIP_TOGGLE = "com.tube.player.PIP_TOGGLE"
    }
}
