package com.tube.strategy

import android.content.Context
import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeKind
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Итоги проверок в одной сети: что работает и какую стратегию там использовать. */
data class NetworkResults(
    val testedAt: Long = 0,
    val systemDns: Boolean? = null,
    val baseline: StrategyScore? = null,
    val scores: Map<String, StrategyScore> = emptyMap(),
    /** Стратегия, выбранная для этой сети (вручную или сторожем). */
    val chosen: String? = null,
    /** Настоящий видеоузел сети (rrN---sn-….googlevideo.com), найденный при подборе. */
    val videoHost: String? = null,
    /** Когда сторож последний раз видел стратегию работающей в живом трафике (id → мс). */
    val lastGood: Map<String, Long> = emptyMap(),
) {
    val ranking: List<StrategyScore> get() = StrategyTester.rank(scores.values.toList())
    val working: List<StrategyScore> get() = ranking.filter { it.works }

    /** Рабочие, проверенные не раньше [maxAgeMs] назад: старые итоги в другой день могут уже не соответствовать сети. */
    fun workingSince(now: Long, maxAgeMs: Long): List<StrategyScore> = working.filter { now - it.at <= maxAgeMs }
}

/**
 * Результаты по сетям в filesDir/results.json — переживают перезапуск.
 *
 * Память обновляется сразу, а файл пишется в фоне не чаще раза в [WRITE_DELAY_MS] (подбор присылает
 * сотню обновлений подряд) и атомарно: если процесс убьют посреди записи, остаётся прошлая целая версия.
 */
class ResultsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "results.json"))
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, NetworkResults>> = _all.asStateFlow()

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "results-writer").apply { isDaemon = true } }
    private val writeScheduled = AtomicBoolean(false)

    fun of(key: String?): NetworkResults? = key?.let { _all.value[it] }

    @Synchronized
    fun update(key: String, transform: (NetworkResults) -> NetworkResults) {
        _all.value = _all.value + (key to transform(_all.value[key] ?: NetworkResults()))
        if (writeScheduled.compareAndSet(false, true)) {
            writer.schedule({
                writeScheduled.set(false)
                write(_all.value)
            }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Записать немедленно (тесты). */
    fun flush() = write(_all.value)

    @Synchronized
    fun clear() {
        _all.value = emptyMap()
        writer.execute { file.delete() }
    }

    private fun write(all: Map<String, NetworkResults>) {
        synchronized(file) {
            var out: java.io.FileOutputStream? = null
            try {
                out = file.startWrite()
                out.write(encode(all).toString().toByteArray())
                file.finishWrite(out)
            } catch (_: Exception) {
                out?.let { file.failWrite(it) }
            }
        }
    }

    private fun load(): Map<String, NetworkResults> = runCatching {
        val root = JSONObject(String(file.readFully()))
        root.keys().asSequence().associateWith { decodeNetwork(root.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    companion object {
        private const val WRITE_DELAY_MS = 1500L

        /**
         * Версия методики проверки. Результаты старой методики сбрасываются (выбор стратегии остаётся):
         * в 0.2 не было проверки ClientHello «как у Chrome», и часть стратегий помечалась рабочими зря.
         */
        const val PROBE_VERSION = 2

        internal fun encode(all: Map<String, NetworkResults>): JSONObject = JSONObject().apply {
            all.forEach { (key, value) -> put(key, encodeNetwork(value)) }
        }

        private fun encodeNetwork(n: NetworkResults) = JSONObject().apply {
            put("probeVersion", PROBE_VERSION)
            put("testedAt", n.testedAt)
            n.systemDns?.let { put("systemDns", it) }
            n.chosen?.let { put("chosen", it) }
            n.videoHost?.let { put("videoHost", it) }
            if (n.lastGood.isNotEmpty()) put("lastGood", JSONObject().apply { n.lastGood.forEach { (id, at) -> put(id, at) } })
            n.baseline?.let { put("baseline", encodeScore(it)) }
            put("scores", JSONArray().apply { n.scores.values.forEach { put(encodeScore(it)) } })
        }

        private fun encodeScore(s: StrategyScore) = JSONObject().apply {
            put("id", s.strategy.id)
            put("name", s.strategy.name)
            put("args", s.strategy.args)
            s.startError?.let { put("startError", it) }
            put("at", s.at)
            put("results", JSONArray().apply {
                s.results.forEach { r ->
                    put(JSONObject().apply {
                        put("host", r.probe.host)
                        // Ссылка на видеопоток подписана и живёт часы — её не храним.
                        put("path", if ("videoplayback" in r.probe.path) r.probe.path.substringBefore('?') else r.probe.path)
                        put("min", r.probe.minBytes)
                        put("kind", r.probe.kind.name)
                        put("ok", r.ok)
                        put("ms", r.millis)
                        put("bytes", r.bytes)
                        r.error?.let { put("error", it) }
                    })
                }
            })
        }

        internal fun decodeNetwork(o: JSONObject): NetworkResults {
            val lastGood = o.optJSONObject("lastGood")?.let { g -> g.keys().asSequence().associateWith { g.optLong(it) } }.orEmpty()
            if (o.optInt("probeVersion") < PROBE_VERSION) {
                return NetworkResults(chosen = o.optString("chosen").ifEmpty { null }, lastGood = lastGood)
            }
            val testedAt = o.optLong("testedAt")
            return NetworkResults(
                testedAt = testedAt,
                systemDns = if (o.has("systemDns")) o.getBoolean("systemDns") else null,
                baseline = o.optJSONObject("baseline")?.let { decodeScore(it, testedAt) },
                // Записи без времени — из прошлых версий: считаем, что проверены вместе с подбором.
                scores = o.optJSONArray("scores")?.let { arr -> (0 until arr.length()).map { decodeScore(arr.getJSONObject(it), testedAt) } }.orEmpty().associateBy { it.strategy.id },
                chosen = o.optString("chosen").ifEmpty { null },
                videoHost = o.optString("videoHost").ifEmpty { null },
                lastGood = lastGood,
            )
        }

        private fun decodeScore(o: JSONObject, testedAt: Long): StrategyScore {
            val id = o.getString("id")
            // Встроенная стратегия берётся из текущей версии приложения (описания могли обновиться).
            val strategy = Strategies.byId(id) ?: Strategy(id, o.optString("name", id), o.optString("args"), "", "")
            val results = o.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    ProbeResult(
                        Probe(
                            r.getString("host"), r.optString("path", "/"), r.optInt("min"),
                            kind = runCatching { ProbeKind.valueOf(r.optString("kind")) }.getOrDefault(ProbeKind.Https),
                        ),
                        r.getBoolean("ok"), r.optLong("ms"), r.optInt("bytes"), r.optString("error").ifEmpty { null },
                    )
                }
            }.orEmpty()
            return StrategyScore(strategy, results, o.optString("startError").ifEmpty { null }, at = o.optLong("at", testedAt))
        }
    }
}
