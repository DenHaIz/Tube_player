package com.tube.player.net

import android.content.Context
import com.tube.byedpi.ByeDpiProcess
import com.tube.byedpi.LocalDns
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Работающий экземпляр byedpi: порт SOCKS5 на 127.0.0.1 и стратегия. */
data class ProxyEndpoint(val port: Int, val strategy: Strategy)

/**
 * byedpi внутри плеера: SOCKS5 на 127.0.0.1:<свободный порт>, через который идёт весь HTTP приложения
 * (OkHttp, плеер, превью, WebView входа). VPN не нужен, поэтому плеер работает рядом с любым VPN.
 *
 * Смена стратегии поднимает новый процесс на новом порту; новые соединения сразу идут через него. Если старый
 * исправен (смена на более быструю), он живёт ещё [DRAIN_MS]: начатые загрузки дорабатывают, а не обрываются;
 * после сбоя старый гасится сразу — соединения через сломанную стратегию не держат видео.
 */
class BypassProxy(private val context: Context, private val scope: CoroutineScope) {
    private val _endpoint = MutableStateFlow<ProxyEndpoint?>(null)
    val endpoint: StateFlow<ProxyEndpoint?> = _endpoint.asStateFlow()

    /** Порт для ProxySelector — читается на каждом новом соединении. */
    val port: Int? get() = _endpoint.value?.port?.takeIf { process?.isAlive == true }

    @Volatile private var process: ByeDpiProcess? = null
    /** Прошлый процесс, пока дорабатывают его соединения. */
    private var retiring: ByeDpiProcess? = null
    private var retireJob: Job? = null
    private val lock = Mutex()

    val isAlive: Boolean get() = process?.isAlive == true

    /** Последние строки вывода byedpi — для журнала. */
    fun logTail(): List<String> = process?.logTail().orEmpty()

    /**
     * Запустить byedpi со стратегией [strategy]. Ошибка аргументов или запуска — исключение,
     * прежний прокси в этом случае продолжает работать.
     */
    suspend fun start(strategy: Strategy, blockUdp: Boolean, fakeSni: String, drain: Boolean = false): ProxyEndpoint = lock.withLock {
        val argv = when (val parsed = StrategyArgs.parse(strategy.args)) {
            is StrategyArgs.Result.Ok -> parsed.argv
            is StrategyArgs.Result.Error -> throw IllegalArgumentException(parsed.message)
        }
        withContext(Dispatchers.IO + NonCancellable) {
            LocalDns.shared().prefetch(LocalDns.YouTubeHosts)
            val next = ByeDpiProcess.start(context, ByeDpiProcess.freePort(), argv, blockUdp, fakeSni)
            val old = process
            process = next
            val endpoint = ProxyEndpoint(next.port, strategy)
            _endpoint.value = endpoint
            // Дважды подряд — предыдущий «уходящий» гасится сразу: одновременно живут не больше двух процессов.
            retireJob?.cancel()
            retiring?.stop()
            retiring = null
            if (old != null && !drain) old.stop()
            if (old != null && drain) {
                retiring = old
                retireJob = scope.launch(Dispatchers.IO) {
                    delay(DRAIN_MS)
                    lock.withLock {
                        if (retiring === old) {
                            old.stop()
                            retiring = null
                        }
                    }
                }
            }
            endpoint
        }
    }

    suspend fun stop() = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            retireJob?.cancel()
            retiring?.stop()
            retiring = null
            process?.stop()
            process = null
            _endpoint.value = null
        }
    }

    companion object {
        const val DRAIN_MS = 30_000L
    }
}
