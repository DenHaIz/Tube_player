package com.tube.player.youtube

import com.tube.player.net.Http
import com.tube.player.net.limitedString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Сеть для NewPipeExtractor — тот же OkHttp, что у всего приложения, то есть через byedpi.
 * Запросы NewPipe всегда анонимные: cookie аккаунта сюда не попадают, чтобы не ломать извлечение потоков.
 */
class NewPipeDownloader(private val client: () -> OkHttpClient) : Downloader() {
    /** Для сетевого теста: считать любой ответ VISIONOS отказом «не бот». */
    @Volatile internal var forceFallback = false

    private companion object {
        /** Страница просмотра — около 1–2 МБ; больше 20 МБ — не YouTube. */
        const val MAX_BODY = 20L * 1024 * 1024
    }

    override fun execute(request: Request): Response {
        val first = call(request.httpMethod(), request.url(), request.headers(), request.dataToSend())
        // Плеер VISIONOS получил «подтвердите, что вы не бот» — тот же запрос другими клиентами (см. PlayerFallback).
        val data = request.dataToSend()
        if (data != null && PlayerFallback.isPlayerRequest(request.url()) && PlayerFallback.isVisionOs(data) && (forceFallback || PlayerFallback.isBotCheck(first.responseBody()))) {
            for (client in PlayerFallback.Clients) {
                val headers = request.headers() + mapOf(
                    "User-Agent" to listOf(client.userAgent),
                    "X-YouTube-Client-Name" to listOf(client.id.toString()),
                    "X-YouTube-Client-Version" to listOf(client.version),
                )
                val alt = runCatching { call(request.httpMethod(), request.url(), headers, PlayerFallback.rewrite(data, client)) }.getOrNull() ?: continue
                if (PlayerFallback.isPlayable(alt.responseBody())) return alt
            }
        }
        return first
    }

    private fun call(method: String, url: String, requestHeaders: Map<String, List<String>>, data: ByteArray?): Response {
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
        requestHeaders.forEach { (name, values) ->
            when (values.size) {
                0 -> Unit
                1 -> builder.header(name, values[0])
                else -> {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
            }
        }
        client().newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val text = response.body.limitedString(MAX_BODY)
            return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
        }
    }
}
