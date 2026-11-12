@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import com.tube.player.data.PlayerSettings
import com.tube.player.player.PlaybackState
import com.tube.player.player.qualityLabel
import com.tube.player.sponsorblock.SegmentAction
import com.tube.player.sponsorblock.SponsorCategory
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import kotlinx.coroutines.delay

/** Позиция, длительность и состояние плеера — обновляются 4 раза в секунду, пока виден экран. */
class PlayerProgress {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
}

@Composable
fun rememberProgress(player: Player?): PlayerProgress {
    val progress = remember { PlayerProgress() }
    if (player == null) return progress
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                progress.playing = p.isPlaying
                progress.buffering = p.playbackState == Player.STATE_BUFFERING
                progress.ended = p.playbackState == Player.STATE_ENDED
            }
        }
        player.addListener(listener)
        listener.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            progress.position = player.currentPosition
            progress.duration = player.duration.coerceAtLeast(0)
            progress.buffered = player.bufferedPosition
            delay(250)
        }
    }
    return progress
}

/**
 * Видео со своими стеклянными контролами: тап — показать/спрятать, двойной тап слева/справа — ±10 с,
 * свайп по левой половине — яркость, по правой — громкость (в полноэкранном режиме, как в ReVanced).
 * [player] = null — превью/скриншот: вместо видео обложка.
 */
@Composable
fun PlayerView(
    player: Player?,
    state: PlaybackState,
    settings: PlayerSettings,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onQuality: () -> Unit,
    onPopup: () -> Unit,
    onPip: () -> Unit,
    onSkip: () -> Unit,
    onUndoSkip: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    pipMode: Boolean = false,
    initiallyVisible: Boolean = false,
) {
    val progress = rememberProgress(player)
    var controls by remember { mutableStateOf(initiallyVisible) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var seekFlash by remember { mutableIntStateOf(0) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lang = LocalLanguage.current

    // Контролы прячутся через 3 с, пока видео играет.
    LaunchedEffect(controls, touchedAt, progress.playing) {
        if (controls && progress.playing) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != 0) {
            delay(650)
            seekFlash = 0
        }
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val width = constraints.maxWidth.toFloat()
        // Видео или обложка.
        when {
            player != null && !state.popup -> ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> NetImage(state.item?.thumbnail, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        if (pipMode) return@BoxWithConstraints

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            controls = !controls
                            touchedAt = System.currentTimeMillis()
                        },
                        onDoubleTap = { offset ->
                            val forward = offset.x > width / 2
                            player?.let { if (forward) it.seekForward() else it.seekBack() }
                            seekFlash = if (forward) 1 else -1
                        },
                    )
                }
                .then(
                    if (fullscreen && settings.gestures) {
                        Modifier.pointerInput(Unit) {
                            var left = true
                            detectDragGestures(
                                onDragStart = { left = it.x < width / 2 },
                                onDragEnd = { gestureLabel = null },
                                onDragCancel = { gestureLabel = null },
                            ) { change, drag ->
                                if (kotlin.math.abs(drag.y) < kotlin.math.abs(drag.x)) return@detectDragGestures
                                change.consume()
                                val delta = -drag.y / size.height
                                gestureLabel = if (left) adjustBrightness(activity, delta, lang) else adjustVolume(context, delta, lang)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        )

        if (progress.buffering || (state.loading && state.error == null)) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }

        // Вспышка «−10 с / +10 с» после двойного тапа.
        AnimatedVisibility(seekFlash != 0, Modifier.align(if (seekFlash > 0) Alignment.CenterEnd else Alignment.CenterStart), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
            Pill(if (seekFlash > 0) tr("+10 с", "+10 s") else tr("−10 с", "−10 s"), Modifier.padding(horizontal = 40.dp))
        }
        gestureLabel?.let { Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }

        AnimatedVisibility(controls && state.error == null, Modifier.fillMaxSize(), enter = fadeIn(Anim.fade()), exit = fadeOut(Anim.out())) {
            Controls(
                player, state, progress, fullscreen,
                onToggle = { touchedAt = System.currentTimeMillis() },
                onFullscreen = onFullscreen, onMinimize = onMinimize, onQuality = onQuality, onPopup = onPopup, onPip = onPip,
                settings = settings,
            )
        }

        // SponsorBlock: кнопка «Пропустить» и подсказка «Пропущено» с отменой.
        val button = state.skipButton
        AnimatedVisibility(
            button != null,
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (controls) 72.dp else 20.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            button?.let { PillButton(tr("Пропустить: ", "Skip: ") + lang.pick(it.category.ru, it.category.en), onClick = onSkip, icon = TubeIcons.Skip, filled = true, compact = true) }
        }
        val skipped = state.skipped
        AnimatedVisibility(
            skipped != null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 72.dp else 20.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            skipped?.let {
                Row(
                    Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedPill).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Пропущено: ", "Skipped: ") + lang.pick(it.category.ru, it.category.en), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    PillButton(tr("Отменить", "Undo"), onClick = onUndoSkip, compact = true, color = Color.White)
                }
            }
        }

        state.error?.let { error ->
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("Видео не загрузилось", "Video failed to load"), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 3)
                PillButton(tr("Повторить", "Retry"), onClick = onRetry, filled = true, compact = true)
            }
        }
    }
}

private val RoundedPill = androidx.compose.foundation.shape.RoundedCornerShape(50)

