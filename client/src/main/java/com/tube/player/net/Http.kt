package com.tube.player.net

import com.tube.byedpi.LocalDns
import com.tube.player.adblock.AdBlock
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Прокси для каждого нового соединения: работающий byedpi или напрямую.
 * Адрес не зашит в клиент — смена стратегии (новый порт) не требует пересоздавать OkHttp.
 * Для SOCKS OkHttp передаёт имя хоста неразрешённым: его резолвит byedpi через DoH (LocalDns).
 */
class BypassProxySelector(private val port: () -> Int?) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val p = port() ?: return listOf(Proxy.NO_PROXY)
        return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(Loopback4, p)))
    }

    companion object {
        /**
         * Именно IPv4 127.0.0.1: byedpi слушает только его, а `InetAddress.getLoopbackAddress()` на части телефонов
         * отдаёт ::1 (ip6-localhost) — тогда все запросы приложения получали «Connection refused».
         */
        val Loopback4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/**
 * Имена для соединений без прокси — через DoH: национальный DNS не отдаёт адреса YouTube.
 * Если DoH недоступен, остаётся системный DNS.
 */
object DohDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "localhost" || hostname.first().isDigit()) return Dns.SYSTEM.lookup(hostname)
        return LocalDns.shared().lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }
}

/**
 * Пока byedpi запускается, запросы ждут его (до [waitMs]), а не уходят напрямую: иначе первая загрузка
 * главной на заблокированной сети падала бы ещё до старта обхода.
 */
class ProxyGate(private val awaitReady: (Long) -> Unit, private val waitMs: Long = 10_000) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        awaitReady(waitMs)
        return chain.proceed(chain.request())
    }
}

/** Рекламные и следящие адреса обрываются до выхода в сеть. */
class AdBlockInterceptor(private val enabled: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (enabled() && AdBlock.isBlocked(request.url.host, request.url.encodedPath)) {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("Blocked")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * Переходы по 301/302/303 для видео: googlevideo ждёт POST и после перенаправления,
 * а OkHttp по стандарту превращает его в GET. Клиент с этим перехватчиком создаётся с followRedirects(false).
 */
object KeepPostRedirects : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0
        while (response.code in 300..308 && response.code != 304 && hops < MAX_HOPS) {
            val location = response.header("Location") ?: break
            val target = response.request.url.resolve(location) ?: break
            response.close()
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
            hops++
        }
        return response
    }

    private const val MAX_HOPS = 5
}

object Http {
    /** User-Agent обычного браузера — как у NewPipe. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** Общий клиент: прокси, DoH для прямых соединений, блокировка рекламы. */
    fun client(port: () -> Int?, adBlock: () -> Boolean = { true }, awaitReady: (Long) -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(BypassProxySelector(port))
        .dns(DohDns)
        .addInterceptor(AdBlockInterceptor(adBlock))
        .addInterceptor(ProxyGate(awaitReady))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Клиент для видеопотоков: тот же пул и прокси, но перенаправления — с сохранением POST. */
    fun mediaClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(KeepPostRedirects)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}
