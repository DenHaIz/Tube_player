package com.tube.player.net

import android.content.Context
import com.tube.byedpi.Strategy
import com.tube.player.data.BypassMode
import com.tube.player.data.SettingsRepository
import com.tube.strategy.AutoPick
import com.tube.strategy.NetworkMonitor
import com.tube.strategy.ResultsStore
import com.tube.strategy.StrategyWatchdog
import com.tube.strategy.WatchdogStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BypassStatus {
    /** Обход выключен — соединения напрямую (имена через DoH). */
    data object Off : BypassStatus
    data object Starting : BypassStatus
    data class Running(val strategy: Strategy, val port: Int, val check: WatchdogStatus? = null) : BypassStatus
    data class Failed(val message: String) : BypassStatus
}

/**
 * Обход внутри плеера — как в Tube, только без VPN: стратегия для текущей сети (запомненная или выбранная),
 * сторож проверяет её через работающий прокси, при сбое или смене сети подставляет другую, а если рабочих нет —
 * запускает подбор в фоне. Результаты подбора — по сетям, в том же формате, что у Tube.
 */
class BypassController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val proxy: BypassProxy,
    private val network: NetworkMonitor,
    private val results: ResultsStore,
    val autoPick: AutoPick,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<BypassStatus>(BypassStatus.Starting)
    val status: StateFlow<BypassStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var watchdog: Job? = null

    /** Следить за режимом из настроек: «Авто» — поднять byedpi, «Выкл» — остановить. */
    fun start() {
        scope.launch {
            settings.settings.map { it.bypass }.distinctUntilChanged().collect { mode ->
                when (mode) {
                    BypassMode.Auto -> startWith(strategyForNetwork())
                    BypassMode.Off -> lock.withLock {
                        watchdog?.cancel()
                        proxy.stop()
                        _status.value = BypassStatus.Off
                    }
                }
            }
        }
    }

    /** Запомненная для сети стратегия, иначе выбранная в настройках. */
    private suspend fun strategyForNetwork(): Strategy {
        val chosen = results.of(network.current.value?.key)?.chosen
        return chosen?.let { settings.resolveStrategy(it) } ?: settings.strategy()
    }

    private suspend fun startWith(strategy: Strategy): Unit = lock.withLock {
        watchdog?.cancel()
        _status.value = BypassStatus.Starting
        val endpoint = runCatching { proxy.start(strategy, settings.blockQuic(), settings.fakeSni()) }.getOrElse { e ->
            _status.value = BypassStatus.Failed(e.message ?: e.javaClass.simpleName)
            return@withLock
        }
        _status.value = BypassStatus.Running(strategy, endpoint.port)
        watchdog = scope.launch {
            StrategyWatchdog(
                context, network, results, autoPick, settings,
                proxyPort = endpoint.port,
                currentId = strategy.id,
                onStatus = { check ->
                    (_status.value as? BypassStatus.Running)?.takeIf { it.port == endpoint.port }?.let { _status.value = it.copy(check = check) }
                },
                // Плеер открывают ради видео — проверяем сразу, а не через 15 с, как в VPN.
                firstCheckMs = 5_000,
            ) { next, _ ->
                // Новый прокси поднимается в отдельной корутине: эта (сторож) будет отменена при перезапуске.
                scope.launch { choose(next.id) }
            }.run()
        }
    }

    /** Порт работающего byedpi для ProxySelector; null — соединения напрямую. */
    val port: Int? get() = proxy.port

    /** Выбрать стратегию (вручную или сторожем): глобально и для текущей сети, и перезапустить прокси. */
    suspend fun choose(id: String) {
        settings.setStrategy(id)
        network.current.value?.key?.let { key -> results.update(key) { it.copy(chosen = id) } }
        if (_status.value != BypassStatus.Off) settings.resolveStrategy(id)?.let { startWith(it) }
    }

    /** Полный подбор по кнопке: после него включается лучшая рабочая стратегия. */
    fun pick() {
        if (autoPick.running) return
        scope.launch {
            val best = autoPick.runFull().firstOrNull { it.works } ?: return@launch
            choose(best.strategy.id)
        }
    }

    fun cancelPick() = autoPick.cancel()

    /** Для скриншотов. */
    internal fun preview(status: BypassStatus) {
        _status.value = status
    }

    /** Перезапустить byedpi с текущей стратегией (например, процесс упал). */
    fun restart() {
        scope.launch { if (_status.value != BypassStatus.Off) startWith(strategyForNetwork()) }
    }
}
