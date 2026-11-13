@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.net

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.tube.byedpi.Probe
import com.tube.player.player.YoutubeDataSource
import com.tube.player.youtube.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.concurrent.atomic.AtomicLong

/**
 * Проверка обхода настоящим видеопотоком: первые [BYTES] звуковой дорожки стабильного видео, тем же запросом,
 * что у плеера (POST `x\0`, `rn`, User-Agent клиента ссылки). Ловит то, чего не видно по короткому ответу видеоузла:
 * замирание после ~16 КБ и замедление (не успело за срок — провал).
 *
 * Ссылка получается через NewPipe (через текущий прокси) в фоне [scope] и живёт до `expire` минус запас.
 * Она привязана к адресу сети (`ip=`), поэтому после смены сети берётся заново. Ждём её не дольше [TIMEOUT_MS]:
 * не успела или не удалось — null (проверки берут ответ видеоузла); после неудачи — новая попытка через [RETRY_MS].
 */
class VideoProbe(
    private val scope: CoroutineScope,
    private val networkKey: () -> String?,
    private val repository: () -> Repository,
) {
    private class Cached(val probe: Probe, val validUntil: Long, val key: String?)

    @Volatile private var cached: Cached? = null
    @Volatile private var failed: Pair<Long, String?>? = null
    @Volatile private var inflight: Deferred<Probe?>? = null

    suspend fun get(): Probe? {
        val now = System.currentTimeMillis()
        val key = networkKey()
        cached?.takeIf { now < it.validUntil && it.key == key }?.let { return it.probe }
        failed?.let { (at, k) -> if (k == key && now - at < RETRY_MS) return null }
        val fetch = synchronized(this) {
            inflight?.takeIf { it.isActive } ?: scope.async(Dispatchers.IO) { fetch(key) }.also { inflight = it }
        }
        // Ожидание обрывается по сроку, а сама загрузка доходит в фоне — пригодится следующей проверке.
        return withTimeoutOrNull(TIMEOUT_MS) { fetch.await() }
    }

    private suspend fun fetch(key: String?): Probe? {
        val now = System.currentTimeMillis()
        val info = try {
            repository().video(STABLE, fresh = true).info
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val stream = info?.audioStreams
            ?.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            ?.let { list -> list.firstOrNull { it.itag == 140 } ?: list.maxByOrNull { it.averageBitrate } }
        val probe = stream?.content?.let(::probeFor)
        if (probe == null) {
            failed = now to key
            return null
        }
        failed = null
        cached = Cached(probe, expiresAt(stream.content, now) - MARGIN_MS, key)
        return probe
    }

    companion object {
        /** «Never Gonna Give You Up»: 3,5 минуты — звука на несколько мегабайт, видео не удаляют. */
        const val STABLE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        private const val BYTES = 192 * 1024
        private const val TIMEOUT_MS = 10_000L
        private const val RETRY_MS = 2 * 60_000L
        private const val MARGIN_MS = 30 * 60_000L

        /** Запрос к видеоузлу как у плеера: диапазон в параметре `range`, остальное — [YoutubeDataSource.resolve]. */
        fun probeFor(url: String): Probe? {
            val ranged = Uri.parse(url).buildUpon().appendQueryParameter("range", "0-${BYTES - 1}").build()
            if (!YoutubeDataSource.isVideoPlayback(ranged)) return null
            val spec = YoutubeDataSource.resolve(DataSpec(ranged), AtomicLong(0))
            val uri = spec.uri
            val host = uri.host ?: return null
            val path = (uri.encodedPath ?: "/") + (uri.encodedQuery?.let { "?$it" } ?: "")
            return Probe(host, path, minBytes = 128 * 1024, post = spec.httpMethod == DataSpec.HTTP_METHOD_POST, headers = spec.httpRequestHeaders)
        }

        /** `expire` из ссылки (секунды) → мс; нет — через час. */
        fun expiresAt(url: String, now: Long): Long =
            Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000)?.takeIf { it > now } ?: (now + 60 * 60_000L)
    }
}
