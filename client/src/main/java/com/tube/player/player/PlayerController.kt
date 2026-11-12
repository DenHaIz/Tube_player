@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tube.player.data.Library
import com.tube.player.data.PlayerSettings
import com.tube.player.sponsorblock.ReturnDislike
import com.tube.player.sponsorblock.Segment
import com.tube.player.sponsorblock.SegmentAction
import com.tube.player.sponsorblock.SponsorBlock
import com.tube.player.youtube.Repository
import com.tube.player.youtube.VideoDetails
import com.tube.player.youtube.VideoItem
import com.tube.player.youtube.videoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Состояние плеера для экранов. */
data class PlaybackState(
    /** Что открыто: сразу из карточки (название, превью), потом — полные данные. */
    val item: VideoItem? = null,
    val details: VideoDetails? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Доступные качества (высота кадра), по убыванию. */
    val qualities: List<Int> = emptyList(),
    /** Выбранное вручную; null — «Авто». */
    val selectedHeight: Int? = null,
    val currentHeight: Int = 0,
    val currentFps: Int = 0,
    val speed: Float = 1f,
    val segments: List<Segment> = emptyList(),
    /** Отрезок под кнопкой «Пропустить». */
    val skipButton: Segment? = null,
    /** Только что пропущенный отрезок — подсказка с «Отменить». */
    val skipped: Segment? = null,
    val dislikes: Long? = null,
    val kind: SourceKind? = null,
    val popup: Boolean = false,
)

