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
            .setCallback(LimitedControllers())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_player) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Сервис открыт другим приложениям (так требует Media3 для уведомления, гарнитур и Android Auto). Чужие пульты
     * управляют воспроизведением, но не подменяют видео: свои адреса в плеер (а значит, запросы через наш обход)
     * они не добавят. Системные и доверенные — как обычно.
     */
    private inner class LimitedControllers : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.isTrusted || controller.packageName == packageName) {
                return super.onConnect(session, controller)
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM)
                .remove(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<androidx.media3.common.MediaItem>,
        ): com.google.common.util.concurrent.ListenableFuture<MutableList<androidx.media3.common.MediaItem>> =
            com.google.common.util.concurrent.Futures.immediateFailedFuture(UnsupportedOperationException())
    }

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
