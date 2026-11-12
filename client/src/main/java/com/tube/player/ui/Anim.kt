package com.tube.player.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Анимации плеера: быстрые (~150–250 мс) и плавные, без «пружинистого» перелёта —
 * экраны, панели, мини-плеер и чипы отзываются сразу.
 */
object Anim {
    /** Перемещения: смена экрана, выезд страницы видео, мини-плеер. */
    fun <T> move(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.92f, stiffness = 650f)

    /** Цвет, прозрачность, мелкие изменения. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(durationMillis = 160)

    /** Исчезновение — чуть быстрее появления. */
    fun <T> out(): FiniteAnimationSpec<T> = tween(durationMillis = 120)
}
