package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-night HRV baseline, built from the timestamped samples themselves.
 *
 * The baseline used to take one value per pipeline run, which runs every 15 minutes while HRV
 * itself only arrives a few times a night. Those repeats collapsed the spread, and
 * `HrvDepression` divides by that spread to reach an insulin multiplier.
 *
 * Reading the samples instead makes the night median a property of the data rather than of how
 * often the pipeline happened to run. It also makes the whole thing idempotent: the daily pass
 * fetches seven days of samples, so it rebuilds the same window from source every time, and a
 * restart costs nothing that the next fetch does not bring back.
 */
class HrvNightBaselineTest {

    private val paris = TimeZone.of("Europe/Paris")

    private class Clock(var nowMs: Long)

    private fun modelAt(clock: Clock, zone: TimeZone = paris) =
        AIMIPhysioBaselineModelMTR(NoOpAapsLogger, { clock.nowMs }, { zone })

    /** Local wall-clock time in [paris], as epoch milliseconds. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = paris): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun sample(ms: Long, value: Double) =
        HRVDataMTR(timestamp = ms, rmssd = value, metric = HrvMetric.RMSSD)

    /** A night that clearly counts: readings across the whole window, well over the coverage floor. */
    private fun fullNight(year: Int, month: Int, day: Int, value: Double): List<HRVDataMTR> =
        listOf(
            sample(at(year, month, day, 0, 30), value),
            sample(at(year, month, day, 2, 30), value),
            sample(at(year, month, day, 4, 30), value),
        )

    private fun features() = PhysioFeaturesMTR(hrvMeanRMSSD = 50.0, hasValidData = true)

    private fun AIMIPhysioBaselineModelMTR.feed(samples: List<HRVDataMTR>) =
        updateBaseline(features(), samples, HrvMetric.RMSSD, hrvPerDayBaseline = true)

