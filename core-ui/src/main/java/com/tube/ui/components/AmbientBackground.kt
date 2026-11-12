package com.tube.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tube.ui.theme.Motion
import com.tube.ui.theme.TubeTheme
import kotlin.random.Random

/**
 * Мягкий «mesh»-фон из цветных пятен в цветах категорий дня — то, что преломляет стекло.
 * Намеренно без бесконечных анимаций: пятна плавно перетекают только при смене дня,
 * а в покое GPU ничего не перерисовывает.
 */
@Composable
fun AmbientBackground(colors: List<Color>, seed: Long, modifier: Modifier = Modifier) {
    val theme = TubeTheme.colors
    val palette = (colors + theme.ambient).take(3)
    val random = Random(seed)
    val blobs = List(3) { index ->
        Blob(
            color = animateColorAsState(palette[index], Motion.soft(), label = "blob$index"),
            x = animateFloatAsState(BlobAnchors[index].x + random.nextFloat() * 0.3f - 0.15f, Motion.soft(), label = "x$index"),
            y = animateFloatAsState(BlobAnchors[index].y + random.nextFloat() * 0.2f - 0.1f, Motion.soft(), label = "y$index"),
            radius = 0.68f + random.nextFloat() * 0.3f,
        )
    }
    val strength = (if (theme.isDark) 0.40f else 0.46f) * theme.ambientStrength
    Canvas(modifier) {
        drawRect(theme.background)
        blobs.forEach { drawBlob(it, strength) }
    }
}

private class Blob(
    val color: State<Color>,
    val x: State<Float>,
    val y: State<Float>,
    val radius: Float,
)

private val BlobAnchors = listOf(Offset(0.15f, 0.12f), Offset(0.9f, 0.38f), Offset(0.3f, 0.85f))

private fun DrawScope.drawBlob(blob: Blob, strength: Float) {
    val center = Offset(size.width * blob.x.value, size.height * blob.y.value)
    val radius = size.width * blob.radius
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(blob.color.value.copy(alpha = strength), blob.color.value.copy(alpha = strength * 0.5f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
