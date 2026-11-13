package com.tube.strategy

import android.content.Context
import com.tube.byedpi.LocalDns
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import com.tube.byedpi.TestEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Ход подбора (общий для экрана и сторожа). */
data class TestProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Подбор запустил сторож, а не пользователь. */
    val background: Boolean = false,
)

/**
 * Подбор стратегии для текущей сети. Результаты сразу пишутся в [ResultsStore] по ключу сети,
 * поэтому экран показывает их и после перезапуска, а сторож берёт из них замену.
 *
 * Быстрее, чем перебор по списку:
 * - кандидаты упорядочены по опыту ([order]): что работало в этой сети и в похожих, — первым;
 * - первая стратегия, прошедшая второй, полный круг, сразу отдаётся в `onWorking` — обход включается
 *   через секунды, а остальное доперебирается в фоне;
 * - проверка без обхода (baseline) идёт параллельно с перебором, видеоузел ищется только если он ещё не известен.
 */
class AutoPick(
    private val context: Context,
    private val settings: StrategySettings,
    private val results: ResultsStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    val plan: ProbePlan = ProbePlan.Vpn,
) {
    private val _progress = MutableStateFlow(TestProgress())
    val progress: StateFlow<TestProgress> = _progress.asStateFlow()

    /** Строки журнала последнего подбора: итог по каждому узлу. */
    val log = MutableStateFlow<List<String>>(emptyList())

    /** Идущий подбор — запущенный кнопкой или сторожем; «Стоп» отменяет любой. */
    @Volatile private var active: Job? = null

    /** Занят с момента вызова, а не с первой проверки: двойное нажатие не запускает два подбора. */
    private val busy = AtomicBoolean(false)

    /** Когда закончился последний подбор и нашёл ли он рабочие — сторож не гоняет подбор по кругу впустую. */
    @Volatile var lastFinishedAt: Long = 0
        private set
    @Volatile var lastFoundWorking: Boolean = true
        private set

    /** Пауза перед следующим фоновым подбором, если прошлый ничего не нашёл: 2 → 4 → … 30 мин (общая для всех сторожей). */
    @Volatile var backoffMs: Long = MIN_BACKOFF_MS
        private set

    /** Идущий подбор запущен сторожем (фоновый): «Выкл» его отменяет. Ставится до запуска. */
    @Volatile var isBackground: Boolean = false
        private set

    /** Сеть, для которой идёт подбор. */
    @Volatile var pickKey: String? = null
        private set

    /**
     * Кому отдать первую сработавшую стратегию фонового подбора — сторожу, который работает сейчас: если сторож
     * перезапустился, пока идёт подбор, новый перехватывает итог (иначе он терялся до следующих двух провалов).
     */
    @Volatile var receiver: (suspend (StrategyScore) -> Unit)? = null

    val running: Boolean get() = busy.get()

    /** Подбор в фоне (в своём scope — переживает перезапуск сторожа). null — подбор уже идёт. */
    fun start(background: Boolean = false, onWorking: (suspend (StrategyScore) -> Unit)? = null): Job? {
        if (running) return null
        isBackground = background
        pickKey = network.current.value?.key
        return scope.launch { runFull(background, onWorking) }
    }

    fun cancel() {
        active?.cancel()
    }

    /**
     * Полный перебор: встроенные, набор ByeByeDPI (без повторов) и своя стратегия. Возвращает рейтинг;
     * пустой, если подбор уже идёт (одновременно два подбора гоняли бы по 12 процессов и путали результаты).
     * [onWorking] — вызывается один раз, как только найдена стратегия, прошедшая полную проверку.
     */
    suspend fun runFull(background: Boolean = false, onWorking: (suspend (StrategyScore) -> Unit)? = null): List<StrategyScore> {
        if (!busy.compareAndSet(false, true)) return emptyList()
        isBackground = background
        pickKey = network.current.value?.key
        active = currentCoroutineContext()[Job]
        var ranking = emptyList<StrategyScore>()
        try {
            ranking = runExclusive(background, onWorking)
            return ranking
        } finally {
            lastFinishedAt = System.currentTimeMillis()
            lastFoundWorking = ranking.any { it.works }
            backoffMs = if (lastFoundWorking) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            isBackground = false
            active = null
            busy.set(false)
            _progress.value = _progress.value.copy(running = false)
        }
    }

    private suspend fun runExclusive(background: Boolean, onWorking: (suspend (StrategyScore) -> Unit)?): List<StrategyScore> {
        network.refresh()
        val key = network.current.value?.key ?: return emptyList()
        val blockUdp = settings.blockQuic()
        val custom = StrategySettings.customStrategy(settings.customArgs())
        val candidates = order(Strategies.candidates(blockUdp) + listOfNotNull(custom), results.all.value, key, System.currentTimeMillis(), settings.strategy().id)
        val tester = tester()
        _progress.value = TestProgress(running = true, total = candidates.size, background = background)
        try {
            val systemDns = withContext(Dispatchers.IO) { tester.systemDnsResolves() }
            val saved = results.of(key)
            LocalDns.shared().prefetch(LocalDns.YouTubeHosts + LocalDns.videoCluster(saved?.videoHost))
            // Видеоузел сети почти не меняется: известен — не ищем (поиск на сломанной сети — до минуты).
            val videoHost = saved?.videoHost ?: tester.discoverVideoHost(candidates, blockUdp)
            val quick = plan.quick(videoHost)
            // Новый подбор заменяет старые результаты этой сети, выбор стратегии сохраняется.
            results.update(key) { it.copy(testedAt = System.currentTimeMillis(), systemDns = systemDns, scores = emptyMap(), videoHost = videoHost) }
            log.value = listOf("network: $key", "system DNS resolves www.youtube.com: $systemDns", "video host: ${videoHost ?: "not found"}")
            var ranking = emptyList<StrategyScore>()
            var full = quick
            val applied = AtomicBoolean(false)
            coroutineScope {
                // Полные проверки (ссылка на видеопоток — запрос к YouTube) готовятся параллельно с первым кругом.
                val fullJob = async { plan.full(videoHost) }
                // Без обхода — параллельно с перебором, а не перед ним (+7 с на сломанной сети).
                launch {
                    val baseline = tester.baseline(quick)
                    results.update(key) { it.copy(baseline = baseline) }
                    log.update { it + describe(baseline) }
                }
                // Ранний выбор: прошедшие первый круг по очереди проверяются полностью, первая — сразу в дело.
                val early = Channel<StrategyScore>(Channel.UNLIMITED)
                val earlyJob = onWorking?.let { callback ->
                    launch {
                        for (first in early) {
                            val again = tester.testOne(first.strategy, fullJob.await(), blockUdp)
                            results.update(key) { it.copy(scores = it.scores + (again.strategy.id to again)) }
                            log.update { it + ("early " + describe(again)) }
                            if (again.works && applied.compareAndSet(false, true)) {
                                deliver(callback, again)
                                break
                            }
                        }
                        // Остальные ранние кандидаты не нужны: дальше их проверит второй круг.
                        early.cancel()
                    }
                }
                tester.run(candidates, quick, blockUdp).collect { event ->
                    when (event) {
                        is TestEvent.Progress -> {
                            results.update(key) { it.copy(scores = it.scores + (event.score.strategy.id to event.score)) }
                            log.update { it + describe(event.score) }
                            _progress.update { it.copy(done = event.done) }
                            if (event.score.works && earlyJob?.isActive == true) early.trySend(event.score)
                        }
                        is TestEvent.Finished -> ranking = event.ranking
                    }
                }
                early.close()
                // Перебор закончился, а ранний выбор ещё ничего не нашёл — дальше всё проверит второй круг.
                if (!applied.get()) earlyJob?.cancel()
                full = fullJob.await()
            }
            // Ранний выбор не успел — первая стратегия, прошедшая второй круг, включается сразу.
            return verify(key, tester, ranking, full, blockUdp) { score ->
                if (onWorking != null && applied.compareAndSet(false, true)) deliver(onWorking, score)
            }
        } finally {
            _progress.value = _progress.value.copy(running = false)
        }
    }

    /**
     * Второй круг для прошедших — полными проверками, по [VERIFY_PARALLELISM] за раз, без нагрузки соседних стратегий.
     * Стратегия, прошедшая один раз из двух, в YouTube работает через раз — она уходит из «рабочих».
     */
    private suspend fun verify(
        key: String,
        tester: StrategyTester,
        ranking: List<StrategyScore>,
        probes: List<com.tube.byedpi.Probe>,
        blockUdp: Boolean,
        onWorks: suspend (StrategyScore) -> Unit,
    ): List<StrategyScore> {
        val passed = ranking.filter { it.works }.take(VERIFY_LIMIT)
        if (passed.isEmpty()) return ranking
        _progress.update { it.copy(total = it.total + passed.size) }
        log.update { it + "verify: ${passed.size}" }
        val gate = Semaphore(VERIFY_PARALLELISM)
        val verified = coroutineScope {
            passed.map { first ->
                async {
                    val again = gate.withPermit { tester.testOne(first.strategy, probes, blockUdp) }
                    // Итог и скорость — по второму, полному кругу.
                    val merged = again
                    results.update(key) { it.copy(scores = it.scores + (merged.strategy.id to merged)) }
                    log.update { it + ("verify " + describe(merged)) }
                    if (merged.works) onWorks(merged)
                    _progress.update { it.copy(done = it.done + 1) }
                    merged
                }
            }.awaitAll()
        }.associateBy { it.strategy.id }
        return StrategyTester.rank(ranking.map { verified[it.strategy.id] ?: it })
    }

    /** Проверка одной стратегии полными проверками на свободном порту; результат тоже сохраняется. */
    suspend fun testOne(strategy: Strategy): StrategyScore {
        val key = network.current.value?.key
        val score = tester().testOne(strategy, plan.full(results.of(key)?.videoHost), settings.blockQuic())
        key?.let { k -> results.update(k) { it.copy(scores = it.scores + (strategy.id to score)) } }
        return score
    }

    private suspend fun tester() = StrategyTester(context, fakeSni = settings.fakeSni())

    /** Итог — тому, кто ждёт; его ошибка (например, служба уже остановлена) не роняет подбор и приложение. */
    private suspend fun deliver(callback: suspend (StrategyScore) -> Unit, score: StrategyScore) {
        try {
            callback(score)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.update { it + "switch failed: ${e.javaClass.simpleName}" }
        }
    }

    companion object {
        private const val VERIFY_LIMIT = 12
        private const val MIN_BACKOFF_MS = 2 * 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val VERIFY_PARALLELISM = 3
        private const val DAY = 24 * 60 * 60_000L

        /**
         * Порядок проверки: сначала то, что вероятнее сработает. Очки:
         * - в этой сети: выбранная (+3000), подтверждённая сторожем недавно (+2000 → 0 за 2 недели),
         *   рабочая по свежему подбору (+1000 → 0 за неделю), провалившаяся сегодня (−600);
         * - в сетях того же вида (тот же оператор — `cell:…`, другие Wi-Fi): +120 за каждую, где работала;
         * - в любых других сетях: +40;
         * - фейк www.google.com (ТСПУ узнаёт его с августа 2026): −150.
         * При равных очках — исходный порядок списка (встроенные свежие строки — первыми).
         */
        fun order(candidates: List<Strategy>, all: Map<String, NetworkResults>, key: String?, now: Long, currentId: String? = null): List<Strategy> {
            val here = key?.let { all[it] }
            val kind = key?.substringBefore(':')
            fun fade(at: Long, span: Long) = (1.0 - (now - at).toDouble() / span).coerceIn(0.0, 1.0)
            val points = candidates.associate { s ->
                var p = 0.0
                if (here != null) {
                    if (here.chosen == s.id) p += 3000
                    here.lastGood[s.id]?.let { p += 2000 * fade(it, 14 * DAY) }
                    here.scores[s.id]?.let { sc ->
                        if (sc.works) p += 1000 * fade(sc.at, 7 * DAY)
                        else if (sc.passed == 0 && now - sc.at < DAY) p -= 600
                    }
                }
                all.forEach { (k, n) ->
                    if (k == key) return@forEach
                    val worked = n.scores[s.id]?.works == true || (n.lastGood[s.id]?.let { now - it < 30 * DAY } == true)
                    if (worked) p += if (k.substringBefore(':') == kind) 120 else 40
                }
                if (s.id == currentId && here?.scores?.get(s.id)?.works != false) p += 10
                if (Strategies.usesGoogleFake(s)) p -= 150
                s.id to p
            }
            return candidates.withIndex().sortedWith(compareByDescending<IndexedValue<Strategy>> { points.getValue(it.value.id) }.thenBy { it.index }).map { it.value }
        }

        /** Строка журнала: стратегия и итог по каждому узлу — видно, что именно режет провайдер. */
        fun describe(score: StrategyScore): String = buildString {
            append(score.strategy.name).append(": ")
            if (score.startError != null) {
                append("start error: ").append(score.startError)
                return@buildString
            }
            score.results.joinTo(this, "; ") { r ->
                val kind = if (r.probe.kind == com.tube.byedpi.ProbeKind.ChromeHello) " [chrome]" else if ("videoplayback" in r.probe.path) " [stream]" else ""
                r.probe.host + kind + " " + if (r.ok) "ok ${r.millis}ms" else "FAIL ${r.bytes}B ${r.error.orEmpty()}"
            }
        }
    }
}
