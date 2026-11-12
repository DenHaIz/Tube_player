package com.tube.dpi.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tube.dpi.MainActivity
import com.tube.dpi.R
import com.tube.dpi.TubeApp
import com.tube.ui.i18n.AppLanguage

object Notifications {
    private const val CHANNEL_TUNNEL = "tunnel"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_TUNNEL = 1
    private const val ID_CONSENT = 2
    private const val ID_FAILED = 3
    private const val ID_SWITCHED = 4

    private fun lang(context: Context): AppLanguage =
        (context.applicationContext as TubeApp).container.language.value ?: AppLanguage.Russian

    fun ensureChannels(context: Context) {
        val lang = lang(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TUNNEL, lang.pick("Обход включён", "Unblocking active"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, lang.pick("Требуется действие", "Action needed"), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun tunnel(context: Context, strategyName: String, apps: Int): Notification {
        ensureChannels(context)
        val lang = lang(context)
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, TubeVpnService::class.java).setAction(TubeVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TUNNEL)
            .setSmallIcon(R.drawable.ic_stat_tube)
            .setContentTitle(lang.pick("Обход включён", "Unblocking is on"))
            .setContentText("$strategyName · " + lang.plural(apps, "$apps приложение", "$apps приложения", "$apps приложений", "$apps app", "$apps apps"))
            .setContentIntent(openApp(context))
            .addAction(0, lang.pick("Выключить", "Turn off"), stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showConsentNeeded(context: Context) {
        ensureChannels(context)
        val lang = lang(context)
        notify(
            context, ID_CONSENT,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_tube)
                .setContentTitle(lang.pick("Нужно разрешение VPN", "VPN permission needed"))
                .setContentText(lang.pick("Откройте Tube и включите обход один раз — дальше он будет запускаться сам", "Open Tube and turn it on once — after that it starts by itself"))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showFailed(context: Context, message: String) {
        ensureChannels(context)
        val lang = lang(context)
        notify(
            context, ID_FAILED,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_tube)
                .setContentTitle(lang.pick("Обход не запустился", "Unblocking failed to start"))
                .setContentText(message)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Сторож сменил стратегию — короткое уведомление без звука. */
    fun showSwitched(context: Context, from: String, to: String, reason: com.tube.strategy.SwitchReason) {
        ensureChannels(context)
        val lang = lang(context)
        val title = when (reason) {
            com.tube.strategy.SwitchReason.Failed -> lang.pick("$from перестала работать", "$from stopped working")
            com.tube.strategy.SwitchReason.NetworkChanged -> lang.pick("Другая сеть", "Network changed")
        }
        notify(
            context, ID_SWITCHED,
            NotificationCompat.Builder(context, CHANNEL_TUNNEL)
                .setSmallIcon(R.drawable.ic_stat_tube)
                .setContentTitle(title)
                .setContentText(lang.pick("Включена стратегия $to", "Switched to $to"))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setSilent(true)
                .build(),
        )
    }

    fun cancelAlerts(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_CONSENT)
        nm.cancel(ID_FAILED)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }
}
