package com.tube.strategy

import android.content.Context
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Почему сторож сменил стратегию. */
enum class SwitchReason { Failed, NetworkChanged }

/** Итог последней проверки сторожа — для экрана. */
data class WatchdogStatus(val checkedAt: Long, val ok: Boolean)

/**
 * Сторож работающего обхода:
 * - проверяет текущую стратегию через работающий прокси раз в [checkIntervalMs] — и сразу, когда приложение
 *   сообщает о сбоях в живом трафике ([trouble]: оборванные соединения, застрявшее видео);
 * - два провала подряд при живом интернете → замена: заранее проверенная запасная, иначе рабочие в этой сети
 *   (по 3 параллельно), иначе подбор в фоне, который включает первую сработавшую стратегию сразу;
 * - пока всё хорошо, держит наготове запасную стратегию (проверяет её раз в [SPARE_TTL_MS]);
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
    /** Сигналы о сбоях в живом трафике приложения; null — только по расписанию (VPN). */
    private val trouble: Flow<Unit>? = null,
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
    private val onSwitch: suspend (Strategy, SwitchReason) -> Unit,
) {
    /** Запасная стратегия: что, когда проверена и в какой сети. */
    private data class Spare(val strategy: Strategy, val at: Long, val key: String)

    @Volatile private var spare: Spare? = null
    @Volatile private var spareJob: Job? = null
    private var lastCheckAt = 0L
    private var pickBackoffMs = PICK_BACKOFF_MS

    /** Сторож работает; после остановки (выключили обход, перезапуск) поздний итог подбора ничего не переключает. */
    @Volatile private var alive = false

    suspend fun run() {
        alive = true
        try {
            loop()
        } finally {
            alive = false
        }
    }

    private suspend fun loop() = coroutineScope {
        val networkChanged = Channel<Unit>(Channel.CONFLATED)
        launch { network.current.drop(1).collect { networkChanged.trySend(Unit) } }
        val troubled = Channel<Unit>(Channel.CONFLATED)
        trouble?.let { flow -> launch { flow.collect { troubled.trySend(Unit) } } }
        var lastKey = network.current.value?.key
        var failures = 0
        // Срок следующей проверки — абсолютный: сигналы о сбоях его не отодвигают.
        var deadline = System.currentTimeMillis() + firstCheckMs
        // До этого момента сигналы о сбоях не будят: нет интернета или замену только что искали.
        var quietUntil = 0L
        while (isActive) {
            val woke = select {
                networkChanged.onReceive { Wake.Network }
                troubled.onReceive { Wake.Trouble }
                onTimeout((deadline - System.currentTimeMillis()).coerceAtLeast(0)) { Wake.Timer }
            }
            val now = System.currentTimeMillis()
            // Сбои в трафике идут пачками (десятки запросов разом) — не чаще раза в TROUBLE_GAP_MS.
            if (woke == Wake.Trouble && (now - lastCheckAt < TROUBLE_GAP_MS || now < quietUntil)) continue
            if (!settings.watchdog()) {
                deadline = now + checkIntervalMs
                continue
            }

            val key = network.current.value?.key
            if (key == null) {
                deadline = now + OFFLINE_RECHECK_MS
                continue
            }
            if (key != lastKey) {
                lastKey = key
                failures = 0
                quietUntil = 0
                spareJob?.cancel()
                spare = null
                val chosen = results.of(key)?.chosen
                val strategy = chosen?.let { settings.resolveStrategy(it) }
                if (strategy != null && strategy.id != currentId) {
                    onSwitch(strategy, SwitchReason.NetworkChanged)
                    coroutineContext.cancelChildren()
                    return@coroutineScope
                }
            }

            val checks = checkCurrent(results.of(key)?.videoHost)
            lastCheckAt = System.currentTimeMillis()
            deadline = lastCheckAt + checkIntervalMs
            val ok = checks.all { it.ok }
            onStatus(WatchdogStatus(lastCheckAt, ok))
            if (ok) {
                failures = 0
                quietUntil = 0
                pickBackoffMs = PICK_BACKOFF_MS
                results.update(key) {
                    // Рабочая стратегия запоминается для сети, если там ещё ничего не выбрано.
                    it.copy(chosen = it.chosen ?: currentId, lastGood = it.lastGood + (currentId to lastCheckAt))
                }
                refreshSpare(this, key)
                continue
            }
            if (!internetAvailable()) {
                // Интернета нет вовсе — менять нечего; появится — сработает смена сети или следующая проверка.
                deadline = System.currentTimeMillis() + OFFLINE_RECHECK_MS
                quietUntil = deadline
                continue
            }
            failures++
            if (failures < 2) {
                deadline = System.currentTimeMillis() + RECHECK_MS
                continue
            }
            // Стратегия не работает — запоминаем это, чтобы она не стояла в «рабочих» первой.
            settings.resolveStrategy(currentId)?.let { current ->
                results.update(key) { it.copy(scores = it.scores + (currentId to StrategyScore(current, checks)), lastGood = it.lastGood - currentId) }
            }
            spareJob?.cancel()
            val replacement = findReplacement(key)
            if (replacement != null) {
                onSwitch(replacement, SwitchReason.Failed)
                coroutineContext.cancelChildren()
                return@coroutineScope
            }
            // Проверенных замен нет — подбор в фоне; первая сработавшая стратегия включится сразу.
            startPick(key)
            deadline = System.currentTimeMillis() + pickBackoffMs
            quietUntil = deadline
        }
    }

    private enum class Wake { Network, Trouble, Timer }

    private suspend fun checkCurrent(videoHost: String?): List<ProbeResult> =
        StrategyTester(context).checkThrough(proxyPort, autoPick.plan.full(videoHost))

    /** Есть ли интернет вообще: без него менять стратегию бессмысленно. */
    private suspend fun internetAvailable(): Boolean = coroutineScope {
        ControlProbes.map { (host, path) ->
            async(Dispatchers.IO) { runCatching { StrategyTester.httpsGet(null, host, path, 1, 4000).isNotEmpty() }.getOrDefault(false) }
        }.awaitAll().any { it }
    }

    /**
     * Замена без подбора: запасная этой сети, проверенная недавно (сразу), иначе рабочие в этой сети по свежим итогам
     * и подтверждённые в живом трафике — по [PARALLEL] за раз, первая сработавшая по порядку.
     */
    private suspend fun findReplacement(key: String): Strategy? {
        val now = System.currentTimeMillis()
        spare?.let { s -> if (s.key == key && s.strategy.id != currentId && now - s.at < SPARE_TRUST_MS) return s.strategy }
        val saved = results.of(key)
        val known = (saved?.workingSince(now, WORKING_MAX_AGE_MS)?.map { it.strategy }.orEmpty() +
            saved?.lastGood?.entries?.sortedByDescending { it.value }?.mapNotNull { settings.resolveStrategy(it.key) }.orEmpty())
            .distinctBy { it.id }
            .filter { it.id != currentId }
            .take(MAX_CANDIDATES)
        if (known.isEmpty()) return null
        val gate = Semaphore(PARALLEL)
        return coroutineScope {
            val tests = known.map { candidate -> async { gate.withPermit { candidate to autoPick.testOne(candidate) } } }
            var found: Strategy? = null
            for (t in tests) {
                val (candidate, score) = t.await()
                if (score.works) {
                    found = candidate
                    break
                }
            }
            // Остальные проверки не нужны; их byedpi гасится сразу (см. StrategyTester.testOne).
            tests.forEach { it.cancel() }
            found
        }
    }

    /**
     * Подбор в фоне, если он сейчас не идёт; недавний пустой подбор не повторяется чаще, чем раз в [pickBackoffMs].
     * Итог применяется, только если сторож ещё работает и сеть та же: иначе он уже ни к чему.
     */
    private fun startPick(key: String) {
        if (autoPick.running) return
        val sinceLast = System.currentTimeMillis() - autoPick.lastFinishedAt
        if (!autoPick.lastFoundWorking && sinceLast < pickBackoffMs) return
        if (!autoPick.lastFoundWorking) pickBackoffMs = (pickBackoffMs * 2).coerceAtMost(MAX_PICK_BACKOFF_MS)
        autoPick.start(background = true) { first ->
            if (alive && network.current.value?.key == key && first.strategy.id != currentId) onSwitch(first.strategy, SwitchReason.Failed)
        }
    }

    /**
     * Запасная стратегия: лучшая другая из рабочих в этой сети (или первая по опыту других сетей), проверенная
     * полностью. Проверка — в фоне, не чаще раза в [SPARE_TTL_MS]; при сбое переключение на неё мгновенное.
     */
    private suspend fun refreshSpare(scope: CoroutineScope, key: String) {
        val now = System.currentTimeMillis()
        if (spare?.let { it.key == key && now - it.at < SPARE_TTL_MS } == true || spareJob?.isActive == true || autoPick.running) return
        val saved = results.of(key)
        val blockUdp = settings.blockQuic()
        val candidate = (saved?.workingSince(now, WORKING_MAX_AGE_MS)?.map { it.strategy }.orEmpty() +
            AutoPick.order(Strategies.candidates(blockUdp), results.all.value, key, now, currentId))
            .firstOrNull { it.id != currentId && saved?.scores?.get(it.id)?.let { s -> !s.works && now - s.at < SPARE_TTL_MS } != true }
            ?: return
        spareJob = scope.launch {
            val score = autoPick.testOne(candidate)
            // Сеть могла смениться, пока шла проверка, — такая запасная не годится.
            spare = if (score.works && network.current.value?.key == key) Spare(candidate, System.currentTimeMillis(), key) else null
        }
    }

    companion object {
        // Первая проверка вскоре после включения: нерабочая стратегия заменяется за полминуты, а не за полторы минуты.
        const val FIRST_CHECK_MS = 15_000L
        const val CHECK_INTERVAL_MS = 10 * 60_000L
        private const val RECHECK_MS = 4_000L
        private const val OFFLINE_RECHECK_MS = 60_000L
        private const val TROUBLE_GAP_MS = 20_000L
        private const val PICK_BACKOFF_MS = 2 * 60_000L
        private const val MAX_PICK_BACKOFF_MS = 30 * 60_000L
        private const val SPARE_TTL_MS = 30 * 60_000L
        /** Запасной, проверенной не раньше этого, доверяем без повторной проверки. */
        private const val SPARE_TRUST_MS = 15 * 60_000L
        private const val WORKING_MAX_AGE_MS = 3 * 24 * 60 * 60_000L
        private const val MAX_CANDIDATES = 6
        private const val PARALLEL = 3
        private val ControlProbes = listOf("ya.ru" to "/", "www.gstatic.com" to "/generate_204")
    }
}
