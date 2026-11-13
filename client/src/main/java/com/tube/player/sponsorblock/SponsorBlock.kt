package com.tube.player.sponsorblock

import com.tube.player.net.limitedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Что делать с отрезком категории. */
enum class SegmentAction { Skip, Button, Show, Off }

/** Категории SponsorBlock (sponsor.ajay.app). Названия — коротко, для настроек и подсказки «Пропущено». */
enum class SponsorCategory(val key: String, val ru: String, val en: String, val color: Long, val default: SegmentAction) {
    Sponsor("sponsor", "Реклама спонсора", "Sponsor", 0xFF00D400, SegmentAction.Skip),
    SelfPromo("selfpromo", "Самореклама", "Self-promotion", 0xFFFFFF00, SegmentAction.Skip),
    Interaction("interaction", "Просьба подписаться", "Interaction reminder", 0xFFCC00FF, SegmentAction.Skip),
    Intro("intro", "Заставка", "Intro", 0xFF00FFFF, SegmentAction.Button),
    Outro("outro", "Концовка", "Endcards", 0xFF0202ED, SegmentAction.Button),
    Preview("preview", "Анонс", "Preview", 0xFF008FD6, SegmentAction.Show),
    Filler("filler", "Не по теме", "Filler", 0xFF7300FF, SegmentAction.Show),
    MusicOffTopic("music_offtopic", "Не музыка", "Non-music", 0xFFFF9900, SegmentAction.Skip),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Отрезок видео в миллисекундах. */
data class Segment(val category: SponsorCategory, val startMs: Long, val endMs: Long, val uuid: String)

/**
 * Клиент SponsorBlock. Запрос по префиксу SHA-256 от id видео (4 символа): сервер не узнаёт, какое видео смотрят,
 * а мы выбираем своё из ответа.
 */
class SponsorBlock(private val client: () -> OkHttpClient) {
    private val cache = ConcurrentHashMap<String, List<Segment>>()

    suspend fun segments(videoId: String): List<Segment> {
        cache[videoId]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            val prefix = sha256(videoId).take(4)
            val categories = SponsorCategory.entries.joinToString(",", "[", "]") { "\"${it.key}\"" }
            val url = "$API/api/skipSegments/$prefix?categories=" + java.net.URLEncoder.encode(categories, "UTF-8")
            client().newCall(Request.Builder().url(url).build()).execute().use { response ->
                when (response.code) {
                    200 -> parse(response.body.limitedString(1L shl 20), videoId)
                    404 -> emptyList()
                    else -> error("SponsorBlock HTTP ${response.code}")
                }
            }
        }
        cache[videoId] = result
        return result
    }

    companion object {
        const val API = "https://sponsor.ajay.app"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Ответ API на префикс: массив видео с отрезками. */
        fun parse(json: String, videoId: String): List<Segment> {
            val videos = JSONArray(json)
            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                if (video.optString("videoID") != videoId) continue
                val segments = video.optJSONArray("segments") ?: return emptyList()
                return (0 until segments.length()).mapNotNull { j ->
                    val s = segments.getJSONObject(j)
                    if (s.optString("actionType", "skip") !in setOf("skip", "mute")) return@mapNotNull null
                    val category = SponsorCategory.of(s.optString("category")) ?: return@mapNotNull null
                    val range = s.getJSONArray("segment")
                    val start = (range.getDouble(0) * 1000).toLong()
                    val end = (range.getDouble(1) * 1000).toLong()
                    if (end - start < 500) return@mapNotNull null
                    Segment(category, start, end, s.optString("UUID"))
                }.sortedBy { it.startMs }
            }
            return emptyList()
        }

        /**
         * Отрезок, который пора пропустить в позиции [positionMs]: начался не дальше, чем [windowMs] назад
         * (если пользователь сам перемотал внутрь отрезка, не выкидываем его обратно), и не был отменён.
         */
        fun toSkip(
            segments: List<Segment>,
            positionMs: Long,
            actions: Map<SponsorCategory, SegmentAction>,
            ignored: Set<String>,
            windowMs: Long = 1500,
        ): Segment? = segments.firstOrNull { s ->
            actions[s.category] == SegmentAction.Skip && s.uuid !in ignored &&
                positionMs >= s.startMs && positionMs < s.endMs - 300 && positionMs - s.startMs <= windowMs
        }

        /** Отрезок под кнопкой «Пропустить» (действие Button) в позиции [positionMs]. */
        fun forButton(segments: List<Segment>, positionMs: Long, actions: Map<SponsorCategory, SegmentAction>): Segment? =
            segments.firstOrNull { s ->
                actions[s.category] == SegmentAction.Button && positionMs >= s.startMs && positionMs < s.endMs - 300
            }
    }
}
