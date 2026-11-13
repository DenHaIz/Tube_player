package com.tube.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.grack.nanojson.JsonParser
import com.tube.player.account.Account
import com.tube.player.account.InnerTube
import com.tube.player.account.SapisidHash
import com.tube.player.adblock.AdBlock
import com.tube.player.data.Library
import com.tube.player.player.DashManifest
import com.tube.player.player.Rendition
import com.tube.player.player.YoutubeDataSource
import com.tube.player.sponsorblock.ReturnDislike
import com.tube.player.sponsorblock.Segment
import com.tube.player.sponsorblock.SegmentAction
import com.tube.player.sponsorblock.SponsorBlock
import com.tube.player.sponsorblock.SponsorCategory
import com.tube.player.ui.Format
import com.tube.player.ui.MainViewModel
import com.tube.player.youtube.ChannelListing
import com.tube.player.youtube.VideoItem
import com.tube.player.youtube.videoId
import com.tube.ui.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogicTest {

    @Test
    fun sapisidHashMatchesKnownValue() {
        // sha1("1700000000 abc/DEF https://www.youtube.com")
        val expected = java.security.MessageDigest.getInstance("SHA-1")
            .digest("1700000000 abc/DEF https://www.youtube.com".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", SapisidHash.header("abc/DEF", nowSeconds = 1_700_000_000))
        assertEquals(40, expected.length)
    }

    @Test
    fun accountReadsSapisidFromCookies() {
        val a = Account("PREF=f6=40000000; SID=xyz; __Secure-3PAPISID=sec3; SAPISID=main/1")
        assertEquals("main/1", a.sapisid)
        assertEquals("sec3", Account("SID=1; __Secure-3PAPISID=sec3").sapisid)
        assertNull(Account("SID=1").sapisid)
    }

    @Test
    fun videoIdFromAllLinkKinds() {
        assertEquals("dQw4w9WgXcQ", videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1s"))
        assertEquals("dQw4w9WgXcQ", videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", videoId("https://m.youtube.com/live/dQw4w9WgXcQ"))
        assertNull(videoId("https://www.youtube.com/@channel"))
    }

    @Test
    fun sponsorBlockParsesOnlyOurVideo() {
        val json = """[
            {"videoID":"other_video","segments":[{"segment":[1.0,20.0],"category":"sponsor","UUID":"x","actionType":"skip"}]},
            {"videoID":"abcdefghijk","segments":[
                {"segment":[30.5,60.0],"category":"sponsor","UUID":"a","actionType":"skip"},
                {"segment":[0.0,5.0],"category":"intro","UUID":"b","actionType":"skip"},
                {"segment":[100.0,100.2],"category":"selfpromo","UUID":"c","actionType":"skip"},
                {"segment":[200.0,210.0],"category":"poi_highlight","UUID":"d","actionType":"poi"}
            ]}
        ]"""
        val segments = SponsorBlock.parse(json, "abcdefghijk")
        assertEquals(listOf("b", "a"), segments.map { it.uuid })
        assertEquals(30_500L, segments[1].startMs)
        assertEquals(SponsorCategory.Intro, segments[0].category)
    }

    @Test
    fun sponsorBlockSkipRules() {
        val sponsor = Segment(SponsorCategory.Sponsor, 10_000, 40_000, "s")
        val intro = Segment(SponsorCategory.Intro, 0, 8_000, "i")
        val actions = SponsorCategory.entries.associateWith { it.default }
        // Вошли в начало отрезка — пропускаем.
        assertEquals(sponsor, SponsorBlock.toSkip(listOf(intro, sponsor), 10_300, actions, emptySet()))
        // Пользователь сам перемотал в середину — не выкидываем.
        assertNull(SponsorBlock.toSkip(listOf(sponsor), 25_000, actions, emptySet()))
        // Отменён — не пропускаем снова.
        assertNull(SponsorBlock.toSkip(listOf(sponsor), 10_300, actions, setOf("s")))
        // Заставка по умолчанию — кнопкой.
        assertNull(SponsorBlock.toSkip(listOf(intro), 500, actions, emptySet()))
        assertEquals(intro, SponsorBlock.forButton(listOf(intro), 3_000, actions))
        assertNull(SponsorBlock.toSkip(listOf(sponsor), 10_300, actions + (SponsorCategory.Sponsor to SegmentAction.Off), emptySet()))
        // Префикс хэша — 4 символа hex.
        assertEquals(64, SponsorBlock.sha256("abcdefghijk").length)
    }

    @Test
    fun returnDislikeParse() {
        val v = ReturnDislike.parse("""{"id":"x","likes":1200,"dislikes":34,"rating":4.8}""")
        assertEquals(1200L, v.likes)
        assertEquals(34L, v.dislikes)
    }

    private fun r(id: String, video: Boolean, codecs: String, mime: String, h: Int = 0, bw: Int = 100_000) = Rendition(
        id = id, video = video, url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=$id&a=1", mime = mime, codecs = codecs,
        bandwidth = bw, width = h * 16 / 9, height = h, fps = 30, initStart = 0, initEnd = 740, indexStart = 741, indexEnd = 1900,
    )

    @Test
    fun dashManifestGroupsCodecsAndEscapesUrls() {
        val mpd = DashManifest.build(
            listOf(
                r("v137", true, "avc1.640028", "video/mp4", 1080, 4_000_000),
                r("v136", true, "avc1.4d401f", "video/mp4", 720, 2_000_000),
                r("v248", true, "vp9", "video/webm", 1080, 2_500_000),
                r("a140", false, "mp4a.40.2", "audio/mp4"),
            ),
            212.5,
        )!!
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT212.500S\""))
        // avc1 и vp9 — в разных наборах, оба качества avc1 — в одном.
        assertEquals(3, Regex("<AdaptationSet").findAll(mpd).count())
        assertTrue(mpd.contains("&amp;a=1"))
        assertFalse(mpd.contains("&a=1<"))
        assertTrue(mpd.contains("""<SegmentBase indexRange="741-1900"><Initialization range="0-740"/></SegmentBase>"""))
        // Манифест разбирается Media3.
        val parsed = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(Uri.parse("https://youtube.com"), mpd.byteInputStream())
        val sets = parsed.getPeriod(0).adaptationSets
        assertEquals(2, sets.first().representations.size)
        assertNull(DashManifest.build(listOf(r("v137", true, "avc1.640028", "video/mp4", 1080)), 10.0))
    }

    @Test
    fun googlevideoRequestsArePostWithRequestNumber() {
        val counter = AtomicLong()
        val visionUrl = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&c=VISIONOS&itag=137"
        val spec = YoutubeDataSource.resolve(DataSpec.Builder().setUri(Uri.parse(visionUrl)).setPosition(1000).setLength(500).build(), counter)
        assertEquals(DataSpec.HTTP_METHOD_POST, spec.httpMethod)
        assertTrue(spec.uri.toString().endsWith("&rn=0"))
        assertEquals(1000L, spec.position)
        assertEquals(500L, spec.length)
        assertTrue(spec.httpRequestHeaders["User-Agent"].orEmpty().isNotEmpty())
        assertTrue(YoutubeDataSource.resolve(DataSpec(Uri.parse(visionUrl)), counter).uri.toString().endsWith("&rn=1"))
        // Превью и прочее — обычный GET.
        val thumb = YoutubeDataSource.resolve(DataSpec(Uri.parse("https://i.ytimg.com/vi/x/hq720.jpg")), counter)
        assertEquals(DataSpec.HTTP_METHOD_GET, thumb.httpMethod)
    }

    @Test
    fun proxyIsIpv4LoopbackNotIp6Localhost() {
        // byedpi слушает только 127.0.0.1; ::1 давал «Connection refused» на телефоне.
        val proxy = com.tube.player.net.BypassProxySelector { 41311 }.select(java.net.URI("https://www.youtube.com/")).single()
        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals(java.net.Proxy.Type.SOCKS, proxy.type())
        assertTrue(address.address is java.net.Inet4Address)
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(41311, address.port)
        assertEquals(java.net.Proxy.NO_PROXY, com.tube.player.net.BypassProxySelector { null }.select(java.net.URI("https://x/")).single())
    }

    @Test
    fun onlyYoutubeGoesThroughBypass() {
        val net = com.tube.player.net.BypassProxySelector
        assertTrue(net.needsBypass("rr3---sn-abc.googlevideo.com"))
        assertTrue(net.needsBypass("www.youtube.com"))
        assertTrue(net.needsBypass("i.ytimg.com"))
        assertTrue(net.needsBypass("accounts.google.com"))
        assertFalse(net.needsBypass("sponsor.ajay.app"))
        assertFalse(net.needsBypass("returnyoutubedislikeapi.com"))
        assertFalse(net.needsBypass("notyoutube.com"))
    }

    @Test
    fun uploadDateIsRelative() {
        val now = java.time.OffsetDateTime.parse("2026-11-12T12:00:00Z").toInstant().toEpochMilli()
        val iso = VideoItem("u", "t", uploaded = "2026-11-09T08:00:00+00:00")
        assertEquals("3 дня назад", Format.uploaded(iso, AppLanguage.Russian, now))
        assertEquals("3 days ago", Format.uploaded(iso, AppLanguage.English, now))
        assertEquals("2 недели назад", Format.uploaded(VideoItem("u", "t", uploaded = "2026-10-29"), AppLanguage.Russian, now))
        assertEquals("1 год назад", Format.uploaded(VideoItem("u", "t", uploadedAt = now - 400L * 86_400_000), AppLanguage.Russian, now))
        assertEquals("5 дней назад", Format.uploaded(VideoItem("u", "t", uploaded = "5 дней назад"), AppLanguage.Russian, now))
    }

    @Test
    fun likeAndDislikeAreExclusive() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val library = Library(context)
        val v = VideoItem("https://www.youtube.com/watch?v=CCCCCCCCCCC", "Three")
        assertTrue(library.toggleLike(v))
        assertTrue(library.isLiked(v.id))
        assertTrue(library.toggleDislike(v))
        assertFalse(library.isLiked(v.id))
        assertTrue(library.isDisliked(v.id))
        assertFalse(library.toggleDislike(v))
        assertFalse(library.isDisliked(v.id))
    }

    @Test
    fun adBlockHostsAndPaths() {
        assertTrue(AdBlock.isBlocked("pagead2.googlesyndication.com", "/x"))
        assertTrue(AdBlock.isBlocked("www.youtube.com", "/pagead/viewthroughconversion"))
        assertTrue(AdBlock.isBlocked("www.youtube.com", "/api/stats/ads"))
        assertTrue(AdBlock.isBlocked("static.doubleclick.net", "/instream/ad_status.js"))
        assertFalse(AdBlock.isBlocked("www.youtube.com", "/youtubei/v1/browse"))
        assertFalse(AdBlock.isBlocked("rr1---sn-abc.googlevideo.com", "/videoplayback"))
        assertFalse(AdBlock.isBlocked("i.ytimg.com", "/vi/x/hq720.jpg"))
    }

    @Test
    fun innerTubeSkipsAdsAndFindsVideos() {
        val json = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
            {"richItemRenderer":{"content":{"adSlotRenderer":{"fulfillmentContent":{"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"Ad"}]}}}}}}},
            {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"BBBBBBBBBBB","title":{"runs":[{"text":"Real video"}]},
              "ownerText":{"runs":[{"text":"Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}]},
              "lengthText":{"simpleText":"4:05"},"viewCountText":{"simpleText":"1,234 views"},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/BBBBBBBBBBB/hq720.jpg","width":720,"height":404}]}}}}}
        ]}}}}]}}}"""
        val items = InnerTube.items(JsonParser.`object`().from(json)).filterIsInstance<VideoItem>()
        assertEquals(1, items.size)
        assertEquals("Real video", items[0].title)
        assertEquals("BBBBBBBBBBB", items[0].id)
        assertEquals(245L, items[0].duration)
    }

    @Test
    fun formatIsShortAndLocalized() {
        assertEquals("4:05", Format.duration(245))
        assertEquals("1:02:03", Format.duration(3723))
        assertEquals("1,2 млн просмотров", Format.views(1_234_567, AppLanguage.Russian))
        assertEquals("350 тыс. просмотров", Format.views(350_000, AppLanguage.Russian))
        assertEquals("1 просмотр", Format.views(1, AppLanguage.Russian))
        assertEquals("3 просмотра", Format.views(3, AppLanguage.Russian))
        assertEquals("1.2M views", Format.views(1_234_567, AppLanguage.English))
        assertEquals("A · B", Format.meta("A", null, "", "B"))
    }

    @Test
    fun libraryHistoryAndSubscriptions() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val library = Library(context)
        val v1 = VideoItem("https://www.youtube.com/watch?v=AAAAAAAAAAA", "One")
        val v2 = VideoItem("https://www.youtube.com/watch?v=BBBBBBBBBBB", "Two")
        library.watched(v1)
        library.watched(v2)
        library.watched(v1)
        assertEquals(listOf("One", "Two"), library.history.items.value.map { it.video.title })
        library.savePosition(v1.id, 60_000, 300_000)
        assertEquals(60_000L, library.position(v1.id))
        library.savePosition(v1.id, 295_000, 300_000)
        assertEquals(0L, library.position(v1.id))

        val ch = com.tube.player.youtube.ChannelItem("https://www.youtube.com/channel/UC123", "Chan")
        library.subscribe(ch)
        library.subscribe(ch.copy(url = "https://youtube.com/channel/UC123/"))
        assertEquals(1, library.subscriptions.items.value.size)
        assertTrue(library.isSubscribed("https://m.youtube.com/channel/UC123"))
        library.unsubscribe("https://www.youtube.com/channel/UC123")
        assertFalse(library.isSubscribed(ch.url))

        // JSON-запись и чтение.
        val json = Library.encodeVideo(v1)
        assertEquals(v1, Library.decodeVideo(json))
        assertNotNull(Library.decodeSubscription(Library.encodeSubscription(com.tube.player.data.Subscription(ch, 5))))
    }

    @Test
    fun interleaveMixesSources() {
        val a = listOf(VideoItem("a1", "a1"), VideoItem("a2", "a2"))
        val b = listOf(VideoItem("b1", "b1"), VideoItem("a1", "dup"))
        assertEquals(listOf("a1", "b1", "a2"), MainViewModel.interleave(listOf(a, b)).map { it.url })
    }

    @Test
    fun channelPeriodQueryAndMonths() {
        fun d(y: Int, m: Int, day: Int) = java.time.LocalDate.of(y, m, day)
        // after: — включительно, before: — не включая: конец периода +1 день.
        assertEquals("after:2019-03-01 before:2019-05-01", ChannelListing.query(d(2019, 3, 1), d(2019, 4, 30)))
        assertEquals("after:2020-02-29", ChannelListing.query(d(2020, 2, 29), null))
        assertEquals("before:2021-01-01", ChannelListing.query(null, d(2020, 12, 31)))
        val months = ChannelListing.months(d(2019, 11, 20), d(2020, 2, 3), newestFirst = true).map { it.toString() }
        assertEquals(listOf("2020-02", "2020-01", "2019-12", "2019-11"), months)
        assertEquals("2019-11", ChannelListing.months(d(2019, 11, 20), d(2020, 2, 3), newestFirst = false).first().toString())
        assertTrue(ChannelListing.months(d(2021, 1, 1), d(2020, 1, 1), true).isEmpty())
    }

    @Test
    fun periodDatesParse() {
        assertEquals(java.time.LocalDate.of(2024, 2, 29), com.tube.player.ui.parseDigits("29022024"))
        assertNull(com.tube.player.ui.parseDigits("29022023"))
        assertNull(com.tube.player.ui.parseDigits("3112202"))
        assertEquals("01.02.2024", com.tube.player.ui.formatDate(java.time.LocalDate.of(2024, 2, 1)))
    }
}
