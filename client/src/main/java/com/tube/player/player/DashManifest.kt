@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/** Одна дорожка для MPD: отдельный файл (видео или звук) с диапазонами init/index внутри. */
data class Rendition(
    val id: String,
    val video: Boolean,
    val url: String,
    val mime: String,
    val codecs: String,
    val bandwidth: Int,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val initStart: Int,
    val initEnd: Int,
    val indexStart: Int,
    val indexEnd: Int,
    val language: String? = null,
    val sampleRate: Int = 0,
    val channels: Int = 2,
) {
    /** Семейство кодека: дорожки одного семейства плеер переключает на лету (ABR). */
    val family: String get() = mime + ":" + codecs.substringBefore('.')
}

/**
 * Один DASH-манифест на всё видео: AdaptationSet на каждое семейство видеокодека (avc1, vp9) со всеми качествами
 * и звук. Media3 по нему сам выбирает качество («Авто») и даёт выбрать вручную (TrackSelectionOverride).
 * NewPipe строит манифест на каждую дорожку отдельно, тогда автоподбора качества нет.
 */
object DashManifest {

    /** Кодеки, которые берём: AV1 на многих телефонах декодируется программно и сажает батарею. */
    private val VideoFamilies = listOf("avc1", "vp9", "vp09")

    fun renditions(videoOnly: List<VideoStream>, audio: List<AudioStream>): List<Rendition> {
        val videos = videoOnly.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isVideoOnly }.mapNotNull { s ->
            val itag = s.itagItem ?: return@mapNotNull null
            val codec = itag.codec ?: s.codec ?: return@mapNotNull null
            if (VideoFamilies.none { codec.startsWith(it) }) return@mapNotNull null
            Rendition(
                id = "v${s.itag}",
                video = true,
                url = s.content,
                mime = s.format?.mimeType ?: return@mapNotNull null,
                codecs = codec,
                bandwidth = itag.bitrate.takeIf { it > 0 } ?: s.bitrate,
                width = s.width,
                height = s.height,
                fps = s.fps,
                initStart = s.initStart, initEnd = s.initEnd, indexStart = s.indexStart, indexEnd = s.indexEnd,
            )
        }
        // Дубляж: берём основную (оригинальную) дорожку, если YouTube отдал несколько языков.
        val original = audio.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { audio }
        val audios = original.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.mapNotNull { s ->
            val itag = s.itagItem ?: return@mapNotNull null
            Rendition(
                id = "a${s.itag}",
                video = false,
                url = s.content,
                mime = s.format?.mimeType ?: return@mapNotNull null,
                codecs = itag.codec ?: s.codec ?: return@mapNotNull null,
                bandwidth = itag.bitrate.takeIf { it > 0 } ?: s.averageBitrate * 1000,
                initStart = s.initStart, initEnd = s.initEnd, indexStart = s.indexStart, indexEnd = s.indexEnd,
                language = s.audioLocale?.toLanguageTag(),
                sampleRate = itag.sampleRate,
                channels = itag.audioChannels.takeIf { it > 0 } ?: 2,
            )
        }
        return (videos + audios).filter { it.indexEnd > 0 && it.initEnd > 0 && it.indexStart > it.initEnd - 1 }
            .distinctBy { it.id }
    }

    /** MPD или null, если не хватает дорожек с индексами (тогда — обычный поток со звуком). */
    fun build(renditions: List<Rendition>, durationSeconds: Double): String? {
        val videos = renditions.filter { it.video }
        val audios = renditions.filter { !it.video }
        if (videos.isEmpty() || audios.isEmpty() || durationSeconds <= 0) return null
        val out = StringBuilder()
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT""")
            .append("%.3f".format(java.util.Locale.ROOT, durationSeconds)).append("""S">""").append('\n')
        out.append("<Period>\n")
        var setId = 0
        videos.groupBy { it.family }.forEach { (_, group) ->
            out.append("""<AdaptationSet id="${setId++}" contentType="video" mimeType="${group.first().mime}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""").append('\n')
            group.sortedBy { it.bandwidth }.forEach { r -> representation(out, r) }
            out.append("</AdaptationSet>\n")
        }
        audios.groupBy { it.family + "|" + it.language }.forEach { (_, group) ->
            val lang = group.first().language?.let { """ lang="${xml(it)}"""" }.orEmpty()
            out.append("""<AdaptationSet id="${setId++}" contentType="audio" mimeType="${group.first().mime}"$lang subsegmentAlignment="true" subsegmentStartsWithSAP="1">""").append('\n')
            group.sortedBy { it.bandwidth }.forEach { r -> representation(out, r) }
            out.append("</AdaptationSet>\n")
        }
        out.append("</Period>\n</MPD>\n")
        return out.toString()
    }

    private fun representation(out: StringBuilder, r: Rendition) {
        out.append("""<Representation id="${xml(r.id)}" codecs="${xml(r.codecs)}" bandwidth="${r.bandwidth.coerceAtLeast(1)}"""")
        if (r.video) {
            out.append(""" width="${r.width}" height="${r.height}"""")
            if (r.fps > 0) out.append(""" frameRate="${r.fps}"""")
        } else if (r.sampleRate > 0) {
            out.append(""" audioSamplingRate="${r.sampleRate}"""")
        }
        out.append(">\n")
        if (!r.video) {
            out.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${r.channels}"/>""").append('\n')
        }
        out.append("<BaseURL>").append(xml(r.url)).append("</BaseURL>\n")
        out.append("""<SegmentBase indexRange="${r.indexStart}-${r.indexEnd}"><Initialization range="${r.initStart}-${r.initEnd}"/></SegmentBase>""").append('\n')
        out.append("</Representation>\n")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
