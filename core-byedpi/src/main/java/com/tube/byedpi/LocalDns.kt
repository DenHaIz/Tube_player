package com.tube.byedpi

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * DNS-сервер на 127.0.0.1 для процессов byedpi: принимает обычные DNS-запросы и пересылает их
 * через DNS-over-HTTPS. С февраля 2026 youtube.com не резолвится через национальную систему DNS,
 * которой пользуется большинство провайдеров, а DoH по IP-адресу (без SNI) ТСПУ не видит.
 * byedpi обращается сюда через подменённый getaddrinfo (tube_dns.c, переменная TUBE_DNS).
 */
class LocalDns private constructor(private val socket: DatagramSocket) {

    val port: Int get() = socket.localPort

    /** Значение переменной окружения TUBE_DNS для byedpi. */
    val address: String get() = "127.0.0.1:$port"

    /** Запросы byedpi — отдельно от фоновых обновлений, чтобы не стоять за ними в очереди. Очереди ограничены. */
    private val live = boundedPool(8, "tube-dns")
    private val background = boundedPool(3, "tube-dns-bg")
    private val upstream = boundedPool(8, "tube-dns-up")

    private class Entry(val freshUntil: Long, val staleUntil: Long, val body: ByteArray)

    private val cache = ConcurrentHashMap<String, Entry>()
    /** Одинаковые запросы, которые уже идут, не дублируются. */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    @Volatile private var preferred = 0

