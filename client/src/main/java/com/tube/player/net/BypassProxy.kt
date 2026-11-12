package com.tube.player.net

import android.content.Context
import com.tube.byedpi.ByeDpiProcess
import com.tube.byedpi.LocalDns
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyArgs
import kotlinx.coroutines.Dispatchers
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
 * Смена стратегии поднимает новый процесс на новом порту и только потом гасит старый —
 * запросы, уже идущие через старый прокси, успевают закончиться или перейти на новый.
 */
class BypassProxy(private val context: Context) {
    private val _endpoint = MutableStateFlow<ProxyEndpoint?>(null)
    val endpoint: StateFlow<ProxyEndpoint?> = _endpoint.asStateFlow()

    /** Порт для ProxySelector — читается на каждом новом соединении. */
    val port: Int? get() = _endpoint.value?.port?.takeIf { process?.isAlive == true }

    @Volatile private var process: ByeDpiProcess? = null
    private val lock = Mutex()

    val isAlive: Boolean get() = process?.isAlive == true

    /** Последние строки вывода byedpi — для журнала. */
    fun logTail(): List<String> = process?.logTail().orEmpty()

    /**
     * Запустить byedpi со стратегией [strategy]. Ошибка аргументов или запуска — исключение,
     * прежний прокси в этом случае продолжает работать.
     */
    suspend fun start(strategy: Strategy, blockUdp: Boolean, fakeSni: String): ProxyEndpoint = lock.withLock {
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
            old?.stop()
            endpoint
        }
    }

    suspend fun stop() = lock.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            process?.stop()
            process = null
            _endpoint.value = null
        }
    }
}
