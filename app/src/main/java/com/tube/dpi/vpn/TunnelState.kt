package com.tube.dpi.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Почему туннель не запустился — для понятного сообщения на экране. */
enum class FailReason { NoConsent, NoApps, ProxyFailed, TunnelFailed, Revoked }

sealed interface TunnelStatus {
    data object Stopped : TunnelStatus
    data object Starting : TunnelStatus
    data class Running(val startedAt: Long, val strategyName: String, val byAutostart: Boolean) : TunnelStatus
    data class Failed(val reason: FailReason, val details: String?) : TunnelStatus
}

/** Последняя проверка сторожа: когда и прошли ли узлы YouTube через текущую стратегию. */
typealias WatchdogStatus = com.tube.strategy.WatchdogStatus

/** Объём трафика через туннель с момента запуска и текущая скорость (байт/с). */
data class Traffic(val rxBytes: Long = 0, val txBytes: Long = 0, val rxRate: Long = 0, val txRate: Long = 0)

/** Состояние туннеля на весь процесс: его пишет [TubeVpnService], читают экран, плитка и автозапуск. */
object TunnelState {
    private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Последние строки вывода byedpi — экран диагностики. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _watchdog = MutableStateFlow<WatchdogStatus?>(null)
    val watchdog: StateFlow<WatchdogStatus?> = _watchdog.asStateFlow()

    val isActive: Boolean get() = status.value.let { it is TunnelStatus.Running || it is TunnelStatus.Starting }

    internal fun set(status: TunnelStatus) {
        _status.value = status
        if (status !is TunnelStatus.Running) {
            _traffic.value = Traffic()
            _watchdog.value = null
        }
    }

    internal fun setWatchdog(status: WatchdogStatus) {
        _watchdog.value = status
    }

    internal fun setTraffic(traffic: Traffic) {
        _traffic.value = traffic
    }

    internal fun setLog(lines: List<String>) {
        _log.value = lines
    }
}
