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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BypassStatus {
    /** Обход выключен — соединения напрямую (имена через DoH). */
    data object Off : BypassStatus
    data object Starting : BypassStatus
    /**
     * [check] — сторож (пробы прямо через byedpi), [appOk] — настоящий запрос приложения через OkHttp
     * (тот же путь, что у ленты и видео); null — ещё не проверяли.
     */
    data class Running(val strategy: Strategy, val port: Int, val check: WatchdogStatus? = null, val appOk: Boolean? = null, val appError: String? = null) : BypassStatus
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

    /** Обход включён в настройках; меняется только под [lock] — запуск не обгоняет выключение. */
    @Volatile private var enabled = false
    private var liveness: Job? = null

    /** Сбои в живом трафике (оборванные соединения к YouTube, застрявшее видео) — будят сторожа сразу. */
    private val trouble = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Сообщить о сбое: сторож проверит стратегию сейчас, а не через 10 минут (не чаще раза в 20 с). */
    fun reportTrouble() {
        when (val s = _status.value) {
            is BypassStatus.Running -> {
                // Процесс умер — не ждать сторожа: поднять ту же стратегию сейчас (один раз на порт — под замком).
                if (!proxy.isAlive) processDied(s.strategy, s.port) else trouble.tryEmit(Unit)
            }
            // Запуск не удался, а запросы идут — повторить, не дожидаясь паузы.
            is BypassStatus.Failed -> retryNow()
            else -> Unit
        }
    }

    /** Перезапуски упавшего byedpi: время каждого — чтобы заметить, что стратегия падает раз за разом. */
    private val restarts = ArrayDeque<Long>()

    /**
     * byedpi с [strategy] на [port] умер. Перезапуск той же стратегии — только если этот порт всё ещё текущий
     * (проверяется под замком в [startWith]: десятки сбоев запросов не перезапускают его десятки раз и не
     * откатывают уже выбранную другую). Падает больше [MAX_RESTARTS] раз за [RESTART_WINDOW_MS] — стратегия
     * считается сломанной в этой сети и ищется замена.
     */
    private fun processDied(strategy: Strategy, port: Int) {
        liveness?.cancel()
        scope.launch {
            val now = System.currentTimeMillis()
            val crashing = synchronized(restarts) {
                while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW_MS) restarts.removeFirst()
                restarts.addLast(now)
                restarts.size > MAX_RESTARTS
            }
            if (!crashing) {
                startWith(strategy, expectPort = port)
                return@launch
            }
            synchronized(restarts) { restarts.clear() }
            network.current.value?.key?.let { key ->
                results.update(key) { it.copy(scores = it.scores + (strategy.id to com.tube.byedpi.StrategyScore(strategy, emptyList(), "crashed")), lastGood = it.lastGood - strategy.id) }
            }
            val next = replacementFor(strategy.id)
            if (next != null) {
                startWith(next, expectPort = port)
            } else {
                startWith(strategy, expectPort = port)
                autoPick.start(background = true) { first -> if (currentId() != first.strategy.id) choose(first.strategy.id, auto = true) }
            }
        }
    }

    /** Рабочая (по свежим итогам) другая стратегия этой сети. */
    private suspend fun replacementFor(id: String): Strategy? {
        val saved = results.of(network.current.value?.key) ?: return null
        return saved.workingSince(System.currentTimeMillis(), 3 * 24 * 60 * 60_000L)
            .firstOrNull { it.strategy.id != id }?.let { settings.resolveStrategy(it.strategy.id) ?: it.strategy }
    }

    private var retryJob: Job? = null
    private var failedStarts = 0

    /** Запуск не удался: повтор через 5 с, 30 с, 2 мин…; после трёх неудач подряд — другая стратегия (подбор). */
    private fun scheduleRetry(strategy: Strategy) {
        failedStarts++
        retryJob?.cancel()
        retryJob = scope.launch {
            kotlinx.coroutines.delay(RetryDelays[minOf(failedStarts - 1, RetryDelays.lastIndex)])
            retry(strategy)
        }
    }

    private fun retryNow() {
        val job = retryJob ?: return
        if (!job.isActive) return
        job.cancel()
        scope.launch { (lastAttempt ?: strategyForNetwork()).let { retry(it) } }
    }

    private var lastAttempt: Strategy? = null

    private suspend fun retry(strategy: Strategy) {
        if (failedStarts >= 3) {
            replacementFor(strategy.id)?.let { return startWith(it) }
        }
        startWith(strategy)
    }

    /** Следить за режимом из настроек: «Авто» — поднять byedpi, «Выкл» — остановить. */
    fun start() {
        scope.launch {
            settings.settings.map { it.bypass }.distinctUntilChanged().collect { mode ->
                when (mode) {
                    BypassMode.Auto -> {
                        lock.withLock { enabled = true }
                        startWith(strategyForNetwork())
                    }
                    BypassMode.Off -> lock.withLock {
                        enabled = false
                        watchdog?.cancel()
                        liveness?.cancel()
                        appCheck?.cancel()
                        retryJob?.cancel()
                        // Подбор, запущенный сторожем, больше не нужен; начатый кнопкой — доработает и только сохранит итог.
                        if (autoPick.isBackground) autoPick.cancel()
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

    /**
     * Поднять byedpi со стратегией [strategy]. [drain] — прошлый процесс дорабатывает начатые загрузки 30 с
     * (только когда он исправен: смена на более быструю); после сбоя он гасится сразу, чтобы соединения
     * через сломанную стратегию не держали видео.
     */
    private suspend fun startWith(strategy: Strategy, drain: Boolean = false, expectPort: Int? = null): Unit = lock.withLock {
        if (!enabled) return@withLock
        // Перезапуск упавшего процесса — только если тот порт всё ещё текущий (иначе уже выбрана другая стратегия).
        if (expectPort != null && (_status.value as? BypassStatus.Running)?.port != expectPort) return@withLock
        watchdog?.cancel()
        liveness?.cancel()
        retryJob?.cancel()
        lastAttempt = strategy
        _status.value = BypassStatus.Starting
        val endpoint = runCatching { proxy.start(strategy, settings.blockQuic(), settings.fakeSni(), drain) }.getOrElse { e ->
            _status.value = BypassStatus.Failed(e.message ?: e.javaClass.simpleName)
            scheduleRetry(strategy)
            return@withLock
        }
        failedStarts = 0
        _status.value = BypassStatus.Running(strategy, endpoint.port)
        appCheck?.cancel()
        appCheck = scope.launch { checkAppPath(endpoint.port) }
        liveness = scope.launch { watchProcess(strategy, endpoint.port) }
        watchdog = scope.launch {
            StrategyWatchdog(
                context, network, results, autoPick, settings,
                proxyPort = endpoint.port,
                currentId = strategy.id,
                onStatus = { check ->
                    (_status.value as? BypassStatus.Running)?.takeIf { it.port == endpoint.port }?.let { _status.value = it.copy(check = check) }
                },
                // Плеер открывают ради видео — проверяем сразу, а не через 15 с, как в VPN.
                firstCheckMs = 3_000,
                trouble = trouble,
            ) { next, _ ->
                // Новый прокси поднимается в отдельной корутине: эта (сторож) будет отменена при перезапуске.
                scope.launch { choose(next.id, auto = true) }
            }.run()
        }
    }

    /** Порт работающего byedpi для ProxySelector; null — соединения напрямую. */
    val port: Int? get() = proxy.port

    /** Клиент приложения для проверки пути «OkHttp → byedpi → YouTube». */
    var httpClient: (() -> okhttp3.OkHttpClient)? = null
    private var appCheck: Job? = null

    /**
     * Проверка тем же путём, что у ленты и плеера (OkHttp → SOCKS byedpi → youtube.com): сторож проверяет byedpi
     * напрямую и не увидит, если приложение до прокси не достучалось. Повторяется раз в 10 минут.
     */
    private suspend fun checkAppPath(port: Int) {
        val client = httpClient ?: return
        while (true) {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    client().newCall(okhttp3.Request.Builder().url("https://www.youtube.com/generate_204").build()).execute().use { it.code in 200..399 }
                }
            }
            val running = _status.value as? BypassStatus.Running ?: return
            if (running.port != port) return
            _status.value = running.copy(appOk = result.getOrDefault(false), appError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            if (!result.getOrDefault(false)) reportTrouble()
            kotlinx.coroutines.delay(if (result.getOrDefault(false)) 10 * 60_000L else 30_000L)
        }
    }

    /**
     * byedpi мог упасть или быть убит системой (нехватка памяти): тогда запросы пошли бы мимо обхода, а сторож
     * через 10 минут счёл бы рабочую стратегию сломанной. Проверяем раз в несколько секунд и поднимаем ту же стратегию.
     */
    private suspend fun watchProcess(strategy: Strategy, port: Int) {
        while (true) {
            kotlinx.coroutines.delay(LIVENESS_MS)
            val running = _status.value as? BypassStatus.Running ?: return
            if (running.port != port) return
            if (!proxy.isAlive) {
                processDied(strategy, port)
                return
            }
        }
    }

    /** Подождать, пока byedpi запускается (для запросов, начатых в это время); не дольше [maxMs]. */
    fun awaitReady(maxMs: Long) {
        if (_status.value != BypassStatus.Starting) return
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(maxMs) { status.first { it != BypassStatus.Starting } }
        }
    }

    /**
     * Выбрать стратегию (вручную или автоматически): глобально и для текущей сети, и перезапустить прокси.
     * Автоматический выбор при выключенном обходе ничего не меняет.
     */
    suspend fun choose(id: String, auto: Boolean = false, drain: Boolean = false) {
        if (auto && !enabled) return
        settings.setStrategy(id)
        network.current.value?.key?.let { key -> results.update(key) { it.copy(chosen = id) } }
        settings.resolveStrategy(id)?.let { startWith(it, drain) }
    }

    /**
     * Подбор по кнопке: первая стратегия, прошедшая полную проверку, включается сразу; когда перебор закончится,
     * лучшая заменяет её, только если заметно быстрее (смена мягкая — видео не обрывается).
     */
    fun pick() {
        if (autoPick.running) return
        scope.launch {
            val key = network.current.value?.key
            val ranking = autoPick.runFull(onWorking = { first ->
                if (network.current.value?.key == key && currentId() != first.strategy.id) choose(first.strategy.id, auto = true)
            })
            if (network.current.value?.key != key) return@launch
            val best = ranking.firstOrNull { it.works } ?: return@launch
            val current = ranking.firstOrNull { it.strategy.id == currentId() }
            if (best.strategy.id != currentId() && (current == null || !current.works || best.avgMillis * 10 < current.avgMillis * 7)) {
                // Текущая исправна, новая просто быстрее — переход мягкий, начатые загрузки дорабатывают.
                choose(best.strategy.id, auto = true, drain = current?.works == true)
            }
        }
    }

    private fun currentId(): String? = (_status.value as? BypassStatus.Running)?.strategy?.id

    fun cancelPick() = autoPick.cancel()

    /** Для скриншотов. */
    internal fun preview(status: BypassStatus) {
        _status.value = status
    }

    /** Перезапустить byedpi с текущей стратегией (например, процесс упал). */
    fun restart() {
        scope.launch { startWith(strategyForNetwork()) }
    }

    private companion object {
        const val LIVENESS_MS = 3_000L
        const val MAX_RESTARTS = 3
        const val RESTART_WINDOW_MS = 60_000L
        val RetryDelays = longArrayOf(5_000L, 30_000L, 120_000L)
    }
}
