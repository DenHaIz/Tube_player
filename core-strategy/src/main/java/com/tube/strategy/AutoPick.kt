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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class AutoPick(
    private val context: Context,
    private val settings: StrategySettings,
    private val results: ResultsStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(TestProgress())
    val progress: StateFlow<TestProgress> = _progress.asStateFlow()

    /** Строки журнала последнего подбора: итог по каждому узлу. */
    val log = MutableStateFlow<List<String>>(emptyList())

    /** Идущий подбор — запущенный кнопкой или сторожем; «Стоп» отменяет любой. */
    @Volatile private var active: Job? = null

    /** Занят с момента вызова, а не с первой проверки: двойное нажатие не запускает два подбора. */
    private val busy = AtomicBoolean(false)

    val running: Boolean get() = busy.get()

    fun start(background: Boolean = false): Job? {
        if (running) return active
        return scope.launch { runFull(background) }
    }

    fun cancel() {
        active?.cancel()
    }

    /**
     * Полный перебор: все встроенные, набор ByeByeDPI и своя стратегия. Возвращает рейтинг;
     * пустой, если подбор уже идёт (одновременно два подбора гоняли бы по 12 процессов и путали результаты).
     */
    suspend fun runFull(background: Boolean = false): List<StrategyScore> {
        if (!busy.compareAndSet(false, true)) return emptyList()
        active = currentCoroutineContext()[Job]
        try {
            return runExclusive(background)
        } finally {
            active = null
            busy.set(false)
            _progress.value = _progress.value.copy(running = false)
        }
    }

    private suspend fun runExclusive(background: Boolean): List<StrategyScore> {
        network.refresh()
        val key = network.current.value?.key ?: return emptyList()
        val candidates = Strategies.all + listOfNotNull(StrategySettings.customStrategy(settings.customArgs()))
        val tester = tester()
        val blockUdp = settings.blockQuic()
        _progress.value = TestProgress(running = true, total = candidates.size, background = background)
        try {
            val systemDns = withContext(Dispatchers.IO) { tester.systemDnsResolves() }
            // Настоящий видеоузел сети: страница просмотра читается через текущую и ранее рабочие стратегии.
            val saved = results.of(key)
            val current = settings.strategy()
            val discoveryOrder = (listOf(current) + saved?.working?.map { it.strategy }.orEmpty() + Strategies.builtIn).distinctBy { it.id }
            LocalDns.shared().prefetch(LocalDns.YouTubeHosts + LocalDns.videoCluster(saved?.videoHost))
            val videoHost = tester.discoverVideoHost(discoveryOrder, blockUdp) ?: saved?.videoHost
            val probes = StrategyTester.defaultProbes(videoHost)
            val baseline = tester.baseline(probes)
            // Новый подбор заменяет старые результаты этой сети, выбор стратегии сохраняется.
            results.update(key) { it.copy(testedAt = System.currentTimeMillis(), systemDns = systemDns, baseline = baseline, scores = emptyMap(), videoHost = videoHost) }
            log.value = listOf("network: $key", "system DNS resolves www.youtube.com: $systemDns", "video host: ${videoHost ?: "not found"}", describe(baseline))
            var ranking = emptyList<StrategyScore>()
            tester.run(candidates, probes, blockUdp).collect { event ->
                when (event) {
                    is TestEvent.Progress -> {
                        results.update(key) { it.copy(scores = it.scores + (event.score.strategy.id to event.score)) }
                        log.value = log.value + describe(event.score)
                        _progress.value = _progress.value.copy(done = event.done)
                    }
                    is TestEvent.Finished -> ranking = event.ranking
                }
            }
            return verify(key, tester, ranking, probes, blockUdp)
        } finally {
            _progress.value = _progress.value.copy(running = false)
        }
    }

    /**
     * Второй круг для прошедших: по [VERIFY_PARALLELISM] за раз, без нагрузки соседних стратегий.
     * Стратегия, прошедшая один раз из двух, в YouTube работает через раз — она уходит из «рабочих».
     */
    private suspend fun verify(
        key: String,
        tester: StrategyTester,
        ranking: List<StrategyScore>,
        probes: List<com.tube.byedpi.Probe>,
        blockUdp: Boolean,
    ): List<StrategyScore> {
        val passed = ranking.filter { it.works }.take(VERIFY_LIMIT)
        if (passed.isEmpty()) return ranking
        _progress.value = _progress.value.copy(total = _progress.value.total + passed.size)
        log.value = log.value + "verify: ${passed.size}"
        val gate = Semaphore(VERIFY_PARALLELISM)
        val verified = coroutineScope {
            passed.map { first ->
                async {
                    val again = gate.withPermit { tester.testOne(first.strategy, probes, blockUdp) }
                    val merged = if (again.works) {
                        // Скорость — средняя по двум кругам.
                        StrategyScore(first.strategy, first.results.zip(again.results) { a, b -> a.copy(millis = (a.millis + b.millis) / 2) })
                    } else {
                        again
                    }
                    results.update(key) { it.copy(scores = it.scores + (merged.strategy.id to merged)) }
                    log.value = log.value + ("verify " + describe(merged))
                    _progress.value = _progress.value.copy(done = _progress.value.done + 1)
                    merged
                }
            }.awaitAll()
        }.associateBy { it.strategy.id }
        return StrategyTester.rank(ranking.map { verified[it.strategy.id] ?: it })
    }

    /** Проверка одной стратегии на свободном порту; результат тоже сохраняется. */
    suspend fun testOne(strategy: Strategy): StrategyScore {
        val videoHost = results.of(network.current.value?.key)?.videoHost
        val score = tester().testOne(strategy, StrategyTester.defaultProbes(videoHost), settings.blockQuic())
        network.current.value?.key?.let { key -> results.update(key) { it.copy(scores = it.scores + (strategy.id to score)) } }
        return score
    }

    private suspend fun tester() = StrategyTester(context, fakeSni = settings.fakeSni())

    companion object {
        private const val VERIFY_LIMIT = 16
        private const val VERIFY_PARALLELISM = 2

        /** Строка журнала: стратегия и итог по каждому узлу — видно, что именно режет провайдер. */
        fun describe(score: StrategyScore): String = buildString {
            append(score.strategy.name).append(": ")
            if (score.startError != null) {
                append("start error: ").append(score.startError)
                return@buildString
            }
            score.results.joinTo(this, "; ") { r ->
                val kind = if (r.probe.kind == com.tube.byedpi.ProbeKind.ChromeHello) " [chrome]" else ""
                r.probe.host + kind + " " + if (r.ok) "ok ${r.millis}ms" else "FAIL ${r.bytes}B ${r.error.orEmpty()}"
            }
        }
    }
}
