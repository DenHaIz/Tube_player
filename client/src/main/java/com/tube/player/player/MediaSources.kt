@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tube.player.youtube.VideoDetails
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/** Как будет играть видео — для отладки и журнала. */
enum class SourceKind { Dash, Merged, Progressive, Hls, LiveDash }

/**
 * Источник Media3 для видео:
 * - обычное видео — наш DASH-манифест со всеми качествами (автоподбор и ручной выбор);
 * - нет индексов — видео без звука + звук (MergingMediaSource) лучшего качества;
 * - совсем старые ролики — обычный файл со звуком;
 * - прямой эфир — HLS (или DASH от YouTube).
 */
object MediaSources {

    fun create(details: VideoDetails, factory: DataSource.Factory, maxHeight: Int): Pair<MediaSource, SourceKind> {
        val info = details.info ?: error("no stream info")
        val item = mediaItem(details)
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            info.hlsUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                return HlsMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(url).build()) to SourceKind.Hls
            }
            info.dashMpdUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                return DashMediaSource.Factory(factory).createMediaSource(item.buildUpon().setUri(url).build()) to SourceKind.LiveDash
            }
        }
        val renditions = DashManifest.renditions(info.videoOnlyStreams, info.audioStreams)
        DashManifest.build(renditions, duration(info))?.let { mpd ->
            val manifest = DashManifestParser().parse(Uri.parse(info.url), mpd.byteInputStream())
            return DashMediaSource.Factory(factory).createMediaSource(manifest, item) to SourceKind.Dash
        }
        val progressive = ProgressiveMediaSource.Factory(factory)
        val video = info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height <= maxHeight }
            .maxByOrNull { it.height }
        val audio = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.averageBitrate }
        if (video != null && audio != null) {
            return MergingMediaSource(
                progressive.createMediaSource(item.buildUpon().setUri(video.content).build()),
                progressive.createMediaSource(MediaItem.fromUri(audio.content)),
            ) to SourceKind.Merged
        }
        val muxed = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.height }
            ?: error("no playable streams")
        return progressive.createMediaSource(item.buildUpon().setUri(muxed.content).build()) to SourceKind.Progressive
    }

    /** Длительность: из дорожек точнее, чем округлённые секунды страницы. */
    private fun duration(info: StreamInfo): Double {
        val approx = (info.videoOnlyStreams.mapNotNull { it.itagItem?.approxDurationMs } + info.audioStreams.mapNotNull { it.itagItem?.approxDurationMs })
            .filter { it > 0 }.maxOrNull()
        return approx?.let { it / 1000.0 } ?: info.duration.toDouble()
    }

    /** Описание для уведомления, экрана блокировки и Bluetooth. */
    fun mediaItem(details: VideoDetails): MediaItem = MediaItem.Builder()
        .setMediaId(details.item.id)
        .setUri(details.item.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(details.item.title)
                .setArtist(details.item.channel)
                .setArtworkUri(details.item.thumbnail?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .build(),
        )
        .build()
}
