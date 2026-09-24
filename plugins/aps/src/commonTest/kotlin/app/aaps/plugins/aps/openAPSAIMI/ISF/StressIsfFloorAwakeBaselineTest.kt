package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the honest baseline does to the signature, on the heart rates the packages really carry.
 *
 * The median heart rate on floor-active ticks was 80 bpm. Against the old sleeping baseline of 50 it
 * cleared the +20 bpm rule with ease, which is why the floor held for half of every day. Against the
 * measured awake rate of 69 it does not clear it at all.
 *
 * Source: `origin/dev_OAPSAIMI` @ `6a6561caab`. Study: commonTest + kotlin.test.
 */
class StressIsfFloorAwakeBaselineTest {

    private val tenMinutesMs = 10 * 60 * 1000L
    private val startMs = 1_790_021_540_129L

    /** Two evaluations ten minutes apart, which is the shortest hold the gesture accepts. */
    private fun heldVerdict(hrNowBpm: Int, restingBpm: Int): StressIsfFloor.Verdict {
        val first = StressIsfFloor.evaluate(
            hrNowBpm = hrNowBpm,
            rhrRestingBpm = restingBpm,
            stepsLast15m = 0,
            nowMs = startMs,
            signatureSinceMs = null,
        )
        return StressIsfFloor.evaluate(
            hrNowBpm = hrNowBpm,
            rhrRestingBpm = restingBpm,
            stepsLast15m = 0,
            nowMs = startMs + tenMinutesMs,
            signatureSinceMs = first.signatureSinceMs,
            lastEvaluatedMs = first.lastEvaluatedMs,
            wasActive = first.active,
            breakStartedMs = first.breakStartedMs,
        )
    }

    @Test
    fun `an ordinary awake heart rate used to trigger against the sleeping baseline`() {
        assertTrue(heldVerdict(hrNowBpm = 80, restingBpm = 50).active)
    }

    @Test
    fun `the same heart rate does not trigger against the awake baseline`() {
        assertFalse(heldVerdict(hrNowBpm = 80, restingBpm = 69).active)
    }

    @Test
    fun `a floor already active stands down when the baseline goes missing`() {
        // The baseline is measured hourly on a background scope. If that measurement fails while the
        // floor is holding, the gesture must stop rather than keep protecting on a number nobody can
        // read any more — and the verdict must say the BASELINE is what went missing, not the heart
        // rate, which is still there.
        val active = heldVerdict(hrNowBpm = 92, restingBpm = 69)
        assertTrue(active.active)

        val afterBaselineLost = StressIsfFloor.evaluate(
            hrNowBpm = 92,
            rhrRestingBpm = 0,
            stepsLast15m = 0,
            nowMs = startMs + 2 * tenMinutesMs,
            signatureSinceMs = active.signatureSinceMs,
            lastEvaluatedMs = active.lastEvaluatedMs,
            wasActive = true,
            breakStartedMs = active.breakStartedMs,
        )

        assertFalse(afterBaselineLost.active)
        assertNull(afterBaselineLost.signatureSinceMs)
        assertTrue(afterBaselineLost.reason.startsWith(StressIsfFloor.REASON_NO_BASELINE))
    }

    @Test
    fun `a real stress heart rate still triggers against the awake baseline`() {
        assertTrue(heldVerdict(hrNowBpm = 92, restingBpm = 69).active)
    }

    @Test
    fun `no baseline is passed as zero and never activates anything`() {
        val verdict = heldVerdict(hrNowBpm = 120, restingBpm = 0)

        assertFalse(verdict.active)
        // Heart rate 120 is present, so the missing input is the baseline, not the heart rate.
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_BASELINE))
    }
}
