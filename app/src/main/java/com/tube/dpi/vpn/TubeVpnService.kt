package com.tube.dpi.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import com.tube.byedpi.ByeDpiProcess
import com.tube.byedpi.LocalDns
import com.tube.byedpi.StrategyArgs
import com.tube.dpi.TubeApp
import com.tube.dpi.apps.TargetApps
import com.tube.strategy.StrategyWatchdog
import com.tube.tunnel.TProxyService
import com.tube.tunnel.TunnelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Локальный VPN только для выбранных приложений:
 * приложение → TUN → hev-socks5-tunnel → SOCKS5 127.0.0.1 → byedpi (desync) → интернет.
 * Сам Tube в туннель не входит, поэтому соединения byedpi идут напрямую, без protect().
 *
 * Все изменения состояния — под [lock]. Сервис останавливается только по последней команде ([lastStartId]):
 * быстрые «выключить → включить» (плитка, автозапуск) не убивают только что запущенный туннель.
 */
class TubeVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.tube.dpi.START"
        const val ACTION_STOP = "com.tube.dpi.STOP"
        const val ACTION_RESTART = "com.tube.dpi.RESTART"
        const val EXTRA_BY_AUTOSTART = "by_autostart"

        private const val MONITOR_INTERVAL_MS = 1000L
        private const val TAG = "TubeVpn"
    }

    /** Непойманная ошибка в фоне не роняет процесс (а с ним и туннель), а показывается как сбой. */
    private val errors = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unexpected", e)
        TunnelState.set(TunnelStatus.Failed(FailReason.TunnelFailed, e.message ?: e.javaClass.simpleName))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val lock = Mutex()
    private val lastStartId = AtomicInteger(0)

    @Volatile private var proxy: ByeDpiProcess? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var monitor: Job? = null
    private var watchdog: Job? = null

    private val container get() = (application as TubeApp).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId.set(startId)
        val byAutostart = intent?.getBooleanExtra(EXTRA_BY_AUTOSTART, false) ?: false
        when (intent?.action) {
            ACTION_STOP -> scope.launch { lock.withLock { shutdown(TunnelStatus.Stopped, startId) } }
            ACTION_RESTART -> {
                promoteToForeground("…", 0)
                // Новый туннель поднимается до закрытия старого: в промежутке трафик YouTube не уходит мимо VPN.
                scope.launch { lock.withLock { guardedStart(byAutostart, startId, replace = true) } }
            }
            // null — система перезапустила сервис (always-on VPN или после нехватки памяти).
            else -> {
                promoteToForeground("…", 0)
                scope.launch { lock.withLock { if (proxy == null) guardedStart(byAutostart, startId, replace = false) } }
            }
        }
        return START_NOT_STICKY
    }

    /** Android требует startForeground в первые секунды после startForegroundService. */
    private fun promoteToForeground(strategyName: String, apps: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_TUNNEL, Notifications.tunnel(this, strategyName, apps), type) }
            .onFailure { Log.w(TAG, "startForeground", it) }
    }

    /** Любая ошибка запуска — понятный сбой, а не падение; отмена (сервис уничтожен) — без висящего «Запуск…». */
    private suspend fun guardedStart(byAutostart: Boolean, startId: Int, replace: Boolean) {
        try {
            startTunnel(byAutostart, startId, replace)
        } catch (e: CancellationException) {
            teardown()
            if (TunnelState.isActive) TunnelState.set(TunnelStatus.Stopped)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            fail(FailReason.TunnelFailed, e.message ?: e.javaClass.simpleName, startId)
        }
    }

    private suspend fun startTunnel(byAutostart: Boolean, startId: Int, replace: Boolean) {
        if (!replace || proxy == null) TunnelState.set(TunnelStatus.Starting)
        // В знакомой сети — стратегия, которая там работала (выбрана вручную или сторожем).
        container.network.refresh()
        val networkKey = container.network.current.value?.key
        container.results.of(networkKey)?.chosen?.let { chosen ->
            if (container.resolveStrategy(chosen) != null) container.settings.setStrategy(chosen)
        }
        val settings = container.settings.tunnelSettings { TargetApps.defaultTargets(this) }
        val installed = settings.targets.filter { TargetApps.isInstalled(this, it) }
        if (installed.isEmpty()) return fail(FailReason.NoApps, null, startId)

        val argv = when (val parsed = StrategyArgs.parse(settings.strategy.args)) {
            is StrategyArgs.Result.Ok -> parsed.argv
            is StrategyArgs.Result.Error -> return fail(FailReason.ProxyFailed, parsed.message, startId)
        }
        // Адреса YouTube — заранее: byedpi резолвит имена в своём единственном потоке и не должен ждать DoH.
        val videoHost = container.results.of(networkKey)?.videoHost
        LocalDns.shared().prefetch(LocalDns.YouTubeHosts + LocalDns.videoCluster(videoHost))

        // Старый туннель (при перезапуске) живёт, пока не готов новый.
        val oldProxy = proxy
        val oldTun = tun
        stopJobs()

        // Порт каждый раз свободный: старый byedpi ещё слушает свой.
        val port = ByeDpiProcess.freePort()
        val newProxy = try {
            ByeDpiProcess.start(this, port, argv, blockUdp = settings.blockQuic, fakeSni = settings.fakeSni)
        } catch (e: Exception) {
            return fail(FailReason.ProxyFailed, e.message, startId)
        }
        proxy = newProxy
        // Если сервис уничтожат дальше, teardown остановит и новый прокси, и старый.
        oldProxy?.let { retired += it }
        oldTun?.let { retiredTuns += it }

        val builder = Builder()
            .setSession("Tube")
            .setMtu(TunnelConfig.MTU)
            .addAddress(TunnelConfig.TUN_IPV4, 32)
            .addAddress(TunnelConfig.TUN_IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TunnelConfig.MAPPED_DNS)
            .setBlocking(false)
            .setConfigureIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.tube.dpi.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        var allowed = 0
        installed.forEach { pkg ->
            try {
                builder.addAllowedApplication(pkg)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (allowed == 0) return fail(FailReason.NoApps, null, startId)

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            return fail(FailReason.TunnelFailed, e.message, startId)
        } ?: return fail(FailReason.NoConsent, null, startId)
        // Система уже переключила маршруты на новый интерфейс; старый можно закрывать.
        runCatching { TProxyService.TProxyStopService() }
        tun = fd
        closeRetired()

        val config = File(cacheDir, "tunnel.yml").apply { writeText(TunnelConfig(socksPort = port).toYaml()) }
        if (!TProxyService.TProxyStartService(config.absolutePath, fd.fd)) {
            return fail(FailReason.TunnelFailed, "hev-socks5-tunnel", startId)
        }

        promoteToForeground(settings.strategy.name, allowed)
        Notifications.cancelAlerts(this)
        TunnelState.set(TunnelStatus.Running(System.currentTimeMillis(), settings.strategy.name, byAutostart))
        monitor = scope.launch { monitorLoop(newProxy) }
        watchdog = scope.launch {
            StrategyWatchdog(
                this@TubeVpnService, container.network, container.results, container.autoPick, container.settings,
                port, settings.strategy.id, onStatus = TunnelState::setWatchdog,
            ) { next, reason ->
                container.chooseStrategy(next.id)
                Notifications.showSwitched(this@TubeVpnService, settings.strategy.name, next.name, reason)
                startService(Intent(this@TubeVpnService, TubeVpnService::class.java).setAction(ACTION_RESTART).putExtra(EXTRA_BY_AUTOSTART, byAutostart))
            }.run()
        }
    }

    /** Прокси и интерфейсы прошлого запуска: закрываются, когда новый туннель уже работает. */
    private val retired = mutableListOf<ByeDpiProcess>()
    private val retiredTuns = mutableListOf<ParcelFileDescriptor>()

    private fun closeRetired() {
        retiredTuns.forEach { runCatching { it.close() } }
        retiredTuns.clear()
        retired.forEach { it.stop() }
        retired.clear()
    }

    /** Следит, что byedpi жив, и считает трафик для экрана и автозапуска. */
    private suspend fun monitorLoop(current: ByeDpiProcess) {
        var lastRx = 0L
        var lastTx = 0L
        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MS)
            if (proxy !== current) return
            TunnelState.setLog(current.logTail())
            if (!current.isAlive) {
                lock.withLock {
                    // Пока ждали замок, туннель могли перезапустить — тогда этот прокси уже не наш.
                    if (proxy === current) fail(FailReason.ProxyFailed, current.logTail().takeLast(3).joinToString("\n"), lastStartId.get())
                }
                return
            }
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: continue
            val tx = stats[1]
            val rx = stats[3]
            TunnelState.setTraffic(Traffic(rx, tx, (rx - lastRx).coerceAtLeast(0), (tx - lastTx).coerceAtLeast(0)))
            lastRx = rx
            lastTx = tx
        }
    }

    private fun fail(reason: FailReason, details: String?, startId: Int) {
        teardown()
        TunnelState.set(TunnelStatus.Failed(reason, details))
        if (reason != FailReason.NoConsent) Notifications.showFailed(this, listOfNotNull(reason.name, details).joinToString(": "))
        else Notifications.showConsentNeeded(this)
        stopIfLatest(startId)
    }

    private fun shutdown(status: TunnelStatus, startId: Int) {
        teardown()
        TunnelState.set(status)
        stopIfLatest(startId)
    }

    private fun stopJobs() {
        monitor?.cancel()
        monitor = null
        watchdog?.cancel()
        watchdog = null
    }

    private fun teardown() {
        stopJobs()
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        proxy?.let { TunnelState.setLog(it.logTail()) }
        proxy?.stop()
        proxy = null
        closeRetired()
    }

    /** Останавливаемся, только если после этой команды не пришла новая (например, «включить» сразу после «выключить»). */
    private fun stopIfLatest(startId: Int) {
        if (startId < lastStartId.get()) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    /** Другое VPN-приложение заняло место или пользователь отключил VPN в системных настройках. */
    override fun onRevoke() {
        scope.launch { lock.withLock { shutdown(TunnelStatus.Failed(FailReason.Revoked, null), lastStartId.get()) } }
    }

    override fun onDestroy() {
        // Запуск, который ещё идёт, отменяется и сам убирает за собой; остальное — под замком в фоне,
        // чтобы не гоняться с ним и не держать главный поток (остановка byedpi занимает до пары секунд).
        scope.cancel()
        container.scope.launch(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                teardown()
                if (TunnelState.isActive) TunnelState.set(TunnelStatus.Stopped)
            }
        }
        super.onDestroy()
    }
}
