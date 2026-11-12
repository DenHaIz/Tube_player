package com.tube.player.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Лайки и дизлайки видео. */
data class Votes(val likes: Long, val dislikes: Long)

/** Return YouTube Dislike (returnyoutubedislikeapi.com) — оценка дизлайков, как в ReVanced/Morphe. */
class ReturnDislike(private val client: () -> OkHttpClient) {
    suspend fun votes(videoId: String): Votes? = withContext(Dispatchers.IO) {
        runCatching {
            client().newCall(Request.Builder().url("$API/votes?videoId=$videoId").build()).execute().use { r ->
                if (r.code != 200) return@use null
                parse(r.body.string())
            }
        }.getOrNull()
    }

    companion object {
        const val API = "https://returnyoutubedislikeapi.com"

        fun parse(json: String): Votes = JSONObject(json).let { Votes(it.optLong("likes", -1), it.optLong("dislikes", -1)) }
    }
}
