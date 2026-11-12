@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tube.player.R
import kotlin.math.roundToInt

/**
 * Плавающее окно плеера поверх других приложений (как popup у NewPipe), отдельно от PiP:
 * перетаскивается пальцем, меняет размер щипком, по двойному тапу разворачивается в приложение,
 * а если бросить его к нижнему краю экрана — закрывается.
 */
@SuppressLint("ClickableViewAccessibility")
class PopupPlayer(
    private val context: Context,
    private val player: ExoPlayer,
    private val onExpand: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val screenWidth get() = context.resources.displayMetrics.widthPixels
    private val screenHeight get() = context.resources.displayMetrics.heightPixels

    private val texture = TextureView(context)
    private val playPause: ImageView = button(R.drawable.popup_pause) { if (player.isPlaying) player.pause() else player.play() }
    private val controls: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        addView(button(R.drawable.popup_expand) { expand() })
        addView(playPause)
        addView(button(R.drawable.popup_close) { close() })
        alpha = 0f
        visibility = View.GONE
    }
    private val root: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 18 * density)
        }
        clipToOutline = true
        elevation = 12 * density
    }

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val w = (minOf(screenWidth, screenHeight) * 0.6f).roundToInt()
        width = w
        height = w * 9 / 16
        x = screenWidth - w - (12 * density).roundToInt()
        y = (screenHeight * 0.62f).roundToInt()
    }

    private var shown = false
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPause.setImageResource(if (isPlaying) R.drawable.popup_pause else R.drawable.popup_play)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = fitAspect()
    }

    private val hideControls = Runnable { setControls(false) }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setControls(controls.visibility != View.VISIBLE)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            expand()
            return true
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val minWidth = (160 * density).roundToInt()
            val width = (params.width * detector.scaleFactor).roundToInt().coerceIn(minWidth, screenWidth)
            val aspect = params.height.toFloat() / params.width
            params.width = width
            params.height = (width * aspect).roundToInt()
            update()
            return true
        }
    })

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    init {
        root.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            if (scale.isInProgress || event.pointerCount > 1) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).roundToInt().coerceIn(-params.width / 2, screenWidth - params.width / 2)
                    params.y = (startY + event.rawY - downY).roundToInt().coerceIn(0, screenHeight - params.height / 2)
                    update()
                }
                MotionEvent.ACTION_UP -> {
                    // Брошено к нижнему краю — закрыть, как в NewPipe.
                    if (params.y + params.height > screenHeight - 24 * density && event.rawY > screenHeight * 0.85f) close()
                }
            }
            true
        }
    }

    fun show() {
        if (shown) return
        runCatching { wm.addView(root, params) }.onFailure { return }
        shown = true
        player.setVideoTextureView(texture)
        player.addListener(listener)
        listener.onIsPlayingChanged(player.isPlaying)
        fitAspect()
    }

    fun hide() {
        if (!shown) return
        shown = false
        player.removeListener(listener)
        player.clearVideoTextureView(texture)
        root.removeCallbacks(hideControls)
        runCatching { wm.removeView(root) }
    }

    private fun expand() {
        hide()
        onExpand()
    }

    private fun close() {
        hide()
        onClose()
    }

    private fun fitAspect() {
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0) return
        val aspect = size.height.toFloat() / size.width * size.pixelWidthHeightRatio.let { if (it > 0) 1f / it else 1f }
        params.height = (params.width * aspect).roundToInt().coerceAtMost(screenHeight / 2)
        update()
    }

    private fun update() {
        if (shown) runCatching { wm.updateViewLayout(root, params) }
    }

    private fun setControls(visible: Boolean) {
        root.removeCallbacks(hideControls)
        if (visible) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(160).start()
            root.postDelayed(hideControls, 3000)
        } else {
            controls.animate().alpha(0f).setDuration(200).withEndAction { controls.visibility = View.GONE }.start()
        }
    }

    private fun button(icon: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        val pad = (10 * density).roundToInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(70, 0, 0, 0)) }
        layoutParams = LinearLayout.LayoutParams((44 * density).roundToInt(), (44 * density).roundToInt()).apply {
            marginStart = (8 * density).roundToInt(); marginEnd = (8 * density).roundToInt()
        }
        setOnClickListener {
            onClick()
            root.removeCallbacks(hideControls)
            root.postDelayed(hideControls, 3000)
        }
    }

    companion object {
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
