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
 * [keepScreenOn] — пока видео играет и видно, экран не гаснет (стандартный PlayerView делает это сам, свой вид — нет).
 */
@Composable
fun VideoSurface(
    player: Player,
    aspect: Float,
    modifier: Modifier = Modifier,
    placeholder: List<String> = emptyList(),
    keepScreenOn: Boolean = false,
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
            factory = { context -> TextureView(context).apply { holder.attach(this) } },
            update = { it.keepScreenOn = keepScreenOn },
            modifier = Modifier.aspectRatio(aspect.coerceIn(0.3f, 4f)),
        )
    }
}

/**
 * Связка «поверхность TextureView ↔ плеер»: отдать готовую поверхность, забрать при окончательном уходе.
 *
 * Поверхность (SurfaceTexture) живёт дольше самого вида: когда плеер переносится между раскладками
 * (полноэкранный режим, поворот), вид на миг отсоединяется от окна — TextureView в этот момент уничтожил бы
 * поверхность, и видео пропало бы до следующего кадра (мелькала обложка). Здесь она сохраняется
 * (`onSurfaceTextureDestroyed` → false) и при возвращении вида в окно подставляется обратно — с последним кадром.
 */
private class SurfaceHolder(private val player: Player, private val onFirstFrame: () -> Unit) :
    TextureView.SurfaceTextureListener, android.view.View.OnAttachStateChangeListener {
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var notified = false
    private var released = false
    /** Поверхность у нас, а не у вида (вид отсоединён) — освобождать её при уходе нам. */
    private var kept = false

    fun attach(view: TextureView) {
        view.surfaceTextureListener = this
        view.addOnAttachStateChangeListener(this)
    }

    override fun onViewAttachedToWindow(v: android.view.View) {
        val view = v as TextureView
        val saved = texture
        if (!released && saved != null && view.surfaceTexture !== saved) {
            runCatching { view.setSurfaceTexture(saved) }.onSuccess { kept = false }
        }
    }

    override fun onViewDetachedFromWindow(v: android.view.View) = Unit

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        if (this.texture === texture && surface != null) {
            player.setVideoSurface(surface)
            return
        }
        surface?.release()
        this.texture?.takeIf { it !== texture }?.release()
        this.texture = texture
        val s = Surface(texture)
        surface = s
        player.setVideoSurface(s)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    /** Вид отсоединён: поверхность не отдаём системе — она вернётся при переносе. Освобождает её [release]. */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (released) return true
        kept = true
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!notified) {
            notified = true
            onFirstFrame()
        }
    }

    /** Вид ушёл совсем: убрать свою поверхность у плеера (если плеер уже на другой — ничего не меняется) и освободить. */
    fun release() {
        released = true
        surface?.let {
            player.clearVideoSurface(it)
            it.release()
        }
        surface = null
        // Если поверхность ещё у вида — он освободит её сам при отсоединении (onSurfaceTextureDestroyed → true).
        if (kept) texture?.release()
        texture = null
    }
}
