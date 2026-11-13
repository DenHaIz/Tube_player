@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.tube.player.net.Http
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Загрузка видео для Media3 через OkHttp (то есть через byedpi), с теми же правками запросов к googlevideo,
 * что у NewPipe (YoutubeHttpDataSource): POST с телом `x\0`, параметр `rn` (номер запроса),
 * User-Agent клиента, для которого выданы ссылки, и Origin/Referer для ссылок WEB.
 */
object YoutubeDataSource {
    private val PostBody = byteArrayOf(0x78, 0)
    private const val YOUTUBE = "https://www.youtube.com"

    fun factory(mediaClient: OkHttpClient): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(mediaClient)
        val requestNumber = AtomicLong()
        return ResolvingDataSource.Factory(upstream) { spec -> resolve(spec, requestNumber) }
    }

    internal fun resolve(spec: DataSpec, requestNumber: AtomicLong): DataSpec {
        val url = spec.uri.toString()
        val headers = HashMap(spec.httpRequestHeaders)
        // Сегменты эфира — ссылки-пути (…/videoplayback/id/…/sq/N/…) без «?»: их не трогаем (GET, без rn),
        // иначе «&rn=» портил последний сегмент пути.
        if (!isVideoPlayback(spec.uri) || spec.uri.query == null || "/sq/" in (spec.uri.path ?: "")) {
            headers.putIfAbsent("User-Agent", Http.USER_AGENT)
            return spec.buildUpon().setHttpRequestHeaders(headers).build()
        }
        val target = if (spec.uri.getQueryParameter("rn") != null) url else spec.uri.buildUpon().appendQueryParameter("rn", requestNumber.getAndIncrement().toString()).build().toString()
        val fallback = com.tube.player.youtube.PlayerFallback.forStreamUrl(url)
        when {
            // User-Agent — тот же, с которым получена ссылка: для VISIONOS он собирается из языка запроса (страна в конце).
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> headers["User-Agent"] = YoutubeParsingHelper.getVisionOsUserAgent(org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization())
            // Ссылки запасных клиентов (iOS, Android VR — когда VISIONOS получил «не бот»).
            fallback != null -> headers["User-Agent"] = fallback.userAgent
            YoutubeParsingHelper.isWebStreamingUrl(url) -> {
                headers["User-Agent"] = Http.USER_AGENT
                headers["Origin"] = YOUTUBE
                headers["Referer"] = YOUTUBE
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }
            else -> headers["User-Agent"] = Http.USER_AGENT
        }
        return spec.buildUpon()
            .setUri(target)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(PostBody)
            .setHttpRequestHeaders(headers)
            .build()
    }

    fun isVideoPlayback(uri: Uri): Boolean =
        uri.host?.endsWith(".googlevideo.com") == true && uri.path?.startsWith("/videoplayback") == true
}
