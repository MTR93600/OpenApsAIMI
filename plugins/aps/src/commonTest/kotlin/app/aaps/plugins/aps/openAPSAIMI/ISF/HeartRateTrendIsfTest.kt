package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The heart-rate trend that makes insulin stronger.
 *
 * This is the only heart-rate path in the engine that RAISES a dose, and until 2026-09-17 it had no
 * gate at all: no carbs test, no rise test, no meal phase. During a fast rise an elevated heart rate
 * is a consequence of the rise, not information about its cause, so adding insulin for it counts the
 * same event twice.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `9d0e9bdb9f` (file unchanged on tip `f61474bb73`).
 * Study source set: [HeartRateTrendIsf] is commonMain and has no mocks, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [CommandedIsfOrderTest] / [DynIsfCacheTest], not
 * `androidHostTest` (mockito/Robolectric). Assertions are the same locks as the Truth/JUnit
 * reference corpus.
 */
class HeartRateTrendIsfTest {

    private fun multiplier(
        steps10m: Int = 0,
        avgBpm10: Double = 100.0,
        avgBpm60: Double = 70.0,
        baselineIsReal: Boolean = true,
        bgMgdl: Double = 150.0,
        deltaMgdl5m: Double = 2.0,
    ) = HeartRateTrendIsf.multiplier(
        steps10m = steps10m,
        avgBpm10 = avgBpm10,
        avgBpm60 = avgBpm60,
        baselineIsReal = baselineIsReal,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
    )

    // ----- it still does what it was written for -----

    @Test
    fun aRisingTrendWhileStillAndAboveTheGlucoseFloorStrengthensInsulin() {
        assertEquals(HeartRateTrendIsf.ISF_MULTIPLIER, multiplier())
    }

    @Test
    fun walkingStopsIt() {
        assertEquals(1.0, multiplier(steps10m = HeartRateTrendIsf.MAX_STEPS_10M))
    }

    @Test
    fun aGlucoseAtOrBelowTheFloorStopsIt() {
        assertEquals(1.0, multiplier(bgMgdl = HeartRateTrendIsf.MIN_GLUCOSE_MGDL))
    }

    @Test
    fun aTrendAtOrBelowTheRatioStopsIt() {
        // 77 / 70 = 1.1 exactly.
        assertEquals(1.0, multiplier(avgBpm10 = 77.0, avgBpm60 = 70.0))
    }

    // ----- the rise rule, which is what this change is about -----

    /**
     * The 2026-09-17 01:00 episode: glucose went 83 to 195 with the ten-minute heart rate climbing
     * from 62 to 100 against a calmer hour, no steps, glucose over 110. Every condition held, so the
     * gesture made insulin 11 % stronger in the middle of the rise it was reacting to.
     */
    @Test
    fun aFastRiseStopsIt() {
        assertEquals(
            1.0,
            multiplier(deltaMgdl5m = HeartRateTrendIsf.RISE_SUSPEND_MGDL_PER_5MIN)
        )
        assertEquals(1.0, multiplier(deltaMgdl5m = 31.9))
    }

    @Test
    fun aRiseJustUnderTheThresholdDoesNotStopIt() {
        assertEquals(
            HeartRateTrendIsf.ISF_MULTIPLIER,
            multiplier(deltaMgdl5m = HeartRateTrendIsf.RISE_SUSPEND_MGDL_PER_5MIN - 0.1)
        )
    }

    @Test
    fun aFallDoesNotStopIt() {
        assertEquals(HeartRateTrendIsf.ISF_MULTIPLIER, multiplier(deltaMgdl5m = -5.0))
    }

    // ----- a made-up baseline may not strengthen a dose -----

    /**
     * When the one-hour window holds no heart-rate record the engine substitutes 80 bpm, and the
     * trend is then a real ten-minute average divided by a number nobody measured. A dose may not be
     * strengthened on that.
     */
    @Test
    fun aBaselineThatWasNotMeasuredStopsIt() {
        assertEquals(1.0, multiplier(avgBpm10 = 100.0, avgBpm60 = 80.0, baselineIsReal = false))
    }

    // ----- numbers that are not numbers -----

    @Test
    fun anyInputThatIsNotAUsableNumberStopsIt() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in bad) {
            assertEquals(1.0, multiplier(avgBpm10 = x))
            assertEquals(1.0, multiplier(avgBpm60 = x))
            assertEquals(1.0, multiplier(bgMgdl = x))
            assertEquals(1.0, multiplier(deltaMgdl5m = x))
        }
    }

    @Test
    fun aBaselineOfZeroOrLessStopsIt() {
        assertEquals(1.0, multiplier(avgBpm60 = 0.0))
        assertEquals(1.0, multiplier(avgBpm60 = -10.0))
    }

    @Test
    fun theGestureCanOnlyEverStrengthenOrDoNothing() {
        // Whatever the inputs, the answer is one of exactly two values, and neither weakens insulin.
        val answers = mutableSetOf<Double>()
        for (steps in listOf(0, 50, 100, 500)) {
            for (hr10 in listOf(50.0, 77.0, 100.0, 140.0)) {
                for (bg in listOf(90.0, 110.0, 150.0, 250.0)) {
                    for (d in listOf(-10.0, 0.0, 5.0, 11.0, 30.0)) {
                        answers += multiplier(steps10m = steps, avgBpm10 = hr10, bgMgdl = bg, deltaMgdl5m = d)
                    }
                }
            }
        }
        assertEquals(setOf(1.0, HeartRateTrendIsf.ISF_MULTIPLIER), answers)
        assertTrue(answers.all { it <= 1.0 })
    }
}
