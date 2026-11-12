package com.tube.strategy

import android.content.Context
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Почему сторож сменил стратегию. */
enum class SwitchReason { Failed, NetworkChanged }

/** Итог последней проверки сторожа — для экрана. */
data class WatchdogStatus(val checkedAt: Long, val ok: Boolean)

/**
 * Сторож работающего обхода:
 * - раз в [CHECK_INTERVAL_MS] проверяет узлы YouTube через работающий прокси (VPN не останавливается);
 * - два провала подряд при живом интернете → ищет замену среди рабочих в этой сети, а если таких нет —
 *   запускает подбор в фоне (не чаще раза в час на сеть);
 * - при смене сети сразу подставляет стратегию, запомненную для новой сети.
 * Смена — через [onSwitch] (VPN перезапускает туннель, плеер — прокси; этот цикл при этом завершается).
 */
class StrategyWatchdog(
    private val context: Context,
    private val network: NetworkMonitor,
    private val results: ResultsStore,
    private val autoPick: AutoPick,
    private val settings: StrategySettings,
    private val proxyPort: Int,
    private val currentId: String,
    private val onStatus: (WatchdogStatus) -> Unit = {},
    private val firstCheckMs: Long = FIRST_CHECK_MS,
    private val onSwitch: suspend (Strategy, SwitchReason) -> Unit,
) {
    suspend fun run() = coroutineScope {
        val networkChanged = Channel<Unit>(Channel.CONFLATED)
        launch { network.current.drop(1).collect { networkChanged.trySend(Unit) } }
        var lastKey = network.current.value?.key
        var failures = 0
        var interval = firstCheckMs
        while (isActive) {
            withTimeoutOrNull(interval) { networkChanged.receive() }
            interval = CHECK_INTERVAL_MS
            if (!settings.watchdog()) continue

            val key = network.current.value?.key ?: continue
            if (key != lastKey) {
                lastKey = key
                failures = 0
                val chosen = results.of(key)?.chosen
                val strategy = chosen?.let { settings.resolveStrategy(it) }
                if (strategy != null && strategy.id != currentId) {
                    onSwitch(strategy, SwitchReason.NetworkChanged)
                    return@coroutineScope
                }
            }

            val checks = checkCurrent(results.of(key)?.videoHost)
            val ok = checks.all { it.ok }
            onStatus(WatchdogStatus(System.currentTimeMillis(), ok))
            if (ok) {
                failures = 0
                // Рабочая стратегия запоминается для сети, если там ещё ничего не выбрано.
                if (results.of(key)?.chosen == null) results.update(key) { it.copy(chosen = currentId) }
                continue
            }
            if (!internetAvailable()) continue
            failures++
            if (failures < 2) {
                interval = RECHECK_MS
                continue
            }
            // Стратегия не работает в самом VPN — запоминаем это, чтобы она не стояла в «рабочих» первой.
            settings.resolveStrategy(currentId)?.let { current ->
                results.update(key) { it.copy(scores = it.scores + (currentId to StrategyScore(current, checks))) }
            }
            val replacement = findReplacement(key) ?: continue
            onSwitch(replacement, SwitchReason.Failed)
            return@coroutineScope
        }
    }

    private suspend fun checkCurrent(videoHost: String?): List<ProbeResult> =
        StrategyTester(context).checkThrough(proxyPort, StrategyTester.defaultProbes(videoHost))

    /** Есть ли интернет вообще: без него менять стратегию бессмысленно. */
    private suspend fun internetAvailable(): Boolean = coroutineScope {
        ControlProbes.map { (host, path) ->
            async(Dispatchers.IO) { runCatching { StrategyTester.httpsGet(null, host, path, 1, 5000).isNotEmpty() }.getOrDefault(false) }
        }.awaitAll().any { it }
    }

    private suspend fun findReplacement(key: String): Strategy? {
        val saved = results.of(key)
        saved?.working?.map { it.strategy }?.filter { it.id != currentId }?.take(MAX_CANDIDATES)?.forEach { candidate ->
            if (autoPick.testOne(candidate).works) return candidate
        }
        val stale = saved == null || System.currentTimeMillis() - saved.testedAt > FULL_RETEST_MS
        if (!stale || autoPick.running) return null
        return autoPick.runFull(background = true).firstOrNull { it.works && it.strategy.id != currentId }?.strategy
    }

    companion object {
        // Первая проверка вскоре после включения: нерабочая стратегия заменяется за полминуты, а не за полторы минуты.
        const val FIRST_CHECK_MS = 15_000L
        private const val CHECK_INTERVAL_MS = 10 * 60_000L
        private const val RECHECK_MS = 15_000L
        private const val FULL_RETEST_MS = 60 * 60_000L
        private const val MAX_CANDIDATES = 6
        private val ControlProbes = listOf("ya.ru" to "/", "www.gstatic.com" to "/generate_204")
    }
}
