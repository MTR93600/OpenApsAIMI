package app.aaps.plugins.aps.openAPSAIMI.ISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The heart-rate trend that makes insulin stronger.
 *
 * This is the only heart-rate path in the engine that RAISES a dose, and until 2026-09-17 it had no
 * gate at all: no carbs test, no rise test, no meal phase. During a fast rise an elevated heart rate
 * is a consequence of the rise, not information about its cause, so adding insulin for it counts the
 * same event twice.
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
        assertThat(multiplier()).isEqualTo(HeartRateTrendIsf.ISF_MULTIPLIER)
    }

    @Test
    fun walkingStopsIt() {
        assertThat(multiplier(steps10m = HeartRateTrendIsf.MAX_STEPS_10M)).isEqualTo(1.0)
    }

    @Test
    fun aGlucoseAtOrBelowTheFloorStopsIt() {
        assertThat(multiplier(bgMgdl = HeartRateTrendIsf.MIN_GLUCOSE_MGDL)).isEqualTo(1.0)
    }

    @Test
    fun aTrendAtOrBelowTheRatioStopsIt() {
        // 77 / 70 = 1.1 exactly.
        assertThat(multiplier(avgBpm10 = 77.0, avgBpm60 = 70.0)).isEqualTo(1.0)
    }

    // ----- the rise rule, which is what this change is about -----

    /**
     * The 2026-09-17 01:00 episode: glucose went 83 to 195 with the ten-minute heart rate climbing
     * from 62 to 100 against a calmer hour, no steps, glucose over 110. Every condition held, so the
     * gesture made insulin 11 % stronger in the middle of the rise it was reacting to.
     */
    @Test
    fun aFastRiseStopsIt() {
        assertThat(
            multiplier(deltaMgdl5m = HeartRateTrendIsf.RISE_SUSPEND_MGDL_PER_5MIN)
        ).isEqualTo(1.0)
        assertThat(multiplier(deltaMgdl5m = 31.9)).isEqualTo(1.0)
    }

    @Test
    fun aRiseJustUnderTheThresholdDoesNotStopIt() {
        assertThat(
            multiplier(deltaMgdl5m = HeartRateTrendIsf.RISE_SUSPEND_MGDL_PER_5MIN - 0.1)
        ).isEqualTo(HeartRateTrendIsf.ISF_MULTIPLIER)
    }

    @Test
    fun aFallDoesNotStopIt() {
        assertThat(multiplier(deltaMgdl5m = -5.0)).isEqualTo(HeartRateTrendIsf.ISF_MULTIPLIER)
    }

    // ----- a made-up baseline may not strengthen a dose -----

    /**
     * When the one-hour window holds no heart-rate record the engine substitutes 80 bpm, and the
     * trend is then a real ten-minute average divided by a number nobody measured. A dose may not be
     * strengthened on that.
     */
    @Test
    fun aBaselineThatWasNotMeasuredStopsIt() {
        assertThat(multiplier(avgBpm10 = 100.0, avgBpm60 = 80.0, baselineIsReal = false)).isEqualTo(1.0)
    }

    // ----- numbers that are not numbers -----

    @Test
    fun anyInputThatIsNotAUsableNumberStopsIt() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in bad) {
            assertThat(multiplier(avgBpm10 = x)).isEqualTo(1.0)
            assertThat(multiplier(avgBpm60 = x)).isEqualTo(1.0)
            assertThat(multiplier(bgMgdl = x)).isEqualTo(1.0)
            assertThat(multiplier(deltaMgdl5m = x)).isEqualTo(1.0)
        }
    }

    @Test
    fun aBaselineOfZeroOrLessStopsIt() {
        assertThat(multiplier(avgBpm60 = 0.0)).isEqualTo(1.0)
        assertThat(multiplier(avgBpm60 = -10.0)).isEqualTo(1.0)
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
        assertThat(answers).containsExactly(1.0, HeartRateTrendIsf.ISF_MULTIPLIER)
        assertThat(answers.all { it <= 1.0 }).isTrue()
    }
}
