package com.tube.strategy

import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Порядок кандидатов автоподбора, чистка списка, хранение времени проверок и подтверждений сторожа. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickOrderTest {
    private val probe = Probe("www.youtube.com", "/", 64)
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L

    private fun score(s: Strategy, ok: Boolean, at: Long = now) = StrategyScore(s, listOf(ProbeResult(probe, ok, 300, 70_000, null)), at = at)

    @Test
    fun candidatesHaveNoDuplicatesOrQuic() {
        val list = Strategies.candidates(blockUdp = true)
        val normalized = list.map { it.args.trim().split(Regex("\\s+")).joinToString(" ") }
        assertEquals(normalized.size, normalized.toSet().size)
        assertTrue(list.none { it.args.trimStart().startsWith("-Ku") })
        assertTrue(list.size < Strategies.all.size)
        // Выбранные раньше стратегии остаются доступны по id.
        Strategies.all.forEach { assertEquals(it, Strategies.byId(it.id)) }
        assertTrue(Strategies.candidates(blockUdp = false).any { it.args.trimStart().startsWith("-Ku") })
    }

    @Test
    fun knownGoodComesFirst() {
        val list = Strategies.candidates(true)
        val plain = list.filterNot(Strategies::usesGoogleFake)
        val confirmed = plain[20]
        val workedToday = plain[30]
        val workedElsewhere = plain[40]
        val failedToday = plain[0]
        val all = mapOf(
            "cell:25001" to NetworkResults(
                testedAt = now - day / 2,
                scores = mapOf(workedToday.id to score(workedToday, true, now - day / 2), failedToday.id to score(failedToday, false, now - 3_600_000)),
                lastGood = mapOf(confirmed.id to now - 3_600_000),
            ),
            "cell:25099" to NetworkResults(scores = mapOf(workedElsewhere.id to score(workedElsewhere, true))),
        )
        val ordered = AutoPick.order(list, all, "cell:25001", now)
        assertEquals(listOf(confirmed, workedToday, workedElsewhere), ordered.take(3))
        assertEquals(failedToday, ordered.last())
        assertEquals(list.toSet(), ordered.toSet())
    }

    @Test
    fun oldResultsFadeAndGoogleFakesGoLast() {
        val list = Strategies.candidates(true)
        val stale = list[25]
        val all = mapOf("wifi:x" to NetworkResults(scores = mapOf(stale.id to score(stale, true, now - 30 * day))))
        val ordered = AutoPick.order(list, all, "wifi:x", now)
        // Месячной давности «работала» — без бонуса: порядок как в списке, кроме фейков google.com в конце.
        val google = list.filter(Strategies::usesGoogleFake)
        assertTrue(google.isNotEmpty())
        assertEquals(list.filterNot(Strategies::usesGoogleFake), ordered.take(list.size - google.size))
        assertEquals(google, ordered.takeLast(google.size))
    }

    @Test
    fun workingSinceSkipsOldScores() {
        val a = score(Strategies.builtIn[0], true, now - 5 * day)
        val b = score(Strategies.builtIn[1], true, now - day)
        val n = NetworkResults(scores = listOf(a, b).associateBy { it.strategy.id })
        assertEquals(listOf(b), n.workingSince(now, 3 * day))
    }

    @Test
    fun jsonKeepsCheckTimeAndLiveConfirmations() {
        val s = Strategies.builtIn[2]
        val original = NetworkResults(testedAt = 42, scores = mapOf(s.id to score(s, true, now)), lastGood = mapOf(s.id to now - 5))
        val decoded = ResultsStore.decodeNetwork(org.json.JSONObject(ResultsStore.encode(mapOf("k" to original)).toString()).getJSONObject("k"))
        assertEquals(original, decoded)
        // Запись прошлой версии без времени проверки — время подбора.
        val legacy = org.json.JSONObject(ResultsStore.encode(mapOf("k" to original)).toString()).getJSONObject("k")
        legacy.getJSONArray("scores").getJSONObject(0).remove("at")
        assertEquals(42L, ResultsStore.decodeNetwork(legacy).scores.getValue(s.id).at)
    }

    @Test
    fun streamLinksAreNotStored() {
        val s = Strategies.builtIn[0]
        val stream = Probe("rr1---sn-abc.googlevideo.com", "/videoplayback?expire=1&sig=secret", 128 * 1024, post = true)
        val n = NetworkResults(scores = mapOf(s.id to StrategyScore(s, listOf(ProbeResult(stream, true, 900, 200_000, null)), at = now)))
        val json = ResultsStore.encode(mapOf("k" to n)).toString()
        assertFalse("secret" in json)
    }

    @Test
    fun requestHeadersCannotInjectLines() {
        val request = StrategyTester.httpRequest("h", "/videoplayback?a=1", post = true, headers = mapOf("User-Agent" to "UA", "X-Bad" to "a\r\nHost: evil", "Bad\nName" to "v"))
        assertTrue(request.startsWith("POST /videoplayback?a=1 HTTP/1.1\r\nHost: h\r\n"))
        assertTrue("User-Agent: UA\r\n" in request)
        assertFalse("evil" in request)
        assertFalse("Bad" in request)
        assertTrue("Content-Length: 2\r\n" in request)
        assertEquals(1, Regex("User-Agent").findAll(request).count())
    }
}
