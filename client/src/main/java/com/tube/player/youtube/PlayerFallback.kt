package com.tube.player.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter

/**
 * Запасные клиенты YouTube для запроса плеера. NewPipe 0.27 спрашивает плеер только клиентом VISIONOS; если YouTube
 * отвечает ему «Войдите, чтобы подтвердить, что вы не бот» (LOGIN_REQUIRED), тот же запрос повторяется клиентом iOS,
 * затем Android VR — у них ссылки на потоки тоже прямые, а проверка «не бот» срабатывает по-разному (зависит от
 * клиента, видео и адреса). Ответ подставляется NewPipe вместо отказа: формат тот же (`streamingData`).
 * Подмена — в [NewPipeDownloader], только для `youtubei/v1/player` клиента VISIONOS.
 */
object PlayerFallback {
    data class Client(val name: String, val id: Int, val version: String, val userAgent: String, val fields: Map<String, Any>)

    val Ios = Client(
        "IOS", 5, "20.03.02",
        "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)",
        mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.2.1.22C161"),
    )
    val AndroidVr = Client(
        "ANDROID_VR", 28, "1.65.10",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L", "androidSdkVersion" to 32),
    )
    val Clients = listOf(Ios, AndroidVr)

    /** Клиент ссылки на поток по параметру `c=` (IOS, ANDROID_VR) — для User-Agent загрузки. */
    fun forStreamUrl(url: String): Client? {
        val c = Regex("""[?&]c=([A-Z_]+)""").find(url)?.groupValues?.get(1) ?: return null
        return Clients.firstOrNull { it.name == c }
    }

    fun isPlayerRequest(url: String) = "/youtubei/v1/player" in url

    /** Запрос плеера от VISIONOS (только его подменяем: остальное NewPipe спрашивает как WEB). */
    fun isVisionOs(body: ByteArray?): Boolean = body != null && String(body, Charsets.UTF_8).contains("\"VISIONOS\"")

    /** Отказ «подтвердите, что вы не бот» (или иной LOGIN_REQUIRED без возрастного ограничения). */
    fun isBotCheck(response: String): Boolean {
        val status = runCatching { JsonParser.`object`().from(response).getObject("playabilityStatus") }.getOrNull() ?: return false
        if (!status.getString("status").equals("LOGIN_REQUIRED", ignoreCase = true)) return false
        val reason = status.getString("reason").orEmpty()
        return "inappropriate" !in reason // возрастное ограничение запасной клиент не снимет
    }

    fun isPlayable(response: String): Boolean = runCatching {
        val root = JsonParser.`object`().from(response)
        root.getObject("playabilityStatus").getString("status").equals("OK", ignoreCase = true) &&
            !root.getObject("streamingData").getArray("adaptiveFormats").isEmpty()
    }.getOrDefault(false)

    /** Тело запроса VISIONOS → то же для [client]: язык, страна и visitorData сохраняются, устройство — клиента. */
    fun rewrite(body: ByteArray, client: Client): ByteArray {
        val root = JsonParser.`object`().from(String(body, Charsets.UTF_8))
        val old = root.getObject("context").getObject("client")
        val fresh = JsonObject()
        for (key in listOf("hl", "gl", "visitorData", "utcOffsetMinutes", "timeZone")) old[key]?.let { fresh[key] = it }
        fresh["clientName"] = client.name
        fresh["clientVersion"] = client.version
        fresh["userAgent"] = client.userAgent
        client.fields.forEach { (k, v) -> fresh[k] = v }
        root.getObject("context")["client"] = fresh
        return JsonWriter.string(root).toByteArray(Charsets.UTF_8)
    }
}
