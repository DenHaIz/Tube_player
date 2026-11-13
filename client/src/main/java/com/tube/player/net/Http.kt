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
        // Через byedpi — только YouTube и Google: стратегии подобраны под них и могут ломать TLS других сайтов
        // (SponsorBlock, Return YouTube Dislike не заблокированы — к ним напрямую, имена через DoH).
        if (!needsBypass(uri?.host)) return listOf(Proxy.NO_PROXY)
        return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(Loopback4, p)))
    }

    companion object {
        private val BypassDomains = listOf(
            "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com",
            "googleapis.com", "google.com", "gstatic.com", "youtube-nocookie.com", "youtubekids.com",
        )

        fun needsBypass(host: String?): Boolean {
            val h = host?.lowercase()?.trimEnd('.') ?: return true
            return BypassDomains.any { h == it || h.endsWith(".$it") }
        }

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
    /** Адрес вместо имени: IPv4 или IPv6 — его не резолвим. */
    private val IpLiteral = Regex("""^(\d{1,3}(\.\d{1,3}){3}|\[?[0-9a-fA-F]*:[0-9a-fA-F:.]+]?)$""")

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "localhost" || IpLiteral.matches(hostname)) return Dns.SYSTEM.lookup(hostname)
        return LocalDns.shared().lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }
}

/**
 * Пока byedpi запускается, запросы ждут его (до [waitMs]), а не уходят напрямую: иначе первая загрузка
 * главной на заблокированной сети падала бы ещё до старта обхода.
 */
class ProxyGate(private val awaitReady: (Long) -> Unit, private val waitMs: Long = 10_000) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Ждут только запросы, которые пойдут через обход: SponsorBlock, дизлайки — напрямую, им ждать нечего.
        if (BypassProxySelector.needsBypass(chain.request().url.host)) awaitReady(waitMs)
        return chain.proceed(chain.request())
    }
}

/**
 * Текст ответа не больше [max] байт: зеркало или чужой сервер не уронит приложение бесконечным ответом
 * (нехватка памяти). Больше — исключение.
 */
fun okhttp3.ResponseBody.limitedString(max: Long): String {
    val source = source()
    if (source.request(max + 1)) throw IOException("response too large")
    return source.buffer.readString(contentType()?.charset() ?: Charsets.UTF_8)
}

/**
 * Сбой запроса к YouTube через обход (обрыв, сброс, тайм-аут TLS) — сигнал сторожу проверить стратегию сейчас.
 * Отменённые запросы (перемотка, закрытый экран) не считаются.
 */
class TroubleListener(private val port: () -> Int?, private val report: () -> Unit) : okhttp3.EventListener() {
    override fun callFailed(call: okhttp3.Call, ioe: IOException) {
        // Порта нет и при упавшем byedpi — об этом тоже нужно сообщить (контроллер поднимет его заново).
        if (call.isCanceled()) return
        if (!BypassProxySelector.needsBypass(call.request().url.host)) return
        report()
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
            // Только https и только видеоузлы: заголовки клиента не уходят на чужие адреса.
            if (!target.isHttps || !target.host.endsWith(".googlevideo.com")) break
            response.close()
            // 303 — по стандарту дальше GET без тела; 301/302/307/308 видеоузлов ждут тот же POST.
            request = if (response.code == 303) request.newBuilder().url(target).get().build() else request.newBuilder().url(target).build()
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
    fun client(port: () -> Int?, adBlock: () -> Boolean = { true }, awaitReady: (Long) -> Unit = {}, onTrouble: () -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(BypassProxySelector(port))
        .eventListener(TroubleListener(port, onTrouble))
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
