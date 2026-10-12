package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the HRV baseline measures, with the day buckets off and on.
 *
 * `HrvDepression` divides by this baseline's spread, and its answer reaches an insulin multiplier,
 * so what goes in decides what comes out. The class calls itself a "7-day rolling baseline" and
 * caps itself at `WINDOW_DAYS * 4` samples, a constant written as "4 samples per day max". Both
 * platforms feed it from the physiological pipeline, whose realtime window runs **every 15
 * minutes** - 96 times a day. Twenty-eight samples at that rate is seven hours.
 *
 * These tests cannot drive the clock: the model stamps its own entries with `aimiWallClockMs()`.
 * So they pin what can be pinned here - how many points a run contributes, and that the switch
 * changes nothing while it is off - and the seven-hour window itself is a reading of the constants.
 */
class HrvBaselineWindowTest {

    /** A clock the test drives, so "a run" and "a day" can be told apart. */
    private class TestClock(var nowMs: Long) {
        fun advanceMinutes(minutes: Long) { nowMs += minutes * 60_000L }
        fun advanceDays(days: Long) { nowMs += days * 24 * 60 * 60_000L }
    }

    private fun modelAt(clock: TestClock) =
        AIMIPhysioBaselineModelMTR(NoOpAapsLogger) { clock.nowMs }

    private fun featuresWithHrv(hrv: Double) =
        PhysioFeaturesMTR(hrvMeanRMSSD = hrv, hasValidData = true)

    /**
     * Off, every run is its own point. Three runs a minute apart therefore make the baseline
     * "valid", although they describe one moment rather than three days.
     */
    @Test
    fun `with the switch off three runs are enough to call the baseline valid`() {
        val clock = TestClock(DAY_ONE_NOON)
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(featuresWithHrv(50.0 + it))
            clock.advanceMinutes(15)
        }

        assertTrue(
            model.getCurrentBaseline().hrvRMSSD.isValid(),
            "this is today's behaviour: three runs, not three days"
        )
    }

    /**
     * On, the same three runs land in one day bucket and contribute one point, so the baseline
     * stays invalid - which is what "at least three days" was always supposed to mean.
     */
    @Test
    fun `with the switch on three runs in one day are not enough`() {
        val clock = TestClock(DAY_ONE_NOON)
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(featuresWithHrv(50.0 + it), hrvPerDayBaseline = true)
            clock.advanceMinutes(15)
        }

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertEquals(0, baseline.sampleCount, "one day must contribute one point, not three")
        assertTrue(!baseline.isValid(), "one day is not a personal normal")
    }

    /**
     * A day contributes its median, not its last reading, so one odd value - a bad sensor contact,
     * a reading taken mid-effort - cannot carry the day.
     */
    @Test
    fun `a day contributes its median`() {
        val clock = TestClock(DAY_ONE_NOON)
        val model = modelAt(clock)

        // Day 1 holds one odd reading among four good ones; two more days make the baseline valid.
        listOf(50.0, 52.0, 9.0, 51.0, 50.0).forEach {
            model.updateBaseline(featuresWithHrv(it), hrvPerDayBaseline = true)
            clock.advanceMinutes(15)
        }
        clock.advanceDays(1)
        model.updateBaseline(featuresWithHrv(50.0), hrvPerDayBaseline = true)
        clock.advanceDays(1)
        model.updateBaseline(featuresWithHrv(50.0), hrvPerDayBaseline = true)

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertEquals(3, baseline.sampleCount, "three days, three points")
        // Day 1 contributes its median, 50.0. A mean would have been dragged to 42.4 by the 9.0,
        // and the spread would have widened enough to hide a real dip later.
        assertEquals(50.0, baseline.mean)
    }

    /**
     * The switch must be inert while it is off. Same readings, same baseline, to the digit.
     */
    @Test
    fun `the switch off leaves the baseline exactly as it was`() {
        val readings = listOf(50.0, 48.0, 53.0, 47.0)

        val clockA = TestClock(DAY_ONE_NOON)
        val untouched = modelAt(clockA)
        readings.forEach { untouched.updateBaseline(featuresWithHrv(it)); clockA.advanceMinutes(15) }

        val clockB = TestClock(DAY_ONE_NOON)
        val explicitlyOff = modelAt(clockB)
        readings.forEach {
            explicitlyOff.updateBaseline(featuresWithHrv(it), hrvPerDayBaseline = false)
            clockB.advanceMinutes(15)
        }

        val a = untouched.getCurrentBaseline().hrvRMSSD
        val b = explicitlyOff.getCurrentBaseline().hrvRMSSD
        assertEquals(a.mean, b.mean)
        assertEquals(a.stdDev, b.stdDev)
        assertEquals(a.sampleCount, b.sampleCount)
    }

    /** Changing the metric drops the day buckets too, not only the per-run history. */
    @Test
    fun `switching metric clears the day buckets`() {
        val clock = TestClock(DAY_ONE_NOON)
        val model = modelAt(clock)

        // Three days of RMSSD: a valid baseline on one scale.
        repeat(3) {
            model.updateBaseline(featuresWithHrv(50.0), HrvMetric.RMSSD, hrvPerDayBaseline = true)
            clock.advanceDays(1)
        }
        assertTrue(model.getCurrentBaseline().hrvRMSSD.isValid())

        // The phone changes and SDNN starts arriving. The old scale must not survive beside it.
        model.updateBaseline(featuresWithHrv(120.0), HrvMetric.SDNN, hrvPerDayBaseline = true)

        // The three RMSSD days are gone, so the one SDNN day cannot reach the three-day threshold
        // and the model reports an empty baseline. That is the cold start the metric guard intends:
        // better no reading at all than this person's RMSSD normal applied to SDNN numbers.
        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertTrue(!baseline.isValid(), "one day of the new metric is a cold start, by design")
        assertEquals(0.0, baseline.mean, "no RMSSD value may survive into an SDNN baseline")
    }

    private companion object {

        /** An arbitrary fixed point, midday so a 15-minute step never crosses midnight. */
        const val DAY_ONE_NOON = 1_760_000_000_000L
    }
}