    init {
        thread(name = "tube-dns-listen", isDaemon = true) {
            val buffer = ByteArray(1500)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { if (socket.isClosed) return@thread }
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val from = packet.socketAddress
                live.execute {
                    // Не смогли узнать адрес — отвечаем SERVFAIL сразу, а не заставляем byedpi ждать таймаут.
                    val response = runCatching { resolve(query) }.getOrNull() ?: servFail(query) ?: return@execute
                    runCatching { socket.send(DatagramPacket(response, response.size, from)) }
                }
            }
        }
    }

    /**
     * Ответ на DNS-запрос (wire format) с тем же id; null — ни один DoH-сервер не ответил.
     *
     * byedpi резолвит имена синхронно, в своём единственном потоке: пока ждём DoH, стоят все его соединения.
     * Поэтому устаревший ответ отдаётся сразу и обновляется в фоне, а адреса YouTube запрашиваются заранее ([prefetch]).
     */
    fun resolve(query: ByteArray): ByteArray? {
        // Наверх и в кэш идёт только «чистый» запрос: стандартный заголовок и один вопрос. Иначе другое приложение
        // могло бы странным запросом (флаги, лишние записи) положить в кэш пустой ответ под ключ запросов byedpi.
        val clean = canonical(query) ?: return null
        val key = questionKey(clean)
        val now = System.currentTimeMillis()
        val entry = cache[key]
        val body = when {
            entry != null && entry.freshUntil > now -> entry.body
            entry != null && entry.staleUntil > now -> {
                refreshLater(key, clean)
                entry.body
            }
            else -> runCatching { fetchShared(key, clean).get(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        } ?: return null
        return body.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }
    }

    /**
     * Адреса IPv4 имени через DoH (с тем же кэшем, что у byedpi) — для соединений приложения без прокси.
     * Пустой список — имя не нашлось или DoH недоступен.
     */
    fun lookup(host: String): List<InetAddress> {
        val query = runCatching { aQuery(host) }.getOrNull() ?: return emptyList()
        val response = resolve(query) ?: return emptyList()
        return parseA(response)
    }

    /** Заранее узнать адреса (A) — при старте VPN, чтобы первые соединения YouTube не ждали DoH. */
    fun prefetch(hosts: Collection<String>) {
        hosts.forEach { host ->
            val query = runCatching { aQuery(host) }.getOrNull()?.let(::canonical) ?: return@forEach
            val entry = cache[questionKey(query)]
            if (entry == null || entry.freshUntil <= System.currentTimeMillis()) refreshLater(questionKey(query), query)
        }
    }

    private fun refreshLater(key: String, query: ByteArray) {
        if (inflight.containsKey(key)) return
        runCatching { background.execute { runCatching { fetchShared(key, query).get() } } }
    }

    private fun fetchShared(key: String, query: ByteArray): CompletableFuture<ByteArray?> {
        inflight[key]?.let { return it }
        val future = CompletableFuture<ByteArray?>()
        val existing = inflight.putIfAbsent(key, future)
        if (existing != null) return existing
        try {
            future.complete(fetchAndStore(key, query))
        } catch (e: Exception) {
            future.complete(null)
        } finally {
            inflight.remove(key)
        }
        return future
    }

    private fun fetchAndStore(key: String, query: ByteArray): ByteArray? =
        fetch(query.copyOf().also { it[0] = 0; it[1] = 0 })?.also { response ->
            // Кэшируем только успешные ответы (RCODE = 0) с хотя бы одной записью и на тот же вопрос.
            val answers = if (response.size >= 12) ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF) else 0
            val sameQuestion = response.size >= query.size && query.copyOfRange(12, query.size).contentEquals(response.copyOfRange(12, query.size))
            if (response.size >= 12 && response[3].toInt() and 0x0F == 0 && answers > 0 && sameQuestion) {
                val now = System.currentTimeMillis()
                if (cache.size >= MAX_CACHE) trimCache(now)
                cache[key] = Entry(now + FRESH_MS, now + STALE_MS, response)
            }
        }

    /** Кэш не растёт бесконечно: сначала выбрасываем просроченное, затем самое старое. */
    private fun trimCache(now: Long) {
        cache.entries.removeIf { it.value.staleUntil <= now }
        if (cache.size < MAX_CACHE) return
        cache.entries.sortedBy { it.value.freshUntil }.take(cache.size - MAX_CACHE * 3 / 4).forEach { cache.remove(it.key) }
    }

    /**
     * Сначала сервер, который отвечал в прошлый раз; не ответил за [FIRST_TRY_MS] — остальные параллельно,
     * берём первый ответ. Так запрос не ждёт последовательно таймауты всех четырёх серверов.
     */
    private fun fetch(query: ByteArray): ByteArray? {
        val first = preferred
        val service = ExecutorCompletionService<Pair<Int, ByteArray>>(upstream)
        val started = System.currentTimeMillis()
        var pending = 0
        fun ask(index: Int) {
            runCatching { service.submit { index to post(Upstreams[index], query) } }.onSuccess { pending++ }
        }
        ask(first)
        var hedged = false
        while (true) {
            val now = System.currentTimeMillis()
            if (!hedged && (pending == 0 || now - started >= FIRST_TRY_MS)) {
                // Медленный основной сервер не отменяем — вдруг он ответит первым.
                Upstreams.indices.filter { it != first }.forEach(::ask)
                hedged = true
            }
            if (pending == 0) return null
            val wait = if (hedged) started + FIRST_TRY_MS + TIMEOUT_MS - now else started + FIRST_TRY_MS - now
            if (wait <= 0) return null
            val done = service.poll(wait, TimeUnit.MILLISECONDS) ?: if (hedged) return null else continue
            pending--
            val (index, answer) = runCatching { done.get() }.getOrNull() ?: continue
            preferred = index
            return answer
        }
    }

    private fun post(url: String, query: ByteArray): ByteArray {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            // Поток дочитан и закрыт без disconnect(): соединение остаётся в пуле keep-alive,
            // и следующий запрос не платит за новое TCP+TLS-рукопожатие.
            return connection.inputStream.use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    companion object {
        /** DoH по IP: сертификаты Google и Cloudflare содержат эти адреса, SNI в ClientHello нет. */
        val Upstreams = listOf(
            "https://8.8.8.8/dns-query",
            "https://1.1.1.1/dns-query",
            "https://8.8.4.4/dns-query",
            "https://1.0.0.1/dns-query",
        )
        private const val TIMEOUT_MS = 3000
        private const val FIRST_TRY_MS = 1200L
        /** Чуть меньше, чем ждёт tube_dns.c: ответ (или SERVFAIL) успевает дойти до byedpi. */
        private const val ANSWER_WAIT_MS = 4500L
        private const val MAX_CACHE = 2048

        private fun boundedPool(threads: Int, name: String) = ThreadPoolExecutor(
            threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(256),
            { r -> Thread(r, name).apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        /** Ответ «сервер не смог» на запрос [query]; null — запрос не похож на DNS. */
        internal fun servFail(query: ByteArray): ByteArray? {
            if (query.size < 12) return null
            return query.copyOf().also {
                it[2] = (it[2].toInt() or 0x80).toByte() // QR = ответ
                it[3] = ((it[3].toInt() and 0xF0) or 0x80 or 2).toByte() // RA, RCODE = SERVFAIL
                for (i in 6 until 12) it[i] = 0 // ни ответов, ни дополнительных записей
            }
        }
        private const val FRESH_MS = 10 * 60_000L
        private const val STALE_MS = 24 * 60 * 60_000L

        /** Узлы, к которым YouTube подключается первым делом. */
        val YouTubeHosts = listOf(
            "www.youtube.com", "m.youtube.com", "youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com",
            "www.googleapis.com", "i.ytimg.com", "i9.ytimg.com", "s.ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com",
            "yt3.googleusercontent.com", "redirector.googlevideo.com", "play.googleapis.com", "jnn-pa.googleapis.com",
            "suggestqueries-clients6.youtube.com", "www.google.com", "accounts.google.com",
        )

        /** Узлы того же кластера, что и [videoHost] (rr1…rr16---sn-xxx): по ним идёт видео. */
        fun videoCluster(videoHost: String?): List<String> {
            val suffix = videoHost?.substringAfter("---", "")?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return (1..16).map { "rr$it---$suffix" }
        }

        /** DNS-запрос A для [host] (id = 0, RD). */
        internal fun aQuery(host: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            host.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(byteArrayOf(0, 0, 1, 0, 1))
            return out.toByteArray()
        }

        @Volatile private var instance: LocalDns? = null

        /** Один сервер на процесс: им пользуются и VPN, и тестер стратегий. */
        fun shared(): LocalDns = instance ?: synchronized(this) {
            instance ?: LocalDns(DatagramSocket(0, InetAddress.getByName("127.0.0.1"))).also { instance = it }
        }

        /** Записи A из DNS-ответа (wire format); CNAME и прочее пропускаются. */
        internal fun parseA(response: ByteArray): List<InetAddress> {
            if (response.size < 12 || response[3].toInt() and 0x0F != 0) return emptyList()
            fun u16(at: Int) = ((response[at].toInt() and 0xFF) shl 8) or (response[at + 1].toInt() and 0xFF)
            fun skipName(start: Int): Int {
                var at = start
                while (at < response.size) {
                    val len = response[at].toInt() and 0xFF
                    if (len == 0) return at + 1
                    if (len and 0xC0 == 0xC0) return at + 2
                    at += len + 1
                }
                return at
            }
            val questions = u16(4)
            val answers = u16(6)
            var at = 12
            repeat(questions) { at = skipName(at) + 4 }
            val out = ArrayList<InetAddress>()
            repeat(answers) {
                at = skipName(at)
                if (at + 10 > response.size) return out
                val type = u16(at)
                val length = u16(at + 8)
                at += 10
                if (at + length > response.size) return out
                if (type == 1 && length == 4) out += InetAddress.getByAddress(response.copyOfRange(at, at + 4))
                at += length
            }
            return out
        }

        /** Секция вопроса (имя + тип + класс) без id и флагов — ключ кэша. */
        /**
         * Запрос в стандартном виде: id 0, только RD, один вопрос (имя + тип + класс), без других записей.
         * null — не запрос или вопрос битый (сжатие в вопросе, обрыв, больше одного вопроса).
         */
        internal fun canonical(query: ByteArray): ByteArray? {
            if (query.size < 17) return null
            if (query[2].toInt() and 0x80 != 0) return null // это ответ, а не вопрос
            if (((query[4].toInt() and 0xFF) shl 8 or (query[5].toInt() and 0xFF)) != 1) return null
            var i = 12
            while (true) {
                if (i >= query.size) return null
                val len = query[i].toInt() and 0xFF
                if (len == 0) break
                if (len > 63) return null
                i += 1 + len
            }
            val end = i + 1 + 4
            if (end > query.size) return null
            return ByteArray(end).also {
                it[2] = 0x01 // RD
                it[5] = 1 // QDCOUNT
                query.copyInto(it, 12, 12, end)
            }
        }

        internal fun questionKey(query: ByteArray): String =
            query.copyOfRange(12, query.size).joinToString("") { "%02x".format(it) }
    }
}
