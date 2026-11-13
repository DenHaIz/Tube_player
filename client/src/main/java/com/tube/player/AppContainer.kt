@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.tube.player

import android.content.Context
import androidx.media3.datasource.DataSource
import com.tube.player.account.AccountStore
import com.tube.player.account.InnerTube
import com.tube.player.data.Library
import com.tube.player.data.PlayerSettings
import com.tube.player.data.SettingsRepository
import com.tube.player.net.BypassController
import com.tube.player.net.BypassProxy
import com.tube.player.net.Http
import com.tube.player.net.VideoProbe
import com.tube.player.player.PlayerController
import com.tube.player.player.YoutubeDataSource
import com.tube.player.sponsorblock.ReturnDislike
import com.tube.player.sponsorblock.SponsorBlock
import com.tube.player.youtube.NewPipeDownloader
import com.tube.player.youtube.Repository
import com.tube.strategy.AutoPick
import com.tube.strategy.NetworkKind
import com.tube.strategy.NetworkMonitor
import com.tube.strategy.ProbePlan
import com.tube.strategy.ResultsStore
import com.tube.update.UpdateChecker
import com.tube.update.UpdateSource
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Ручной DI, как в Tube: зависимости создаются лениво, при первом обращении. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** Настройки на весь процесс; null — ещё не прочитаны (первый кадр). */
    val settings: StateFlow<PlayerSettings?> by lazy { settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null) }
    private val current: PlayerSettings get() = settings.value ?: PlayerSettings()

    val network: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val results: ResultsStore by lazy { ResultsStore(appContext) }
    /** Проверка обхода настоящим видеопотоком (ссылка — через NewPipe). */
    val videoProbe: VideoProbe by lazy { VideoProbe(scope, { network.current.value?.key }) { repository } }
    val autoPick: AutoPick by lazy { AutoPick(appContext, settingsRepository, results, network, scope, ProbePlan.app { videoProbe.get() }) }
    val proxy: BypassProxy by lazy { BypassProxy(appContext, scope) }
    val bypass: BypassController by lazy { BypassController(appContext, settingsRepository, proxy, network, results, autoPick, scope) }

    /** Один OkHttp на всё: NewPipe, превью, SponsorBlock, InnerTube — через byedpi. */
    val http: OkHttpClient by lazy {
        Http.client(port = { proxy.port }, adBlock = { current.adBlock }, awaitReady = { bypass.awaitReady(it) }, onTrouble = { bypass.reportTrouble() })
    }
    val mediaDataSource: DataSource.Factory by lazy { YoutubeDataSource.factory(Http.mediaClient(http)) }

    val repository: Repository by lazy {
        val lang = current.language
        Repository(NewPipeDownloader { http }, if (lang == AppLanguage.English) "en" else "ru", "RU")
    }
    val library: Library by lazy { Library(appContext) }
    val sponsorBlock: SponsorBlock by lazy { SponsorBlock { http } }
    val returnDislike: ReturnDislike by lazy { ReturnDislike { http } }
    val accounts: AccountStore by lazy { AccountStore(appContext) }
    val updates: UpdateChecker by lazy {
        UpdateChecker(
            appContext,
            UpdateSource("DenHaIz/Tube_player", "TubePlayer-", BuildConfig.VERSION_NAME, "TubePlayer/${BuildConfig.VERSION_NAME}"),
            settingsRepository,
        )
    }
    val innerTube: InnerTube by lazy { InnerTube({ http }) { accounts.account.value } }

    /** Плеер создаётся на главном потоке (ExoPlayer привязан к нему). */
    val playback: PlayerController by lazy {
        PlayerController(
            appContext, mainScope, repository, library, sponsorBlock, returnDislike, mediaDataSource,
            settings = { current },
            qualityCap = {
                val s = current
                if (network.current.value?.kind == NetworkKind.Cellular) s.qualityMobile.height else s.qualityWifi.height
            },
            onNetworkTrouble = { bypass.reportTrouble() },
        )
    }

    /** Запуск обхода; смена порта byedpi сбрасывает пул соединений — новые пойдут через новый прокси. */
    fun start() {
        bypass.httpClient = { http }
        bypass.start()
        scope.launch {
            proxy.endpoint.map { it?.port }.distinctUntilChanged().drop(1).collect { http.connectionPool.evictAll() }
        }
        scope.launch {
            settings.map { it?.language }.distinctUntilChanged().collect { lang ->
                if (lang != null) repository.setLocale(if (lang == AppLanguage.English) "en" else "ru", "RU")
            }
        }
    }
}
