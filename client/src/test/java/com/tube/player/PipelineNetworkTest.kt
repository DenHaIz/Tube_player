package com.tube.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.tube.player.net.Http
import com.tube.player.player.DashManifest
import com.tube.player.player.YoutubeDataSource
import com.tube.player.youtube.Kiosk
import com.tube.player.youtube.NewPipeDownloader
import com.tube.player.youtube.Repository
import com.tube.player.youtube.ChannelListing
import com.tube.player.youtube.ChannelSort
import com.tube.player.youtube.DateRange
import com.tube.player.youtube.NewPipeBrowse
import com.tube.player.youtube.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * «Трубопровод» плеера через настоящий интернет: NewPipe и видеопотоки идут через SOCKS5 (здесь — ретранслятор
 * вместо byedpi). Обход DPI тут не проверяется: сервер сборки в Финляндии.
 * Запуск: gradlew :client:testDebugUnitTest -Pnetwork --tests "*PipelineNetworkTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipelineNetworkTest {

    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    companion object {
        private lateinit var relay: SocksRelay
        private lateinit var repo: Repository
        private val client by lazy { Http.client({ relay.port }) }

        @BeforeClass @JvmStatic
        fun setUp() {
            if (System.getProperty("network") != "true") return
            relay = SocksRelay()
            repo = Repository(NewPipeDownloader { client }, "en", "US")
        }

        @AfterClass @JvmStatic
        fun tearDown() {
            if (::relay.isInitialized) relay.close()
        }

        /** «Me at the zoo» — первое видео YouTube, никуда не денется. */
        const val STABLE = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }

    @Test
    fun searchAndKioskThroughProxy() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val found = repo.search("lofi hip hop radio").items.filterIsInstance<VideoItem>()
        println("search: ${found.size} videos, first: ${found.firstOrNull()?.title}")
        assertTrue(found.isNotEmpty())
        val music = runCatching { repo.kiosk(Kiosk.Music).items }.onFailure { println("kiosk music: $it") }.getOrDefault(emptyList())
        println("kiosk music: ${music.size}")
        val suggestions = repo.suggestions("lofi")
        println("suggestions: $suggestions")
        assertTrue("youtube via proxy: ${relay.hosts}", relay.hosts.any { it.endsWith("youtube.com") })
    }

    @Test
    fun streamsDownloadThroughProxy() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val details = repo.video(STABLE)
        val info = details.info!!
        println("video: ${details.item.title}, ${info.videoOnlyStreams.size} video-only, ${info.audioStreams.size} audio, related ${details.related.size}")
        val renditions = DashManifest.renditions(info.videoOnlyStreams, info.audioStreams)
        renditions.forEach { println("  ${it.id} ${it.mime} ${it.codecs} ${it.height}p ${it.bandwidth}") }
        assertTrue(renditions.any { it.video } && renditions.any { !it.video })

        val source = YoutubeDataSource.factory(Http.mediaClient(client)).createDataSource()
        for (r in listOf(renditions.first { it.video }, renditions.first { !it.video })) {
            val want = 256 * 1024L
            val read = try {
                source.open(DataSpec.Builder().setUri(Uri.parse(r.url)).setPosition(0).setLength(want).build())
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    total += n
                }
                total
            } finally {
                source.close()
            }
            println("${r.id}: $read B")
            // Короткий звук бывает меньше 256 КБ целиком.
            assertTrue(r.id, read >= 64 * 1024)
        }
        assertTrue(relay.hosts.any { it.endsWith("googlevideo.com") })
    }

    /**
     * Проверка обхода видеопотоком: ссылка через NewPipe, затем запрос тестера (POST `x\0`, свой TLS, через SOCKS)
     * получает ≥ 128 КБ — так же, как сторож и автоподбор проверяют стратегию.
     */
    @Test
    fun videoProbeDownloadsStream() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val probe = com.tube.player.net.VideoProbe(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), { "test" }) { repo }.get()
        println("video probe: ${probe?.host} post=${probe?.post} ua=${probe?.headers?.get("User-Agent")}")
        assertTrue(probe != null && probe.host.endsWith(".googlevideo.com") && probe.minBytes >= 128 * 1024)
        val plan = com.tube.strategy.ProbePlan.app { probe }
        val probes = plan.full(null)
        val started = System.nanoTime()
        val bytes = com.tube.byedpi.StrategyTester.httpsGet(relay.port, probe!!.host, probe.path, probe.minBytes, 7000, probe.post, probe.headers)
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1).lineSequence().first()
        println("stream probe: ${bytes.size} B in ${(System.nanoTime() - started) / 1_000_000} ms, $head; plan: ${probes.map { it.host }}")
        assertTrue(head, head.contains(" 200 ") || head.contains(" 206 "))
        assertTrue(bytes.size >= probe.minBytes)
        // Остальные проверки плана тоже проходят через прокси.
        for (p in probes.filter { it !== probe }) {
            val n = com.tube.byedpi.StrategyTester.httpsGet(relay.port, p.host, p.path, p.minBytes, 7000).size
            println("  ${p.host}${p.path.take(30)}: $n B (need ${p.minBytes})")
            assertTrue(p.host, n >= p.minBytes)
        }
    }

    /** Сортировка канала чипами YouTube и период через поиск по каналу с датами (Fireship, канал с 2017 года). */
    @Test
    fun channelSortAndPeriod() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val id = "UCsBjURrPoezykLs9EqgamOA"
        val popular = ChannelListing(NewPipeBrowse, id, ChannelSort.Popular, DateRange())
        val pop = popular.first().items.filterIsInstance<VideoItem>()
        println("popular: ${pop.take(3).map { it.title + " " + it.views }}")
        assertTrue(pop.size >= 10 && pop.first().views > 1_000_000)
        assertTrue(popular.more().items.isNotEmpty())
        val oldest = ChannelListing(NewPipeBrowse, id, ChannelSort.Oldest, DateRange()).first().items.filterIsInstance<VideoItem>()
        println("oldest: ${oldest.take(3).map { it.title + " " + it.uploaded }}")
        assertTrue(oldest.isNotEmpty() && oldest.first().uploadedAt < System.currentTimeMillis() - 5L * 365 * 86_400_000)

        // Март–апрель 2019: «Svelte 3 Reaction» — 25 апреля, «Predict Horse Races with BigQuery» — 15 марта.
        val range = DateRange(java.time.LocalDate.of(2019, 3, 1), java.time.LocalDate.of(2019, 4, 30))
        val newest = ChannelListing(NewPipeBrowse, id, ChannelSort.Newest, range)
        val all = ArrayList(newest.first().items.filterIsInstance<VideoItem>())
        repeat(3) { all += newest.more().items.filterIsInstance<VideoItem>() }
        val titles = all.map { it.title }
        println("2019-03..04 newest: ${titles.size} $titles")
        val svelte = titles.indexOfFirst { it.startsWith("Svelte 3") }
        val horses = titles.indexOfFirst { it.startsWith("Predict Horse Races") }
        assertTrue("апрель раньше марта: $svelte < $horses", svelte in 0 until horses)
        val old = ChannelListing(NewPipeBrowse, id, ChannelSort.Oldest, range).first().items.map { (it as VideoItem).title }
        println("2019-03..04 oldest: $old")
        val oldHorses = old.indexOfFirst { it.startsWith("Predict Horse Races") }
        val oldSvelte = old.indexOfFirst { it.startsWith("Svelte 3") }.let { if (it < 0) Int.MAX_VALUE else it }
        assertTrue("старые — с марта: $oldHorses < $oldSvelte", oldHorses in 0 until oldSvelte)
        // Части по 10 дней: 15 марта раньше 25 марта («RxJS Top Ten»).
        assertTrue("15.03 раньше 25.03", oldHorses < old.indexOfFirst { it.startsWith("RxJS Top Ten") })
        val popRange = ChannelListing(NewPipeBrowse, id, ChannelSort.Popular, range).first().items.filterIsInstance<VideoItem>()
        println("2019-03..04 popular: ${popRange.take(3).map { it.title + " " + it.views }}")
        assertTrue(popRange.zipWithNext().all { (a, b) -> a.views >= b.views })
        assertTrue(popRange.map { it.title }.toSet() == titles.toSet() || popRange.size >= titles.size)
    }

    @Test
    fun commentsLoadWithRepliesPage() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val first = repo.comments(STABLE)
        println("comments: count ${first.count}, page ${first.items.size}, first: ${first.items.firstOrNull()?.plain?.take(60)}")
        assertTrue(first.items.isNotEmpty())
        val more = first.next?.let { repo.comments(STABLE, it) }
        println("comments page 2: ${more?.items?.size}")
        assertTrue(more == null || more.items.isNotEmpty())
    }

    @Test
    fun sponsorBlockAndDislikesWork() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val segments = com.tube.player.sponsorblock.SponsorBlock { client }.segments("eXjGWlJOhWg")
        println("SponsorBlock: $segments")
        assertTrue(segments.isNotEmpty())
        val votes = com.tube.player.sponsorblock.ReturnDislike { client }.votes("dQw4w9WgXcQ")
        println("RYD: $votes")
        assertTrue(votes != null && votes.dislikes > 0)
    }

    @Test
    fun exoPlayerPlaysDashThroughProxy() {
        assumeTrue(System.getProperty("network") == "true")
        val info = runBlocking { repo.video(STABLE).info!! }
        // Декодеры Robolectric знают H.264 и AAC — для проверки берём их.
        val renditions = DashManifest.renditions(info.videoOnlyStreams, info.audioStreams)
            .filter { if (it.video) it.codecs.startsWith("avc1") else it.codecs.startsWith("mp4a") }
        val mpd = DashManifest.build(renditions, info.duration.toDouble())!!
        val manifest = DashManifestParser().parse(Uri.parse(STABLE), mpd.byteInputStream())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = ExoPlayer.Builder(context).build()
        try {
            val factory = YoutubeDataSource.factory(Http.mediaClient(client))
            player.setMediaSource(DashMediaSource.Factory(factory).createMediaSource(manifest, MediaItem.fromUri(STABLE)))
            player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onLoadError(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                    mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                    error: java.io.IOException,
                    wasCanceled: Boolean,
                ) = println("load error: $error")
                override fun onPlaybackStateChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) =
                    println("state: $state")
            })
            player.prepare()
            player.play()
            // Сеть настоящая — ждём по реальному времени, прокручивая главный поток.
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && player.currentPosition < 2_000 && player.playerError == null) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                Thread.sleep(20)
            }
            println("state ${player.playbackState}, buffered ${player.bufferedPosition}, error ${player.playerError}")
            println("ExoPlayer: position ${player.currentPosition} ms, video ${player.videoFormat?.height}p ${player.videoFormat?.codecs}, audio ${player.audioFormat?.codecs}")
            assertTrue(player.currentPosition >= 2_000)
        } finally {
            player.release()
        }
    }
}
