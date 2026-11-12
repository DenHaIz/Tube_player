package com.tube.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tube.ui.glass.liquidGlass
import com.tube.ui.glass.glassRim
import androidx.compose.ui.draw.clip
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Сворачивание листа: около 0,4 с — немного короче раскрытия, с разгоном к концу. */
private const val SheetExitMs = 400
private val SheetExitEasing = CubicBezierEasing(0.35f, 0f, 0.65f, 1f)
private val MaxSheetWidth = 640.dp
/** С какой ширины (в горизонтальном виде) лист становится боковой панелью. */
private val SideMinWidth = 840.dp

/**
 * Стеклянный лист поверх экрана. Появляется, «вырастая» снизу,
 * закрывается свайпом вниз, тапом по фону или жестом «назад» с предпросмотром.
 * [solid] — непрозрачный лист (как в YouTube): содержимое экрана под ним не просвечивает.
 * [dockToSide] — на широком экране боком (планшет) лист становится панелью у правого края во всю высоту:
 * снизу по центру он оставлял по бокам пустые поля и был низким. Панель выезжает справа и закрывается свайпом вправо.
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    solid: Boolean = false,
    dockToSide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = dockToSide && maxWidth >= SideMinWidth && maxWidth > maxHeight
        // Ширина панели: ~38% экрана, но не уже 380dp и не шире 480dp.
        val sideWidth = (maxWidth * 0.38f).coerceIn(380.dp, 480.dp)
        SheetBody(visible, onDismiss, solid, side, sideWidth, content)
    }
}

@Composable
private fun SheetBody(
    visible: Boolean,
    onDismiss: () -> Unit,
    solid: Boolean,
    side: Boolean,
    sideWidth: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TubeTheme.colors
    AnimatedVisibility(visible, enter = fadeIn(Motion.snappy()), exit = fadeOut(tween(SheetExitMs))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                // Во время исчезновения фон не перехватывает касания.
                .then(if (visible) Modifier.clickable(interactionSource = null, indication = null, onClick = onDismiss) else Modifier),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = if (side) {
            slideInHorizontally(Motion.soft()) { it } + fadeIn(Motion.snappy())
        } else {
            slideInVertically(Motion.soft()) { it / 3 } +
                scaleIn(Motion.soft(), initialScale = 0.82f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(Motion.snappy())
        },
        exit = if (side) {
            slideOutHorizontally(tween(SheetExitMs - 80, easing = SheetExitEasing)) { it } + fadeOut(tween(SheetExitMs - 80))
        } else {
            slideOutVertically(tween(SheetExitMs, easing = SheetExitEasing)) { it } +
                scaleOut(tween(SheetExitMs, easing = SheetExitEasing), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(tween(SheetExitMs - 120, delayMillis = 120))
        },
    ) {
        if (side) {
            SidePanel(visible, onDismiss, solid, sideWidth, content)
            return@AnimatedVisibility
        }
        // Сдвиг листа вниз пальцем; анимация — только при отпускании.
        var drag by remember { mutableFloatStateOf(0f) }
        var backProgress by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val dismissDistance = with(LocalDensity.current) { 110.dp.toPx() }
        // Быстрый взмах закрывает лист, только если его действительно потянули, а не дёрнуло раскладкой
        // (например, когда прячется клавиатура и лист съезжает вниз).
        val flingDistance = with(LocalDensity.current) { 28.dp.toPx() }
        val currentDismiss by rememberUpdatedState(onDismiss)

        fun settle(velocity: Float) {
            if (drag > dismissDistance || (velocity > 1600f && drag > flingDistance)) {
                currentDismiss()
            } else {
                scope.launch { animate(drag, 0f, animationSpec = Motion.bouncy()) { v, _ -> drag = v } }
            }
        }

        // Тянуть вниз можно за любое место: пока содержимое не у верха — оно прокручивается,
        // дальше жест «перетекает» в сдвиг всего листа.
        val nestedScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && drag > 0f) {
                        val used = maxOf(available.y, -drag)
                        drag += used
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) {
                        drag += available.y
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag > 0f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        PredictiveBackHandler(enabled = visible) { events ->
            try {
                events.collect { backProgress = it.progress }
                onDismiss()
            } catch (e: CancellationException) {
                backProgress = 0f
                throw e
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // Сверху и по бокам — вырез камеры и системные панели (в горизонтальном виде они сбоку).
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val maxSheetHeight = maxHeight - 12.dp
            Column(
                Modifier
                    .padding(horizontal = 8.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
                    .graphicsLayer {
                        translationY = drag
                        val s = 1f - backProgress * 0.08f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .heightIn(max = maxSheetHeight)
                    // На планшете и в горизонтальном виде лист не растягивается во всю ширину.
                    .widthIn(max = MaxSheetWidth)
                    .fillMaxWidth()
                    .then(
                        if (solid) {
                            Modifier
                                .background(colors.background, Shapes.Sheet)
                                .glassRim(Shapes.Sheet, colors.glassStroke)
                                .clip(Shapes.Sheet)
                        } else {
                            Modifier.liquidGlass(Shapes.Sheet, tint = colors.background.copy(alpha = if (colors.isDark) 0.74f else 0.66f))
                        },
                    )
                    .then(
                        if (visible) {
                            Modifier.clickable(interactionSource = null, indication = null) { /* не пропускать тапы к фону */ }
                        } else {
                            Modifier
                        },
                    )
                    .nestedScroll(nestedScroll)
                    // Участки без прокрутки (ручка, заголовок) тоже тянут лист.
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = { settle(tracker.calculateVelocity().y) },
                            onDragCancel = { settle(0f) },
                        ) { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            drag = (drag + dy).coerceAtLeast(0f)
                        }
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 5.dp)
                            .background(colors.textTertiary.copy(alpha = 0.5f), RoundedCornerShape(50)),
                    )
                }
                content()
            }
        }
    }
}

