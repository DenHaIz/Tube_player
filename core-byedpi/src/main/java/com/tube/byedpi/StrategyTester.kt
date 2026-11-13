package com.tube.byedpi

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/** Как проверяем узел. */
enum class ProbeKind {
    /** Полный TLS (Java) и HTTP GET: видно замедление, при котором поток замирает после ~16 КБ. */
    Https,

    /** ClientHello как у Chrome/YouTube (~1.8 КБ, Kyber): прошёл ли он DPI и ответил ли сервер. */
    ChromeHello,
}

/**
 * Что проверяем: узел, путь, сколько байт должно прийти; [group] — для экрана (сайт, превью, видео).
 * [post] — POST с телом `x\0` (так видеоузлы отдают потоки клиентов Android/iOS/visionOS), [headers] — свои заголовки
 * (User-Agent клиента, для которого выдана ссылка).
 */
data class Probe(
    val host: String,
    val path: String,
    val minBytes: Int,
    val kind: ProbeKind = ProbeKind.Https,
    val group: ProbeGroup = ProbeGroup.of(host),
    val post: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

enum class ProbeGroup {
    Site, Thumbs, Video;

    companion object {
        fun of(host: String): ProbeGroup = when {
            "googlevideo" in host -> Video
            "ytimg" in host -> Thumbs
            else -> Site
        }
    }
}

data class ProbeResult(val probe: Probe, val ok: Boolean, val millis: Long, val bytes: Int, val error: String?)

/** Итог проверки стратегии; [at] — когда проверяли (мс), 0 — неизвестно (старые записи). */
data class StrategyScore(val strategy: Strategy, val results: List<ProbeResult>, val startError: String? = null, val at: Long = System.currentTimeMillis()) {
    val passed: Int get() = results.count { it.ok }
    val total: Int get() = results.size
    val avgMillis: Long get() = results.filter { it.ok }.map { it.millis }.average().takeIf { !it.isNaN() }?.toLong() ?: Long.MAX_VALUE
    val works: Boolean get() = total > 0 && passed == total
    val partial: Boolean get() = passed in 1 until total
}

sealed interface TestEvent {
    data class Progress(val done: Int, val total: Int, val score: StrategyScore) : TestEvent
    data class Finished(val ranking: List<StrategyScore>) : TestEvent
}

/**
 * Автоподбор стратегии, как перебор general*.bat у zapret, но автоматически:
 * для каждой стратегии поднимается свой byedpi на отдельном порту (несколько параллельно),
 * через него открываются TLS-соединения к узлам YouTube и замеряется скачивание.
 */
class StrategyTester(
    private val context: Context,
    private val parallelism: Int = 6,
    private val timeoutMs: Int = 7000,
    private val fakeSni: String = Strategies.FakeSniOptions.first(),
) {

    fun run(strategies: List<Strategy>, probes: List<Probe>, blockUdp: Boolean = true): Flow<TestEvent> = channelFlow {
        val gate = Semaphore(parallelism)
        var done = 0
        val scores = coroutineScope {
            strategies.map { strategy ->
                async {
                    gate.withPermit {
                        testOne(strategy, probes, blockUdp)
                    }.also { score ->
                        val n = synchronized(this@StrategyTester) { ++done }
                        send(TestEvent.Progress(n, strategies.size, score))
                    }
                }
            }.awaitAll()
        }
        send(TestEvent.Finished(rank(scores)))
    }.flowOn(Dispatchers.IO)

    /**
     * Одна стратегия на своём экземпляре byedpi. Порт каждый раз свободный: фиксированные порты сталкивались,
     * когда сторож и кнопка «Проверить» запускали проверку одновременно, — и стратегия получала «ошибку».
     */
    suspend fun testOne(strategy: Strategy, probes: List<Probe>, blockUdp: Boolean): StrategyScore {
        val argv = when (val parsed = StrategyArgs.parse(strategy.args)) {
            is StrategyArgs.Result.Ok -> parsed.argv
            is StrategyArgs.Result.Error -> return StrategyScore(strategy, emptyList(), parsed.message)
        }
        var lastError: Exception? = null
        var proxy: ByeDpiProcess? = null
        for (attempt in 0 until START_ATTEMPTS) {
            // Запуск не прерывается отменой, а созданный процесс запоминается внутри того же блока: при отмене
            // возврат из withContext бросает CancellationException уже после запуска — процесс не должен потеряться.
            val started = java.util.concurrent.atomic.AtomicReference<ByeDpiProcess?>()
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    started.set(ByeDpiProcess.start(context, ByeDpiProcess.freePort(), argv, blockUdp, fakeSni))
                }
            } catch (e: CancellationException) {
                started.get()?.let { p -> withContext(NonCancellable + Dispatchers.IO) { p.stop() } }
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            proxy = started.get()
            if (proxy != null) break
            coroutineContext.ensureActive()
        }
        if (proxy != null && !coroutineContext.isActive) {
            withContext(NonCancellable + Dispatchers.IO) { proxy.stop() }
            coroutineContext.ensureActive()
        }
        if (proxy == null) return StrategyScore(strategy, emptyList(), lastError?.message ?: "start failed")
        val port = proxy.port
        try {
            val results = withProcess(proxy) {
                probes.map { probe -> async(Dispatchers.IO) { probe(port, probe) } }.awaitAll()
            }
            return StrategyScore(strategy, results)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { proxy.stop() }
        }
    }

    /**
     * [block] с работающим [proxy]; при отмене процесс гасится сразу — заблокированные чтения сокетов обрываются,
     * и отмена (кнопка «Стоп», найденная замена) не ждёт тайм-аутов проверок.
     */
    private suspend fun <T> withProcess(proxy: ByeDpiProcess, block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val killer = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { proxy.stop() }
            }
        }
        try {
            block()
        } finally {
            killer.cancel()
        }
    }

    /** Проверки через уже работающий прокси (сторож проверяет текущую стратегию, не останавливая VPN). */
    suspend fun checkThrough(port: Int, probes: List<Probe>): List<ProbeResult> = coroutineScope {
        probes.map { probe -> async(Dispatchers.IO) { probe(port, probe) } }.awaitAll()
    }

    private val chromeTemplate: ByteArray by lazy {
        context.assets.open(CHROME_TEMPLATE).use { it.readBytes() }
    }

    private suspend fun probe(port: Int?, probe: Probe): ProbeResult {
        coroutineContext.ensureActive()
        val started = System.nanoTime()
        val target = maxOf(probe.minBytes, 1)
        return try {
            val bytes = when (probe.kind) {
                ProbeKind.Https -> {
                    val data = httpsGet(port, probe.host, probe.path, target, timeoutMs, probe.post, probe.headers)
                    // Ссылка на поток устарела или не для этого адреса (403/410): TLS и ответ узла прошли — судим по ним,
                    // а не считаем стратегию сломанной (иначе смена IP «ломала» бы все стратегии подряд).
                    if (probe.post && statusOf(data) in 400..499) {
                        val ok = data.size >= STALE_LINK_MIN_BYTES
                        return ProbeResult(probe, ok, (System.nanoTime() - started) / 1_000_000, data.size, "stale link ${statusOf(data)}")
                    }
                    data.size
                }
                // Chrome переставляет расширения в каждом соединении, и SNI оказывается то в начале, то во втором
                // TCP-сегменте. Стратегия должна пропускать все варианты, иначе в YouTube она сработает через раз.
                ProbeKind.ChromeHello -> coroutineScope {
                    HelloOrders.map { order ->
                        async(Dispatchers.IO) {
                            chromeHandshake(port, probe.host, ChromeHello.build(chromeTemplate, probe.host, order = order), target, timeoutMs)
                        }
                    }.awaitAll().min()
                }
            }
            val ok = bytes >= target
            ProbeResult(probe, ok, (System.nanoTime() - started) / 1_000_000, bytes, if (ok) null else "short read $bytes")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult(probe, false, (System.nanoTime() - started) / 1_000_000, 0, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /**
     * Настоящий видеоузел этой сети (rrN---sn-….googlevideo.com) — именно с него YouTube качает видео.
     * Ссылка есть на странице просмотра; страница читается через первую сработавшую стратегию из [candidates].
     */
    suspend fun discoverVideoHost(candidates: List<Strategy>, blockUdp: Boolean): String? = withContext(Dispatchers.IO) {
        // По DISCOVERY_PARALLEL стратегий сразу: раньше — по одной, до 8 × 14 с ожидания на сломанной сети.
        for (wave in candidates.take(DISCOVERY_ATTEMPTS).chunked(DISCOVERY_PARALLEL)) {
            ensureActive()
            // Первый найденный узел — сразу; остальные попытки отменяются (их byedpi гасится, чтения обрываются).
            val found = coroutineScope {
                val first = CompletableDeferred<String?>()
                val attempts = wave.map { strategy ->
                    launch {
                        val argv = (StrategyArgs.parse(strategy.args) as? StrategyArgs.Result.Ok)?.argv ?: return@launch
                        val proxy = try {
                            withContext(NonCancellable) { ByeDpiProcess.start(context, ByeDpiProcess.freePort(), argv, blockUdp, fakeSni) }
                        } catch (_: Exception) {
                            return@launch
                        }
                        try {
                            val host = withProcess(proxy) {
                                withContext(Dispatchers.IO) { runCatching { findVideoHost(proxy.port, timeoutMs * 2) }.getOrNull() }
                            }
                            if (host != null) first.complete(host)
                        } finally {
                            withContext(NonCancellable) { proxy.stop() }
                        }
                    }
                }
                launch {
                    attempts.joinAll()
                    first.complete(null)
                }
                first.await().also { coroutineContext.cancelChildren() }
            }
            if (found != null) return@withContext found
        }
        null
    }

    /** Отдаёт ли DNS провайдера адрес youtube.com (с февраля 2026 многие — нет; byedpi тогда ходит через DoH). */
    fun systemDnsResolves(host: String = "www.youtube.com"): Boolean = runCatching {
        java.net.InetAddress.getAllByName(host).any { !it.isAnyLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(false)

    /**
     * Те же проверки без прокси: видно, блокирует ли сеть YouTube вообще
     * (приложение не входит в собственный VPN, поэтому соединения идут напрямую).
     */
    suspend fun baseline(probes: List<Probe>): StrategyScore = coroutineScope {
        val results = probes.map { probe -> async(Dispatchers.IO) { probe(null, probe) } }.awaitAll()
        StrategyScore(Strategy(BASELINE_ID, "DIRECT", "", "Без обхода", "No bypass"), results)
    }

    companion object {
        const val BASELINE_ID = "direct"

        private const val CHROME_TEMPLATE = "fake/tls_clienthello_vk_com_kyber.bin"
        private const val DISCOVERY_ATTEMPTS = 8
        private const val DISCOVERY_PARALLEL = 4
        private val RedirectDomains = listOf("youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "google.com", "googleapis.com", "gstatic.com", "youtu.be")
        private const val STALE_LINK_MIN_BYTES = 12

        /** Код ответа HTTP из первой строки; -1 — не HTTP. */
        fun statusOf(response: ByteArray): Int =
            String(response, 0, minOf(response.size, 64), Charsets.ISO_8859_1).substringBefore('\r').split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        private const val START_ATTEMPTS = 3
        private val HelloOrders = listOf(ChromeHello.Order.SniLast, ChromeHello.Order.SniFirst, ChromeHello.Order.Random)
        private const val FALLBACK_VIDEO_HOST = "redirector.googlevideo.com"

        /** Ответ сервера на ClientHello: ServerHello + сертификаты — несколько КБ. Меньше — DPI оборвал. */
        private const val CHROME_MIN_BYTES = 2000

        /**
         * Проверки «как у приложения YouTube»: страница, превью и видеоузел, каждый — и полным TLS
         * с загрузкой (замедление), и ClientHello как у Chrome (именно его видит DPI в приложении).
         * [videoHost] — настоящий узел сети; без него — redirector (тот же SNI *.googlevideo.com).
         */
        fun defaultProbes(videoHost: String? = null): List<Probe> {
            val video = videoHost ?: FALLBACK_VIDEO_HOST
            return listOf(
                Probe("www.youtube.com", "/", minBytes = 64 * 1024),
                Probe("www.youtube.com", "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
                // API приложения YouTube: без него лента и плеер не открываются, даже если видеоузел доступен.
                Probe("youtubei.googleapis.com", "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello, group = ProbeGroup.Site),
                Probe("i.ytimg.com", "/vi/jNQXAC9IVRw/hqdefault.jpg", minBytes = 8 * 1024),
                Probe("i.ytimg.com", "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
                Probe(video, if (videoHost != null) "/generate_204" else "/report_mapping?di=no", minBytes = 12),
                Probe(video, "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
            )
        }

        /**
         * Проверки для встроенного прокси (Tube Player): приложение ходит своим TLS (Conscrypt, как и эти пробы),
         * Chrome-ClientHello ему не нужен. Сайт (страница ≥ 48 КБ — видно замирание после ~16 КБ), превью и видеоузел.
         * [video] — проверка настоящим видеопотоком (≥ 128 КБ), если приложение её дало; иначе — ответ видеоузла.
         */
        fun appProbes(videoHost: String? = null, video: Probe? = null): List<Probe> {
            val host = video?.host ?: videoHost ?: FALLBACK_VIDEO_HOST
            return listOf(
                Probe("www.youtube.com", "/", minBytes = 48 * 1024),
                Probe("i.ytimg.com", "/vi/jNQXAC9IVRw/hqdefault.jpg", minBytes = 8 * 1024),
                video ?: Probe(host, if (host != FALLBACK_VIDEO_HOST) "/generate_204" else "/report_mapping?di=no", minBytes = 12),
            )
        }

        /**
         * Шлёт ClientHello как у Chrome и считает, сколько пришло в ответ. Ответ должен начинаться с записи
         * Handshake (0x16); тревога (0x15) или тишина — значит, DPI или сервер оборвали соединение.
         */
        fun chromeHandshake(proxyPort: Int?, host: String, hello: ByteArray, minBytes: Int, timeoutMs: Int): Int {
            val socket = if (proxyPort != null) {
                Socks5.connect(proxyPort, host, 443, timeoutMs)
            } else {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(java.net.InetSocketAddress(host, 443), timeoutMs)
                }
            }
            socket.use {
                it.soTimeout = timeoutMs
                it.getOutputStream().apply {
                    write(hello)
                    flush()
                }
                val input = it.getInputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                var first = -1
                while (total < minBytes) {
                    val n = runCatching { input.read(buffer) }.getOrElse { e -> if (total > 0) -1 else throw e }
                    if (n < 0) break
                    if (first < 0 && n > 0) first = buffer[0].toInt() and 0xFF
                    total += n
                }
                if (first == 0x15) throw java.io.IOException("TLS alert")
                if (first != 0x16 && total > 0) throw java.io.IOException("not TLS")
                return total
            }
        }

        private val VideoHostPattern = Regex("""rr\d{1,2}---sn-[a-z0-9-]+\.googlevideo\.com""")

        /** Читает страницу просмотра потоком, пока не встретится ссылка на видеоузел. */
        fun findVideoHost(proxyPort: Int?, timeoutMs: Int): String? {
            val raw = if (proxyPort != null) Socks5.connect(proxyPort, "www.youtube.com", 443, timeoutMs) else Socket("www.youtube.com", 443)
            raw.use {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "www.youtube.com", 443, true) as SSLSocket
                tls.use {
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    tls.outputStream.apply {
                        write(httpRequest("www.youtube.com", WATCH_PATH).toByteArray())
                        flush()
                    }
                    val deadline = System.nanoTime() + timeoutMs * 2_000_000L
                    val input = tls.inputStream
                    val buffer = ByteArray(32 * 1024)
                    var tail = ""
                    var read = 0
                    while (read < WATCH_MAX_BYTES && System.nanoTime() < deadline) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        val chunk = tail + String(buffer, 0, n, Charsets.ISO_8859_1)
                        VideoHostPattern.find(chunk)?.let { m -> return m.value }
                        tail = chunk.takeLast(80)
                    }
                    return null
                }
            }
        }

        private const val WATCH_PATH = "/watch?v=jNQXAC9IVRw"
        private const val WATCH_MAX_BYTES = 2_000_000

        /** Сначала все пройденные проверки, затем скорость. */
        fun rank(scores: List<StrategyScore>): List<StrategyScore> =
            scores.sortedWith(compareByDescending<StrategyScore> { it.passed }.thenBy { it.avgMillis })

        /** Тот же браузер, что у остальных запросов приложения (NewPipe, InnerTube): один отпечаток с одного адреса. */
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** Тело POST видеоузлу — как у NewPipe/YouTube. */
        private val PostBody = byteArrayOf(0x78, 0)

        fun httpRequest(host: String, path: String, post: Boolean = false, headers: Map<String, String> = emptyMap()): String {
            // Перевод строки в заголовке или пути — подмена запроса: такие не отправляем.
            require(path.none { it == '\r' || it == '\n' || it == ' ' }) { "bad path" }
            val extra = headers.filter { (k, v) -> k.isNotEmpty() && k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' } }
            return buildList {
                add("${if (post) "POST" else "GET"} $path HTTP/1.1")
                add("Host: $host")
                // Десктопный браузер: с мобильным UA youtube.com сразу уводит на m.youtube.com коротким 302.
                if (extra.keys.none { it.equals("User-Agent", ignoreCase = true) }) add("User-Agent: $DESKTOP_UA")
                extra.forEach { (k, v) -> add("$k: $v") }
                // Согласие на cookie (как у NewPipe): без него из ЕС youtube.com уводит на страницу согласия.
                if (host == "youtube.com" || host.endsWith(".youtube.com")) add("Cookie: SOCS=CAE=")
                add("Accept: */*")
                add("Accept-Language: ru-RU,ru;q=0.9,en;q=0.8")
                if (post) add("Content-Length: ${PostBody.size}")
                add("Connection: close")
                add("")
                add("")
            }.joinToString(CRLF)
        }

        private const val CRLF = "\r\n"
        private const val MAX_REDIRECTS = 3

        /**
         * HTTPS GET через SOCKS5 (или напрямую, если [proxyPort] = null) с переходом по редиректам;
         * читает не больше [maxBytes] (ответ вместе с заголовками). Если поток оборвался или замер
         * после первых байт, возвращает то, что успело прийти, — так видно замедление.
         */
        fun httpsGet(proxyPort: Int?, host: String, path: String, maxBytes: Int, timeoutMs: Int, post: Boolean = false, headers: Map<String, String> = emptyMap()): ByteArray {
            var currentHost = host
            var currentPath = path
            var currentPost = post
            repeat(MAX_REDIRECTS + 1) {
                val bytes = fetchOnce(proxyPort, currentHost, currentPath, maxBytes, timeoutMs, currentPost, headers)
                val location = redirectTarget(bytes) ?: return bytes
                val (nextHost, nextPath) = resolveLocation(location, currentHost) ?: return bytes
                // 303 — дальше GET; 307/308 сохраняют метод.
                if (statusOf(bytes) == 303) currentPost = false
                currentHost = nextHost
                currentPath = nextPath
            }
            throw java.io.IOException("too many redirects")
        }

        /** Location из ответа 301/302/303/307/308 или null. */
        internal fun redirectTarget(response: ByteArray): String? {
            val head = String(response, 0, minOf(response.size, 8192), Charsets.ISO_8859_1)
            val headers = head.substringBefore(CRLF + CRLF)
            val status = headers.substringBefore(CRLF).split(' ').getOrNull(1)?.toIntOrNull() ?: return null
            if (status !in setOf(301, 302, 303, 307, 308)) return null
            return headers.lineSequence()
                .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
        }

        /** https://host/path или /path относительно текущего узла; http:// и чужие схемы не идём. */
        internal fun resolveLocation(location: String, currentHost: String): Pair<String, String>? = when {
            location.startsWith("https://") -> {
                val rest = location.removePrefix("https://")
                val hostPart = rest.substringBefore('/').substringBefore('?')
                val pathPart = rest.removePrefix(hostPart).ifEmpty { "/" }
                // Другой порт — не туда, куда проверяем (и не повод ходить через прокси на произвольные порты).
                val host = hostPart.substringBefore(':').lowercase()
                // Только 443 и только узлы YouTube/Google: проверка не уходит по редиректу на чужие адреса.
                if (hostPart.substringAfter(':', "443") != "443" || RedirectDomains.none { host == it || host.endsWith(".$it") }) null
                else host to (if (pathPart.startsWith("/")) pathPart else "/$pathPart")
            }
            location.startsWith("/") -> currentHost to location
            else -> null
        }

        private fun fetchOnce(proxyPort: Int?, host: String, path: String, maxBytes: Int, timeoutMs: Int, post: Boolean, headers: Map<String, String>): ByteArray {
            val raw = if (proxyPort != null) {
                Socks5.connect(proxyPort, host, 443, timeoutMs)
            } else {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(java.net.InetSocketAddress(host, 443), timeoutMs)
                }
            }
            raw.use {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
                tls.use {
                    // Проверка имени в сертификате: перехват (портал Wi-Fi со своим сертификатом) — не «работает».
                    tls.sslParameters = tls.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    tls.outputStream.apply {
                        write(httpRequest(host, path, post, headers).toByteArray(Charsets.ISO_8859_1))
                        if (post) write(PostBody)
                        flush()
                    }
                    // Весь ответ — не дольше двух сроков: медленная «струйка» (замедление) — тоже провал,
                    // а не бесконечное ожидание (soTimeout ограничивает только паузу между пакетами).
                    val deadline = System.nanoTime() + timeoutMs * 2_000_000L
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    val input = tls.inputStream
                    while (out.size() < maxBytes && System.nanoTime() < deadline) {
                        val n = runCatching { input.read(buffer) }.getOrElse { if (out.size() > 0) -1 else throw it }
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        }
    }
}