@Composable
private fun Controls(
    player: Player?,
    state: PlaybackState,
    progress: PlayerProgress,
    fullscreen: Boolean,
    onToggle: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onQuality: () -> Unit,
    onPopup: () -> Unit,
    onPip: () -> Unit,
    settings: PlayerSettings,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
        // Сверху: свернуть, качество, окно, PiP.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(if (fullscreen) TubeIcons.FullscreenExit else TubeIcons.ChevronDown, tr("Свернуть", "Minimize")) {
                if (fullscreen) onFullscreen(false) else onMinimize()
            }
            Spacer(Modifier.weight(1f))
            ControlIcon(TubeIcons.Popup, tr("Плавающее окно", "Floating window"), onClick = onPopup)
            ControlIcon(TubeIcons.Pip, tr("Картинка в картинке", "Picture-in-picture"), onClick = onPip)
            ControlIcon(TubeIcons.Gear, tr("Качество", "Quality")) { onToggle(); onQuality() }
        }
        // В центре: назад 10 с, пауза, вперёд 10 с.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(TubeIcons.Rewind, tr("Назад 10 с", "Back 10 s"), size = 56) { onToggle(); player?.seekBack() }
            if (!progress.buffering) {
                ControlIcon(
                    when {
                        progress.ended -> TubeIcons.History
                        progress.playing -> TubeIcons.Pause
                        else -> TubeIcons.Play
                    },
                    if (progress.playing) tr("Пауза", "Pause") else tr("Играть", "Play"),
                    size = 68,
                ) {
                    onToggle()
                    player?.let {
                        when {
                            progress.ended -> { it.seekTo(0); it.play() }
                            it.isPlaying -> it.pause()
                            else -> it.play()
                        }
                    }
                }
            } else {
                Spacer(Modifier.size(68.dp))
            }
            ControlIcon(TubeIcons.Forward, tr("Вперёд 10 с", "Forward 10 s"), size = 56) { onToggle(); player?.seekForward() }
        }
        // Снизу: время, полоса с отрезками SponsorBlock, полный экран.
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val live = state.item?.live == true
                Text(
                    if (live) tr("Эфир", "Live") else Format.millis(progress.position) + " / " + Format.millis(progress.duration),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                ControlIcon(if (fullscreen) TubeIcons.FullscreenExit else TubeIcons.Fullscreen, tr("Во весь экран", "Fullscreen")) { onFullscreen(!fullscreen) }
            }
            if (state.item?.live != true) {
                SeekBar(
                    position = progress.position,
                    buffered = progress.buffered,
                    duration = progress.duration,
                    segments = if (settings.sponsorBlock) state.segments.filter { settings.sponsor[it.category] != SegmentAction.Off } else emptyList(),
                    onSeek = { onToggle(); player?.seekTo(it) },
                    onScrub = onToggle,
                )
            }
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, description: String, size: Int = 44, onClick: () -> Unit) {
    // Крупные кнопки по центру — на полупрозрачном круге, как в плеере YouTube.
    val round = size >= 52
    Box(
        Modifier
            .size(size.dp)
            .bouncyClickable(pressedScale = 0.88f, onClick = onClick)
            .then(if (round) Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedPill) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp))
    }
}

/** Полоса прокрутки: загружено, просмотрено, цветные отрезки SponsorBlock; тап или перетаскивание — перемотка. */
@Composable
fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    segments: List<com.tube.player.sponsorblock.Segment>,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = TubeTheme.colors.accent
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val fraction = if (dragFraction >= 0) dragFraction else if (duration > 0) position.toFloat() / duration else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        if (duration > 0 && dragFraction >= 0) onSeek((dragFraction * duration).toLong())
                        dragFraction = -1f
                    },
                    onDragCancel = { dragFraction = -1f },
                ) { change, _ ->
                    change.consume()
                    onScrub()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), r)
            if (duration > 0) {
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * (buffered.toFloat() / duration).coerceIn(0f, 1f), h), r)
                segments.forEach { s ->
                    val x0 = size.width * s.startMs / duration
                    val x1 = size.width * s.endMs / duration
                    drawRect(Color(s.category.color), Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), h))
                }
            }
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction.coerceIn(0f, 1f), h), r)
            val knob = if (dragFraction >= 0) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(accent, knob, Offset(size.width * fraction.coerceIn(0f, 1f), size.height / 2))
        }
    }
}

/** Яркость окна (не системная): вверх — ярче. Возвращает подпись «Яркость 60%». */
private fun adjustBrightness(activity: Activity?, delta: Float, lang: com.tube.ui.i18n.AppLanguage): String? {
    val window = activity?.window ?: return null
    val attrs = window.attributes
    val current = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
    val next = (current + delta).coerceIn(0.02f, 1f)
    attrs.screenBrightness = next
    window.attributes = attrs
    return lang.pick("Яркость", "Brightness") + " ${(next * 100).toInt()}%"
}

private var volumeAccumulator = 0f

/** Громкость музыки: вверх — громче, шаг — по мере движения пальца. */
private fun adjustVolume(context: Context, delta: Float, lang: com.tube.ui.i18n.AppLanguage): String {
    val audio = context.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    volumeAccumulator += delta * max
    val steps = volumeAccumulator.toInt()
    if (steps != 0) {
        volumeAccumulator -= steps
        val next = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    }
    val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    return lang.pick("Громкость", "Volume") + " ${now * 100 / max.coerceAtLeast(1)}%"
}

/** Цвет категории SponsorBlock — для легенды в настройках. */
fun SponsorCategory.composeColor() = Color(color)