/**
 * Лист-панель у правого края во всю высоту (планшет боком). Внутри — тот же контент, прокрутка — вертикальная,
 * поэтому закрывается свайпом вправо (за любое место без прокрутки), тапом по фону или «назад».
 */
@Composable
private fun SidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    solid: Boolean,
    width: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TubeTheme.colors
    var drag by remember { mutableFloatStateOf(0f) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistance = with(LocalDensity.current) { 110.dp.toPx() }
    val flingDistance = with(LocalDensity.current) { 28.dp.toPx() }
    val currentDismiss by rememberUpdatedState(onDismiss)
    fun settle(velocity: Float) {
        if (drag > dismissDistance || (velocity > 1600f && drag > flingDistance)) {
            currentDismiss()
        } else {
            scope.launch { animate(drag, 0f, animationSpec = Motion.bouncy()) { v, _ -> drag = v } }
        }
    }
    PredictiveBackHandler(enabled = visible) { events ->
        try {
            events.collect { backProgress = it.progress }
            onDismiss()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    val shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .graphicsLayer {
                    translationX = drag
                    val s = 1f - backProgress * 0.06f
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .fillMaxHeight()
                .width(width)
                .then(
                    if (solid) {
                        Modifier.background(colors.background, shape).glassRim(shape, colors.glassStroke).clip(shape)
                    } else {
                        Modifier.liquidGlass(shape, tint = colors.background.copy(alpha = if (colors.isDark) 0.74f else 0.66f))
                    },
                )
                .then(if (visible) Modifier.clickable(interactionSource = null, indication = null) { } else Modifier)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = { settle(tracker.calculateVelocity().x) },
                        onDragCancel = { settle(0f) },
                    ) { change, dx ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        drag = (drag + dx).coerceAtLeast(0f)
                    }
                }
                // Сверху и снизу — системные панели, справа — вырез камеры: содержимое не заходит под них.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                .imePadding()
                .padding(top = 12.dp),
        ) {
            content()
        }
    }
}
