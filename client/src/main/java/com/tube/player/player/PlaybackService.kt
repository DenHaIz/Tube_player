@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tube.player.MainActivity
import com.tube.player.PlayerApp
import com.tube.player.R

/**
 * MediaSession плеера: уведомление с управлением, экран блокировки, наушники и Bluetooth,
 * а главное — фоновое воспроизведение (сервис переднего плана, пока играет видео).
 * Сам ExoPlayer принадлежит [PlayerController] и переживает сервис.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as PlayerApp).container
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).setAction(MainActivity.ACTION_OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, container.playback.player)
            .setSessionActivity(open)
            // Обложка в уведомлении тоже идёт через byedpi: i.ytimg.com может быть недоступен напрямую.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(container.mediaDataSource).build()))
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_player) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Смахнули приложение из недавних: если видео на паузе — закрываемся, если играет в фоне — продолжаем. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            (application as PlayerApp).container.playback.close()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
