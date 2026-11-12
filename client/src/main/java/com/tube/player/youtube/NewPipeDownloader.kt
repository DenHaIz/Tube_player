package com.tube.player.youtube

import com.tube.player.net.Http
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

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())
            .header("User-Agent", Http.USER_AGENT)
        request.headers().forEach { (name, values) ->
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
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val text = response.body.string()
            return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
        }
    }
}
