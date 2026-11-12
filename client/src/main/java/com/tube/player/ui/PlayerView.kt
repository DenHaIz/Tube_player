@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.animation.togetherWith
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.compose.ui.draw.clipToBounds
import com.tube.player.data.PlayerSettings
import com.tube.player.player.PlaybackState
import com.tube.player.player.qualityLabel
import com.tube.player.sponsorblock.SegmentAction
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
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
    /** Пропорции кадра (ширина / высота) — для расчёта «растянуть во весь экран». */
    var aspect by mutableFloatStateOf(16f / 9f)
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
                val v = p.videoSize
                if (v.width > 0 && v.height > 0) progress.aspect = v.width * v.pixelWidthHeightRatio / v.height
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
 * Видео со своими контролами и жестами как в YouTube:
 * - тап — показать/спрятать контролы; двойной тап слева/справа — ±10 с (ещё тапы на той же стороне — ещё ±10 с);
 * - смахнуть вниз — свернуть в мини-плеер (в полноэкранном — выйти из него), вверх — во весь экран;
 * - щипок во весь экран — растянуть видео без рамок или вернуть как было.
 * Яркость и громкость жестами не меняются: видео идёт с яркостью и громкостью устройства.
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
    /** Палец тянет плеер по вертикали: смещение от начала жеста (px). */
    onSwipe: (Float) -> Unit = {},
    /** Палец отпущен: итоговое смещение и скорость (px, px/с); решение — свернуть, во весь экран или вернуть — за страницей. */
    onSwipeEnd: (Float, Float) -> Unit = { _, _ -> },
) {
    val progress = rememberProgress(player)
    var controls by remember { mutableStateOf(initiallyVisible) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    val lang = LocalLanguage.current
    val scope = rememberCoroutineScope()

    // Перемотка двойным тапом: сторона запоминается отдельно — исчезающая подсказка не «перепрыгивает» на другую сторону.
    var seekSide by remember { mutableIntStateOf(1) }
    var seekTotal by remember { mutableIntStateOf(0) }
    var seekVisible by remember { mutableStateOf(false) }
    var seekAt by remember { mutableLongStateOf(0L) }
    // Растянуть во весь экран без рамок (обрезая края) — только в полноэкранном режиме.
    var fill by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controls, touchedAt, progress.playing) {
        if (controls && progress.playing) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(seekAt) {
        if (seekAt != 0L) {
            delay(800)
            seekVisible = false
            seekTotal = 0
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1200)
            hint = null
        }
    }
    LaunchedEffect(fullscreen) { if (!fullscreen) fill = false }
    // Масштаб видео: 1 — вписано целиком, [fillScale] — заполняет экран (края обрезаются).
    val zoom = remember { androidx.compose.animation.core.Animatable(1f) }
    var gestureCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var pinching by remember { mutableStateOf(false) }

    fun seek(side: Int) {
        if (seekVisible && side != seekSide) seekTotal = 0
        seekSide = side
        seekTotal += 10
        seekVisible = true
        seekAt = System.currentTimeMillis()
        controls = false
        player?.let { if (side > 0) it.seekForward() else it.seekBack() }
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val width = constraints.maxWidth.toFloat()
        val boxAspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
        val fillScale = if (fullscreen) maxOf(boxAspect / progress.aspect, progress.aspect / boxAspect).coerceIn(1f, 3f) else 1f
        LaunchedEffect(fill, fillScale, pinching) {
            if (!pinching) zoom.animateTo(if (fill && fullscreen) fillScale else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f))
        }
        // Видео или обложка. TextureView, а не SurfaceView: поверхность SurfaceView — «дыра» под окном, она не обрезается
        // и отстаёт от анимаций (при свайпе видео «проваливалось» под текст); TextureView движется вместе с экраном.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            when {
                player != null && !state.popup -> VideoSurface(
                    player,
                    progress.aspect,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                    placeholder = state.item?.let { com.tube.player.youtube.thumbnailCandidates(it) }.orEmpty(),
                )
                else -> NetImage(state.item?.let { com.tube.player.youtube.thumbnailCandidates(it) }.orEmpty(), Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        if (pipMode) return@BoxWithConstraints

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    // Двойной тап распознаётся сами, без onDoubleTap: тот задерживает одиночный тап на ~0,3 с,
                    // и контролы появлялись с запозданием. Теперь первый тап показывает их сразу, а второй
                    // в той же половине в пределах 300 мс — перемотка (контролы при этом прячутся).
                    var lastTapAt = 0L
                    var lastTapSide = 0
                    detectTapGestures(
                        onTap = { offset ->
                            val side = if (offset.x > width / 2) 1 else -1
                            val now = System.currentTimeMillis()
                            when {
                                // Сразу после двойного тапа одиночные тапы продолжают перемотку, как в YouTube.
                                seekVisible && now - seekAt < 800 -> seek(side)
                                now - lastTapAt < DoubleTapMs && side == lastTapSide -> seek(side)
                                else -> {
                                    controls = !controls
                                    touchedAt = now
                                }
                            }
                            lastTapAt = now
                            lastTapSide = side
                        },
                    )
                }
                .onGloballyPositioned { gestureCoords = it }
                .pointerInput(fullscreen) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Положение пальца — в координатах окна: плеер сам едет вместе со страницей, и в его
                        // собственных координатах палец «стоял бы на месте» — отсюда были рывки.
                        fun windowY(change: androidx.compose.ui.input.pointer.PointerInputChange): Float =
                            gestureCoords?.takeIf { it.isAttached }?.localToWindow(change.position)?.y ?: change.position.y
                        val startY = windowY(down)
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        var swipeY = 0f
                        var pan = Offset.Zero
                        var pinchZoom = 1f
                        var vertical = false
                        var pinch = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                pinch = true
                                pinchZoom *= event.calculateZoom()
                                event.changes.forEach { it.consume() }
                                if (fullscreen) {
                                    // Видео следует за пальцами (с лёгким «резиновым» запасом за пределами).
                                    pinching = true
                                    val base = if (fill) fillScale else 1f
                                    scope.launch { zoom.snapTo((base * pinchZoom).coerceIn(0.9f, fillScale * 1.1f)) }
                                }
                            } else if (!pinch) {
                                pan += event.calculatePan()
                                val change = event.changes.first()
                                val y = windowY(change)
                                tracker.addPosition(change.uptimeMillis, Offset(0f, y))
                                if (!vertical && abs(pan.y) > viewConfiguration.touchSlop && abs(pan.y) > abs(pan.x) * 1.5f) vertical = true
                                if (vertical) {
                                    event.changes.forEach { it.consume() }
                                    swipeY = y - startY
                                    onSwipe(swipeY)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        when {
                            pinch && fullscreen && pinchZoom > 1.05f && !fill -> {
                                fill = true
                                hint = lang.pick("Во весь экран", "Zoomed to fill")
                            }
                            pinch && fullscreen && pinchZoom < 0.95f && fill -> {
                                fill = false
                                hint = lang.pick("Исходный размер", "Original size")
                            }
                            vertical -> onSwipeEnd(swipeY, tracker.calculateVelocity().y)
                        }
                        // После щипка масштаб плавно доезжает до выбранного состояния.
                        pinching = false
                    }
                },
        )

        AnimatedVisibility(
            progress.buffering || (state.loading && state.error == null),
            Modifier.align(Alignment.Center),
            enter = fadeIn(Anim.fade()),
            exit = fadeOut(Anim.out()),
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        }

        // Перемотка: полукруглая подсветка на стороне тапа, стрелки и сумма секунд.
        AnimatedVisibility(
            seekVisible,
            Modifier.align(if (seekSide > 0) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.38f),
            enter = fadeIn(Anim.fade()),
            exit = fadeOut(Anim.out()),
        ) {
            val shape = if (seekSide > 0) {
                RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
            } else {
                RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
            }
            Column(
                Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f), shape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (seekSide > 0) TubeIcons.Forward else TubeIcons.Rewind, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(4.dp))
                Text(lang.pick("$seekTotal секунд", "$seekTotal seconds"), style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        hint?.let { Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }

        // Тонкая полоса просмотренного с отметками SponsorBlock, когда контролы спрятаны (как в YouTube).
        AnimatedVisibility(
            !controls && !fullscreen && state.item?.live != true,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(Anim.fade()),
            exit = fadeOut(Anim.out()),
        ) {
            ThinProgress(progress, visibleSegments(state, settings))
        }

        AnimatedVisibility(controls && state.error == null, Modifier.fillMaxSize(), enter = fadeIn(androidx.compose.animation.core.tween(90)), exit = fadeOut(Anim.out())) {
            Controls(
                player, state, progress, fullscreen,
                onToggle = { touchedAt = System.currentTimeMillis() },
                onFullscreen = onFullscreen, onMinimize = onMinimize, onQuality = onQuality, onPopup = onPopup, onPip = onPip,
                settings = settings,
                fill = fill,
                onFill = {
                    fill = !fill
                    hint = if (fill) lang.pick("Во весь экран", "Zoomed to fill") else lang.pick("Исходный размер", "Original size")
                },
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

        AnimatedVisibility(state.error != null, Modifier.align(Alignment.Center), enter = fadeIn(Anim.fade()), exit = fadeOut(Anim.out())) {
            val error = state.error.orEmpty()
            Column(
                Modifier.padding(16.dp),
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

/** Время «1:23 / 4:56» — единственное, что перерисовывается каждую четверть секунды в контролах. */
@Composable
private fun TimeLabel(progress: PlayerProgress, live: Boolean) {
    Text(
        if (live) tr("Эфир", "Live") else Format.millis(progress.position) + " / " + Format.millis(progress.duration),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
    )
}

/** Полоса 3dp внизу плеера; позиция читается только при рисовании — без перекомпоновки. */
@Composable
private fun ThinProgress(progress: PlayerProgress, segments: List<com.tube.player.sponsorblock.Segment>) {
    val accent = TubeTheme.colors.accent
    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
        val duration = progress.duration
        drawRect(Color.White.copy(alpha = 0.25f))
        if (duration <= 0) return@Canvas
        drawRect(accent, size = Size(size.width * (progress.position.toFloat() / duration).coerceIn(0f, 1f), size.height))
        segments.forEach { seg ->
            val x0 = size.width * seg.startMs / duration
            val x1 = size.width * seg.endMs / duration
            drawRect(Color(seg.category.color), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height))
        }
    }
}

/** Отрезки SponsorBlock, которые видны на полосе (категория не выключена). */
private fun visibleSegments(state: PlaybackState, settings: PlayerSettings) =
    if (settings.sponsorBlock) state.segments.filter { settings.sponsor[it.category] != SegmentAction.Off } else emptyList()

private const val DoubleTapMs = 300L

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
    fill: Boolean,
    onFill: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
        // Сверху: свернуть, качество, окно, PiP.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullscreen) {
                ControlIcon(if (fill) TubeIcons.Shrink else TubeIcons.Expand, if (fill) tr("Исходный размер", "Original size") else tr("Растянуть", "Zoom to fill")) {
                    onToggle()
                    onFill()
                }
            } else {
                ControlIcon(TubeIcons.ChevronDown, tr("Свернуть", "Minimize"), onClick = onMinimize)
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
                    animate = true,
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
        // Во весь экран полоса выше: снизу жестовая панель системы, и палец не должен цеплять её.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = if (fullscreen) 28.dp else 14.dp)
                .padding(top = 6.dp, bottom = if (fullscreen) 36.dp else 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val live = state.item?.live == true
                TimeLabel(progress, live)
                Spacer(Modifier.weight(1f))
                ControlIcon(if (fullscreen) TubeIcons.FullscreenExit else TubeIcons.Fullscreen, tr("Во весь экран", "Fullscreen")) { onFullscreen(!fullscreen) }
            }
            if (state.item?.live != true) {
                SeekBar(
                    position = { progress.position },
                    buffered = { progress.buffered },
                    duration = progress.duration,
                    segments = visibleSegments(state, settings),
                    onSeek = { onToggle(); player?.seekTo(it) },
                    onScrub = onToggle,
                )
            }
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, description: String, size: Int = 44, animate: Boolean = false, onClick: () -> Unit) {
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
        if (animate) {
            // Пауза ↔ play: быстрое перетекание значков с лёгким масштабом.
            androidx.compose.animation.AnimatedContent(
                icon,
                transitionSpec = {
                    (fadeIn(Anim.fade()) + androidx.compose.animation.scaleIn(Anim.move(), initialScale = 0.7f)) togetherWith
                        (fadeOut(Anim.out()) + androidx.compose.animation.scaleOut(Anim.move(), targetScale = 0.7f))
                },
                label = "playPause",
            ) { target -> Icon(target, null, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp)) }
        } else {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp))
        }
    }
}