/**
 * Один плеер на всё приложение (ExoPlayer живёт дольше экранов: фон, PiP, плавающее окно).
 * Видео ищется через NewPipe, играет через наш DASH-манифест, данные — через byedpi.
 * Все методы — с главного потока.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val library: Library,
    private val sponsorBlock: SponsorBlock,
    private val returnDislike: ReturnDislike,
    private val dataSource: DataSource.Factory,
    private val settings: () -> PlayerSettings,
    /** Предел качества для текущей сети (Wi-Fi / мобильная). */
    private val qualityCap: () -> Int,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        // Старт после ~1 с буфера (по умолчанию 2,5 с), после остановки — 2 с; дальше копим до 50 с.
        .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val ignoredSegments = mutableSetOf<String>()
    private var retried = false

    /** Плавающее окно; создаётся при первом показе. */
    var popup: PopupPlayer? = null
        private set
    /** Развернуть плавающее окно обратно в приложение. */
    var onExpandPopup: () -> Unit = {}

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = updateTracks(tracks)

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _state.update { it.copy(currentHeight = player.videoFormat?.height ?: it.currentHeight, currentFps = player.videoFormat?.frameRate?.toInt() ?: 0) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
            }

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
    }

    /** Открыть видео по ссылке; [item] — данные карточки, чтобы сразу показать название и превью. */
    fun open(url: String, item: VideoItem? = null) {
        val id = videoId(url)
        if (id != null && _state.value.item?.id == id && _state.value.error == null && player.playbackState != Player.STATE_IDLE) {
            if (!player.isPlaying) player.play()
            return
        }
        savePosition()
        loadJob?.cancel()
        ignoredSegments.clear()
        retried = false
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState(item = item ?: id?.let { VideoItem(url, "") }, loading = true, speed = initialSpeed(), popup = _state.value.popup)
        connectSession()
        loadJob = scope.launch {
            val details = runCatching { repo.video(url) }.getOrElse { e ->
                _state.update { it.copy(loading = false, error = describe(e)) }
                return@launch
            }
            prepare(details, startMs = library.position(details.item.id).takeIf { it > 10_000 } ?: 0)
            library.watched(details.item, durationMs = details.item.duration * 1000)
            launch { loadExtras(details) }
        }
    }

    private fun prepare(details: VideoDetails, startMs: Long) {
        val (source, kind) = runCatching { MediaSources.create(details, dataSource, qualityCap()) }.getOrElse { e ->
            _state.update { it.copy(details = details, item = details.item, loading = false, error = describe(e)) }
            return
        }
        applyQualityCap()
        player.setMediaSource(source, startMs)
        player.setPlaybackSpeed(_state.value.speed)
        player.prepare()
        player.play()
        _state.update { it.copy(details = details, item = details.item, loading = false, error = null, kind = kind) }
        startTicker()
    }

    /** SponsorBlock и дизлайки — необязательные, грузятся после старта видео. */
    private suspend fun loadExtras(details: VideoDetails) {
        val s = settings()
        if (s.sponsorBlock && !details.item.live) {
            runCatching { sponsorBlock.segments(details.item.id) }.onSuccess { segments ->
                if (_state.value.item?.id == details.item.id) _state.update { it.copy(segments = segments) }
            }
        }
        if (s.returnDislikes) {
            returnDislike.votes(details.item.id)?.let { votes ->
                if (_state.value.item?.id == details.item.id) _state.update { it.copy(dislikes = votes.dislikes.takeIf { d -> d >= 0 }) }
            }
        }
    }

    /** Раз в 250 мс: пропуск отрезков SponsorBlock, кнопка «Пропустить», сохранение позиции. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(250)
                val state = _state.value
                if (state.segments.isNotEmpty()) {
                    val actions = settings().sponsor
                    val position = player.currentPosition
                    SponsorBlock.toSkip(state.segments, position, actions, ignoredSegments)?.let { segment ->
                        ignoredSegments += segment.uuid
                        player.seekTo(segment.endMs)
                        _state.update { it.copy(skipped = segment) }
                        scope.launch {
                            delay(4000)
                            _state.update { if (it.skipped == segment) it.copy(skipped = null) else it }
                        }
                    }
                    val button = SponsorBlock.forButton(state.segments, position, actions)
                    if (button != state.skipButton) _state.update { it.copy(skipButton = button) }
                }
                if (++ticks % 20 == 0 && player.isPlaying) savePosition()
            }
        }
    }

    fun skip(segment: Segment) {
        ignoredSegments += segment.uuid
        player.seekTo(segment.endMs)
        _state.update { it.copy(skipButton = null) }
    }

    /** «Отменить» после автоматического пропуска: вернуться к началу отрезка, больше его не пропускать. */
    fun undoSkip() {
        val segment = _state.value.skipped ?: return
        player.seekTo(segment.startMs)
        _state.update { it.copy(skipped = null) }
    }

    private fun updateTracks(tracks: Tracks) {
        val heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
            .filter { it > 0 }.distinct().sortedDescending()
        _state.update { it.copy(qualities = heights, currentHeight = player.videoFormat?.height ?: it.currentHeight) }
    }

    /** Качество: null — «Авто» (с пределом из настроек), иначе — конкретная высота кадра. */
    fun setQuality(height: Int?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height == null) {
            val cap = qualityCap()
            builder.setMaxVideoSize(Int.MAX_VALUE, cap)
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            // Сначала группа (кодек), что играет сейчас, — без смены декодера.
            val ordered = groups.sortedByDescending { it.isSelected }
            for (group in ordered) {
                val index = (0 until group.length).firstOrNull { group.isTrackSupported(it) && group.getTrackFormat(it).height == height } ?: continue
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                break
            }
        }
        player.trackSelectionParameters = builder.build()
        _state.update { it.copy(selectedHeight = height) }
    }

    private fun applyQualityCap() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, qualityCap())
            .build()
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    private fun initialSpeed(): Float = settings().let { if (it.rememberSpeed) it.speed else 1f }

    /** Ссылки googlevideo живут несколько часов: 403 — заново получить ссылки и продолжить с того же места. */
    private fun onError(error: PlaybackException) {
        val cause = error.cause
        val details = _state.value.details
        if (!retried && details != null && cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode in setOf(403, 410)) {
            retried = true
            val position = player.currentPosition
            loadJob = scope.launch {
                val fresh = runCatching { repo.video(details.item.url, fresh = true) }.getOrNull()
                if (fresh != null) prepare(fresh, position) else _state.update { it.copy(error = describe(error)) }
            }
            return
        }
        _state.update { it.copy(error = describe(error)) }
    }

    fun retry() {
        val item = _state.value.item ?: return
        _state.update { it.copy(error = null) }
        val position = player.currentPosition
        val details = _state.value.details
        if (details?.info != null && player.playerError != null) {
            prepare(details, position)
        } else {
            _state.value = PlaybackState(item = item, popup = _state.value.popup)
            open(item.url, item)
        }
    }

    fun savePosition() {
        val item = _state.value.item ?: return
        if (item.live || player.duration <= 0) return
        library.savePosition(item.id, player.currentPosition, player.duration)
    }

    /** Закрыть видео совсем: остановить, убрать уведомление и плавающее окно. */
    fun close() {
        savePosition()
        loadJob?.cancel()
        tickJob?.cancel()
        hidePopup()
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    /** Подключиться к сервису: он держит MediaSession, уведомление и фоновое воспроизведение. */
    private fun connectSession() {
        if (controller != null) return
        controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }

    /**
     * Плавающее окно. Порядок важен: сначала экран отдаёт поверхность (popup = true → вместо видео обложка),
     * через кадр окно забирает плеер себе и проявляется по первому кадру; [onShown] — когда окно уже на экране
     * (тогда приложение можно убирать в фон без белой вспышки и застывшей картинки).
     */
    fun showPopup(onShown: () -> Unit = {}): Boolean {
        if (!PopupPlayer.canShow(context)) return false
        val window = popup ?: PopupPlayer(context, player, onExpand = { onExpandPopup() }, onClose = { close() }).also { popup = it }
        _state.update { it.copy(popup = true) }
        scope.launch {
            delay(POPUP_HANDOVER_MS)
            window.show()
            if (!player.isPlaying) player.play()
            delay(POPUP_HANDOVER_MS)
            onShown()
        }
        return true
    }

    fun hidePopup() {
        popup?.hide()
        _state.update { it.copy(popup = false) }
    }

    /** Для скриншотов: показать состояние без загрузки видео. */
    internal fun preview(state: PlaybackState) {
        _state.value = state
    }

    private fun describe(e: Throwable): String = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName
}

private const val POPUP_HANDOVER_MS = 120L

/** Подписи качества: «1080p», «720p60». */
fun qualityLabel(height: Int, fps: Int = 0) = if (fps > 30) "${height}p$fps" else "${height}p"

/** Действие SponsorBlock, при котором отрезок виден на полосе. */
val SegmentAction.visible: Boolean get() = this != SegmentAction.Off
