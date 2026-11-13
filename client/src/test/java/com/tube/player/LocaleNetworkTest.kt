package com.tube.player

import com.tube.player.net.Http
import com.tube.player.youtube.ChannelListing
import com.tube.player.youtube.ChannelSort
import com.tube.player.youtube.DateRange
import com.tube.player.youtube.NewPipeBrowse
import com.tube.player.youtube.NewPipeDownloader
import com.tube.player.youtube.Repository
import com.tube.player.youtube.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Язык выдачи через настоящий YouTube: при русском — переводы названий от авторов (у MrBeast они есть на русском)
 * во всех источниках, при английском — оригинал. NewPipe сам подменял язык на en-GB.
 * Запуск: gradlew :client:testDebugUnitTest -Pnetwork --tests "*LocaleNetworkTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocaleNetworkTest {
    private val beastId = "UCX6OQ3DkcsbYNE6H8uQQuVA"
    private val beast = "https://www.youtube.com/channel/$beastId"
    private val cyrillic = Regex("[А-Яа-яЁё]")

    private fun ru(titles: List<String>) = titles.count { cyrillic.containsMatchIn(it) }

    @Test
    fun titlesFollowAppLanguage() = runBlocking {
        assumeTrue(System.getProperty("network") == "true")
        val relay = SocksRelay()
        try {
            val client = Http.client({ relay.port })
            val repo = Repository(NewPipeDownloader { client }, "ru", "RU")
            fun check(tag: String, titles: List<String>) {
                println("LOC $tag: ${ru(titles)}/${titles.size} ${titles.take(3)}")
                assertTrue("$tag: ${titles.take(3)}", titles.isNotEmpty() && ru(titles) * 2 >= titles.size)
            }
            val channel = repo.channel(beast).videos.items.filterIsInstance<VideoItem>()
            check("channel", channel.map { it.title })
            check("search", repo.search("mrbeast").items.filterIsInstance<VideoItem>().filter { it.channelUrl?.contains(beastId) == true }.map { it.title })
            check("subscriptions feed", repo.feed(beast).map { it.title })
            check("popular", ChannelListing(NewPipeBrowse, beastId, ChannelSort.Popular, DateRange()).first().items.filterIsInstance<VideoItem>().map { it.title })
            check("oldest+newest dates", listOf(channel.first().uploaded.orEmpty()))
            val video = repo.video(channel.first().url)
            println("LOC video: ${video.item.title} | related ${video.related.filterIsInstance<VideoItem>().take(3).map { it.title }}")
            check("video", listOf(video.item.title))

            repo.setLocale("en", "RU")
            val en = repo.channel(beast).videos.items.filterIsInstance<VideoItem>().map { it.title }
            println("LOC en channel: ${en.take(3)}")
            assertTrue("en: ${en.take(3)}", en.isNotEmpty() && ru(en) == 0)
            val enSearch = repo.search("mrbeast").items.filterIsInstance<VideoItem>().map { it.title }
            assertTrue(enSearch.isNotEmpty())
        } finally {
            relay.close()
        }
    }
}