    @Test
    fun `three closed nights make a baseline`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        model.feed(fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0))

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertTrue(baseline.isValid(), "three nights is a personal normal")
        assertEquals(3, baseline.sampleCount)
        assertEquals(50.0, baseline.mean)
    }

    /**
     * The point of reading samples rather than runs: feeding the same night ten times must not
     * make it count ten times, and must not shrink the spread.
     */
    @Test
    fun `feeding the same samples again changes nothing`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)
        val week = fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)

        model.feed(week)
        val once = model.getCurrentBaseline().hrvRMSSD
        repeat(9) { model.feed(week) }
        val tenTimes = model.getCurrentBaseline().hrvRMSSD

        assertEquals(once.sampleCount, tenTimes.sampleCount)
        assertEquals(once.mean, tenTimes.mean)
        assertEquals(once.stdDev, tenTimes.stdDev, "repeats must not collapse the spread")
    }

    /** A night contributes its median, so one odd reading cannot carry it. */
    @Test
    fun `a night contributes its median`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val oddNight = listOf(
            sample(at(2026, 3, 1, 0, 30), 50.0),
            sample(at(2026, 3, 1, 2, 30), 9.0),
            sample(at(2026, 3, 1, 4, 30), 51.0),
        )
        model.feed(oddNight + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        assertEquals(50.0, model.getCurrentBaseline().hrvRMSSD.mean, "the 9.0 must not drag the night")
    }

    /** Readings taken in the day say nothing about a resting normal, so they are not a night. */
    @Test
    fun `daytime readings are ignored`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val daytime = listOf(
            sample(at(2026, 3, 1, 14, 0), 10.0),
            sample(at(2026, 3, 1, 16, 0), 10.0),
            sample(at(2026, 3, 1, 18, 0), 10.0),
        )
        model.feed(
            daytime + fullNight(2026, 3, 1, 50.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0)
        )

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertEquals(3, baseline.sampleCount, "the daytime readings must not become a fourth night")
        assertEquals(50.0, baseline.mean, "and must not drag the mean toward 10")
    }

    /** The window opens at 22:00, so a late evening reading belongs to the morning that follows. */
    @Test
    fun `an evening reading belongs to the next morning's night`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val acrossMidnight = listOf(
            sample(at(2026, 3, 1, 23, 30), 50.0),
            sample(at(2026, 3, 2, 2, 0), 50.0),
        )
        // Nights 3 and 4 are both closed at 4 March noon; a night 5 would still be in the future.
        model.feed(acrossMidnight + fullNight(2026, 3, 3, 50.0) + fullNight(2026, 3, 4, 50.0))

        // The pair is one night (2 March), not two. Three nights in all, not four.
        assertEquals(3, model.getCurrentBaseline().hrvRMSSD.sampleCount)
    }

    /**
     * The night in progress must never be part of the baseline its own reading is compared
     * against. At 03:00 the current night is still open.
     */
    @Test
    fun `the night in progress is not counted`() {
        val clock = Clock(at(2026, 3, 4, 3))
        val model = modelAt(clock)

        model.feed(
            fullNight(2026, 3, 1, 50.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0) +
                fullNight(2026, 3, 4, 20.0)
        )

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertEquals(3, baseline.sampleCount, "tonight is still open")
        assertEquals(50.0, baseline.mean, "tonight's low reading must not enter its own baseline")
    }

    /** Two readings minutes apart say nothing about a night. Coverage is what counts. */
    @Test
    fun `a night with too little coverage is not counted`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val thin = listOf(
            sample(at(2026, 3, 1, 2, 0), 50.0),
            sample(at(2026, 3, 1, 2, 30), 50.0),
        )
        model.feed(
            thin + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0) + fullNight(2026, 3, 4, 50.0)
        )

        assertEquals(3, model.getCurrentBaseline().hrvRMSSD.sampleCount, "half an hour is not a night")
    }

    /** The clocks go forward at 02:00 on 29 March 2026, so that night is five real hours. */
    @Test
    fun `the spring forward night is one night`() {
        val clock = Clock(at(2026, 3, 30, 12))
        val model = modelAt(clock)

        val shortNight = listOf(
            sample(at(2026, 3, 28, 23, 30), 50.0),
            sample(at(2026, 3, 29, 1, 0), 50.0),
            sample(at(2026, 3, 29, 4, 0), 50.0),
        )
        model.feed(shortNight + fullNight(2026, 3, 27, 50.0) + fullNight(2026, 3, 26, 50.0))

        assertEquals(3, model.getCurrentBaseline().hrvRMSSD.sampleCount)
    }

    /** The window is local, so the same samples must give the same nights in another zone. */
    @Test
    fun `the zone decides the night and not the machine`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val parisModel = modelAt(clock, paris)
        val pragueModel = modelAt(clock, TimeZone.of("Europe/Prague"))

        val week = fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        parisModel.feed(week)
        pragueModel.feed(week)

        // Paris and Prague share an offset, so the same samples fall in the same nights.
        assertEquals(
            parisModel.getCurrentBaseline().hrvRMSSD.mean,
            pragueModel.getCurrentBaseline().hrvRMSSD.mean
        )
    }

    /** Only seven nights are kept, by local date. */
    @Test
    fun `the window keeps seven nights`() {
        val clock = Clock(at(2026, 3, 20, 12))
        val model = modelAt(clock)

        val tenNights = (1..10).flatMap { fullNight(2026, 3, it, 50.0) }
        model.feed(tenNights)

        assertEquals(7, model.getCurrentBaseline().hrvRMSSD.sampleCount)
    }

    /** A night count must also make the model's own day count agree, or `isValid` disagrees with itself. */
    @Test
    fun `valid days count sees the nights`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val baseline = model.feed(
            fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        )

        assertEquals(3, baseline.validDaysCount, "HRV-only nights still make days")
    }

    /** Changing metric drops the nights, so one scale never sits beside the other. */
    @Test
    fun `a metric change clears the nights`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)
        model.feed(fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0))

        val sdnnNight = fullNight(2026, 3, 3, 120.0).map { it.copy(rmssd = 0.0, sdnn = 120.0, metric = HrvMetric.SDNN) }
        model.updateBaseline(features(), sdnnNight, HrvMetric.SDNN, hrvPerDayBaseline = true)

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertTrue(!baseline.isValid(), "one night of the new metric is a cold start, by design")
        assertEquals(0.0, baseline.mean, "no RMSSD night may survive into an SDNN baseline")
    }

    /**
     * Features that also carry a resting heart rate and a step count, so the run reaches the other
     * histories as well as the nights. That overlap is what the day count has to handle.
     */
    private fun featuresWithOtherSources() =
        PhysioFeaturesMTR(hrvMeanRMSSD = 50.0, rhrMorning = 58, stepsDailyAverage = 6000, hasValidData = true)

    private fun AIMIPhysioBaselineModelMTR.feedWithOtherSources(samples: List<HRVDataMTR>) =
        updateBaseline(featuresWithOtherSources(), samples, HrvMetric.RMSSD, hrvPerDayBaseline = true)

    /**
     * One calendar day that has both a night and a reading from another source is one day.
     *
     * The count used to ADD the number of nights to an already deduplicated set of days, so this
     * single day reported two.
     */
    @Test
    fun `a date with both a night and another reading counts once`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)

        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        assertEquals(1, model.getCurrentBaseline().validDaysCount, "one day, not one plus one")
    }

    /** Two real days must not pass the "at least three days" test. */
    @Test
    fun `two real days do not make a valid baseline`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        val baseline = model.getCurrentBaseline()
        assertEquals(2, baseline.validDaysCount)
        assertTrue(!baseline.isValid(), "two days is not three")
    }

    /** Three real days do. */
    @Test
    fun `three real days make a valid baseline`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 3, 50.0))

        clock.nowMs = at(2026, 3, 4, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 4, 50.0))

        val baseline = model.getCurrentBaseline()
        assertEquals(3, baseline.validDaysCount)
        assertTrue(baseline.isValid())
    }

    /**
     * The day count reaches an insulin decision through the confidence score, which adds
     * `0.3 x (days / 7)`. A doubled count therefore buys confidence the data does not support.
     */
    @Test
    fun `confidence is not inflated by a day counted twice`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        val baseline = model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        val engine = AIMIPhysioContextEngineMTR(NoOpAapsLogger)
        val withTwoDays = engine.analyze(featuresWithOtherSources(), baseline).confidence
        val withFourDays = engine.analyze(
            featuresWithOtherSources(),
            baseline.copy(validDaysCount = 4)
        ).confidence

        assertEquals(2, baseline.validDaysCount)
        assertTrue(
            withTwoDays < withFourDays,
            "the score follows the day count, so the count must not be inflated"
        )
    }

    /**
     * A day is a local idea. At 00:30 in Paris the UTC date is still the day before, so a UTC
     * boundary would split one day in two and the night would never line up with it.
     */
    @Test
    fun `the day count uses the local date and not the UTC one`() {
        val clock = Clock(at(2026, 3, 2, 0, 30))
        val model = modelAt(clock)
        // Writes the heart rate and steps at 00:30 local, which is 23:30 UTC the day before.
        model.feedWithOtherSources(emptyList())

        clock.nowMs = at(2026, 3, 2, 12)
        model.feedWithOtherSources(
            listOf(sample(at(2026, 3, 1, 23, 30), 50.0), sample(at(2026, 3, 2, 2, 0), 50.0))
        )

        assertEquals(1, model.getCurrentBaseline().validDaysCount, "both readings are the 2nd of March")
    }

    /**
     * With the switch off the count must stay exactly what it always was, UTC day boundary and all.
     * Three runs a day apart made three days before this change, and still do.
     */
    @Test
    fun `the switch off counts days exactly as before`() {
        val clock = Clock(at(2026, 3, 1, 12))
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(
                featuresWithOtherSources(), fullNight(2026, 3, 1, 50.0), HrvMetric.RMSSD, hrvPerDayBaseline = false
            )
            clock.nowMs += 24 * 60 * 60 * 1000L
        }

        assertEquals(3, model.getCurrentBaseline().validDaysCount)
    }

    /**
     * The nights survive a restart.
     *
     * The per-night baseline reads [AIMIPhysioBaselineModelMTR] nights only. They used to live in
     * memory alone, so after a restart the baseline was empty and needed three fresh nights to be
     * valid again - up to a day, because only the daily pass fetches a week of readings.
     */
    @Test
    fun `the nights are restored into a fresh model`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val saved = modelAt(clock).feed(
            fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        )
        assertEquals(3, saved.hrvNights.size, "the nights must be written out")

        val restored = modelAt(clock)
        restored.restoreBaseline(saved)

        val baseline = restored.getCurrentBaseline().hrvRMSSD
        assertTrue(baseline.isValid(), "a restart must not cost the personal normal")
        assertEquals(3, baseline.sampleCount)
        assertEquals(50.0, baseline.mean)
    }

    /** A night this version cannot read must not cost the others. */
    @Test
    fun `an unreadable night date is dropped and the rest survive`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val saved = modelAt(clock).feed(
            fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        )
        val damaged = saved.copy(hrvNights = saved.hrvNights + ("not-a-date" to 99.0))

        val restored = modelAt(clock)
        restored.restoreBaseline(damaged)

        assertEquals(3, restored.getCurrentBaseline().hrvRMSSD.sampleCount)
    }

    /** With the switch off nothing about the night path may run. */
    @Test
    fun `the switch off still counts runs rather than nights`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(features(), fullNight(2026, 3, 1, 50.0), HrvMetric.RMSSD, hrvPerDayBaseline = false)
            clock.nowMs += 15 * 60_000L
        }

        assertTrue(
            model.getCurrentBaseline().hrvRMSSD.isValid(),
            "off, three runs still make a baseline - this is the behaviour in production"
        )
    }
}