/** Полоса прокрутки: загружено, просмотрено, цветные отрезки SponsorBlock; тап или перетаскивание — перемотка. */
@Composable
fun SeekBar(
    position: () -> Long,
    buffered: () -> Long,
    duration: Long,
    segments: List<com.tube.player.sponsorblock.Segment>,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = TubeTheme.colors.accent
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    // Позиция читается внутри рисования — полоса обновляется без перекомпоновки контролов.
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
            val fraction = if (dragFraction >= 0) dragFraction else if (duration > 0) position().toFloat() / duration else 0f
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), r)
            if (duration > 0) {
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * (buffered().toFloat() / duration).coerceIn(0f, 1f), h), r)
            }
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction.coerceIn(0f, 1f), h), r)
            // Отрезки SponsorBlock — поверх просмотренного, чтобы метка рекламы (зелёная) была видна всегда.
            if (duration > 0) {
                segments.forEach { s ->
                    val x0 = size.width * s.startMs / duration
                    val x1 = size.width * s.endMs / duration
                    drawRect(Color(s.category.color), Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), h))
                }
            }
            val knob = if (dragFraction >= 0) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(accent, knob, Offset(size.width * fraction.coerceIn(0f, 1f), size.height / 2))
        }
    }
}

/** Цвет категории SponsorBlock — для легенды в настройках. */
fun com.tube.player.sponsorblock.SponsorCategory.composeColor() = Color(color)
