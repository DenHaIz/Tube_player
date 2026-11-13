@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.net

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.tube.byedpi.Probe
import com.tube.player.player.YoutubeDataSource
import com.tube.player.youtube.Repository
import java.util.concurrent.atomic.AtomicLong

/**
 * Проверка обхода настоящим видеопотоком: первые [BYTES] звуковой дорожки, тем же запросом, что у плеера
 * (POST `x\0`, `rn`, User-Agent клиента ссылки). Ловит то, чего не видно по короткому ответу видеоузла:
 * замирание после ~16 КБ и замедление (не успело за срок — провал).
 *
 * **Своих запросов плеера нет:** берётся дорожка последнего открытого видео ([Repository.lastStream]).
 * В 0.4.8 проверка сама извлекала одно и то же видео при каждой смене сети и каждые 2 минуты при неудаче —
 * YouTube принимал это за бота и отвечал «подтвердите, что вы не бот» на все видео.
 * Ссылка привязана к адресу сети (`ip=`): после смены сети — только ссылка, полученная уже в новой сети;
 * устаревшую ([invalidate]: ответ 4xx) не берём до следующего видео. Нет подходящей — null (ответ видеоузла).
 */
class VideoProbe(
    private val networkKey: () -> String?,
    private val repository: () -> Repository,
) {
    @Volatile private var seenUrl: String? = null
    @Volatile private var seenKey: String? = null

    fun get(): Probe? {
        val url = repository().lastStream ?: return null
        val key = networkKey()
        if (url != seenUrl) {
            seenUrl = url
            seenKey = key
        }
        if (seenKey != key) return null
        if (expiresAt(url, System.currentTimeMillis()) - MARGIN_MS < System.currentTimeMillis()) return null
        return probeFor(url)
    }

    /** Ссылка не годится (видеоузел ответил 4xx) — не использовать до следующего открытого видео. */
    fun invalidate() {
        seenKey = STALE
    }

    companion object {
        private const val BYTES = 192 * 1024
        private const val MARGIN_MS = 10 * 60_000L
        private const val STALE = "\u0000stale"

        /** Дорожка годится для проверки, если она заметно длиннее проверяемого куска (`clen` — размер файла). */
        fun usable(url: String): Boolean = (Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: 0) >= 2L * BYTES

        /** Запрос к видеоузлу как у плеера: диапазон в параметре `range`, остальное — [YoutubeDataSource.resolve]. */
        fun probeFor(url: String): Probe? {
            if (!usable(url)) return null
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
