package com.tube.player.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/**
 * Видео на TextureView, вписанное по пропорциям кадра.
 *
 * В отличие от ContentFrame, плеер получает поверхность только когда она уже готова (`setVideoSurface`),
 * и переключается со старой на новую сразу — без промежуточного «нет поверхности». Поэтому при переходе
 * страница видео → мини-плеер (и обратно) кадр не пропадает: старый вид держит последний кадр, новый
 * показывает видео с ближайшего кадра. Пока первого кадра нет — под видом обложка [placeholder].
 */
@Composable
fun VideoSurface(
    player: Player,
    aspect: Float,
    modifier: Modifier = Modifier,
    placeholder: List<String> = emptyList(),
) {
    var hasFrame by remember(player) { mutableStateOf(false) }
    val holder = remember(player) { SurfaceHolder(player) { hasFrame = true } }
    DisposableEffect(holder) { onDispose { holder.release() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (placeholder.isNotEmpty()) {
            AnimatedVisibility(!hasFrame, Modifier.fillMaxSize(), enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(Anim.out())) {
                NetImage(placeholder, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        AndroidView(
            factory = { context -> TextureView(context).apply { surfaceTextureListener = holder } },
            modifier = Modifier.aspectRatio(aspect.coerceIn(0.3f, 4f)),
        )
    }
}

/** Связка «поверхность TextureView ↔ плеер»: отдать готовую поверхность, забрать при уничтожении. */
private class SurfaceHolder(private val player: Player, private val onFirstFrame: () -> Unit) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null
    private var notified = false

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(texture)
        surface = s
        player.setVideoSurface(s)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!notified) {
            notified = true
            onFirstFrame()
        }
    }

    /** Убрать свою поверхность у плеера; если плеер уже на другой — ничего не меняется. */
    fun release() {
        surface?.let {
            player.clearVideoSurface(it)
            it.release()
        }
        surface = null
    }
}
