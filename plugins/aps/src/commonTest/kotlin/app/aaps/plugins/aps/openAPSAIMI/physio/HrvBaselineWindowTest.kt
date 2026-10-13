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

    // The per-day cases that used to live here were replaced by HrvNightBaselineTest when the
    // baseline started reading the samples' own timestamps instead of counting pipeline runs.
    // "three runs in one day", "a day contributes its median" and the metric purge are all asserted
    // there now, against nights rather than runs. What is left here is the switch-off behaviour,
    // which must not move: it is what production does today.

    /** A clock the test drives, so "a run" and "a day" can be told apart. */
    private class TestClock(var nowMs: Long) {
        fun advanceMinutes(minutes: Long) { nowMs += minutes * 60_000L }
        fun advanceDays(days: Long) { nowMs += days * 24 * 60 * 60_000L }
    }

    private fun modelAt(clock: TestClock) =
        AIMIPhysioBaselineModelMTR(NoOpAapsLogger, now = { clock.nowMs })

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

    private companion object {

        /** An arbitrary fixed point, midday so a 15-minute step never crosses midnight. */
        const val DAY_ONE_NOON = 1_760_000_000_000L
    }
}
