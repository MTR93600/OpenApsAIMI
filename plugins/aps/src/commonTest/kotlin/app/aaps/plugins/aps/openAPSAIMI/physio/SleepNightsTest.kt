package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.SleepNights.Span
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Last night, out of the 48 hours of sleep both phones read. It reaches the dose through the sleep
 * debt, the short-night check and the sleep score, so the rule is tested on both platforms.
 */
class SleepNightsTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    /** Midnight of "today" in these tests; the day before starts at 0. */
    private val today = 48 * hour

    @Test
    fun `two nights in the window give only the last one`() {
        val spans = listOf(
            Span(today - 24 * hour - hour, today - 24 * hour + 6 * hour), // 23:00 - 06:00 the day before
            Span(today - hour, today + 5 * hour + 30 * minute),           // 23:00 - 05:30 last night
        )

        val night = assertNotNull(SleepNights.lastNight(spans, nowMs = today + 9 * hour))

        assertEquals(6.5, night.durationHours)
        assertEquals(today - hour, night.startMs)
    }

    @Test
    fun `a nap after the night is not taken as the night`() {
        val spans = listOf(
            Span(today - hour, today + 6 * hour),
            Span(today + 14 * hour, today + 15 * hour),
        )

        val night = assertNotNull(SleepNights.lastNight(spans, nowMs = today + 16 * hour))

        assertEquals(7.0, night.durationHours)
    }

    @Test
    fun `a night broken by a long waking is one night without the waking`() {
        val spans = listOf(
            Span(today - hour, today + 3 * hour),
            Span(today + 4 * hour + 30 * minute, today + 7 * hour), // 1.5 h awake in between
        )

        val night = assertNotNull(SleepNights.lastNight(spans, nowMs = today + 9 * hour))

        assertEquals(6.5, night.durationHours)
        assertEquals(today - hour, night.startMs)
        assertEquals(today + 7 * hour, night.endMs)
    }

    @Test
    fun `a short waking stays inside the night`() {
        val spans = listOf(
            Span(today, today + 3 * hour),
            Span(today + 3 * hour + 20 * minute, today + 7 * hour),
        )

        assertEquals(7.0, assertNotNull(SleepNights.lastNight(spans, nowMs = today + 9 * hour)).durationHours)
    }

    @Test
    fun `two writers for the same night are not counted twice`() {
        val spans = listOf(Span(today, today + 6 * hour), Span(today + hour, today + 7 * hour))

        assertEquals(7.0, assertNotNull(SleepNights.lastNight(spans, nowMs = today + 9 * hour)).durationHours)
    }

    /** At 3 in the morning half a night must not read as a short night. */
    @Test
    fun `a night still running does not replace the last finished one`() {
        val spans = listOf(
            Span(today - 24 * hour - hour, today - 24 * hour + 6 * hour),
            Span(today - hour, today + 4 * hour),
        )

        val night = assertNotNull(SleepNights.lastNight(spans, nowMs = today + 3 * hour))

        assertEquals(7.0, night.durationHours)
        assertEquals(today - 24 * hour - hour, night.startMs)
    }

    @Test
    fun `a really short night is seen as short`() {
        val spans = listOf(
            Span(today - 24 * hour - hour, today - 24 * hour + 6 * hour),
            Span(today + 2 * hour, today + 6 * hour),
        )

        val night = assertNotNull(SleepNights.lastNight(spans, nowMs = today + 9 * hour))

        assertEquals(4.0, night.durationHours)
    }

    @Test
    fun `only naps gives the longest one`() {
        val spans = listOf(Span(today, today + hour), Span(today + 8 * hour, today + 10 * hour))

        assertEquals(2.0, assertNotNull(SleepNights.lastNight(spans, nowMs = today + 12 * hour)).durationHours)
    }

    @Test
    fun `only a running night gives that night`() {
        val spans = listOf(Span(today, today + 5 * hour))

        assertEquals(5.0, assertNotNull(SleepNights.lastNight(spans, nowMs = today + 2 * hour)).durationHours)
    }

    @Test
    fun `no sleep gives no night`() {
        assertNull(SleepNights.lastNight(emptyList(), nowMs = today))
        assertNull(SleepNights.lastNight(listOf(Span(today, today)), nowMs = today))
    }

    // The baseline learned before this change holds 48 h sums; they must not be mixed with nights.

    @Test
    fun `a baseline saved with 48 hour sleep sums loads without its sleep part`() {
        val old = buildJsonObject {
            put("sleepDuration", buildJsonObject { put("mean", 13.2); put("stdDev", 0.4); put("count", 20) })
            put("validDays", 5)
            put("history", buildJsonObject {
                put("sleep", buildJsonObject { put("1000", 13.2); put("2000", 13.0) })
                put("rhr", buildJsonObject { put("1000", 55) })
            })
        }

        val baseline = PhysioBaselineMTR.fromJSON(old)

        assertTrue(baseline.sleepHistory.isEmpty())
        assertEquals(0, baseline.sleepDuration.sampleCount)
        assertEquals(mapOf(1000L to 55), baseline.rhrHistory)
        assertEquals(5, baseline.validDaysCount)
    }

    @Test
    fun `a baseline saved with nights keeps its sleep part`() {
        val saved = PhysioBaselineMTR(
            sleepDuration = MetricBaselineMTR("sleepDuration", mean = 7.0, stdDev = 0.5, sampleCount = 4),
            sleepHistory = mapOf(1000L to 7.0, 2000L to 6.5),
        )

        val loaded = PhysioBaselineMTR.fromJSON(saved.toJSON())

        assertEquals(saved.sleepHistory, loaded.sleepHistory)
        assertEquals(4, loaded.sleepDuration.sampleCount)
        assertEquals(7.0, loaded.sleepDuration.mean)
    }
}
