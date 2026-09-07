package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Freezes the maxSMB ladder as it behaved before it was moved out of `DetermineBasalAIMI2`.
 *
 * One fixture per branch. These tests are the proof that lifting the ladder into [MaxSmbLadder]
 * changed nothing: they were written against the old inline `when` and must stay green through the
 * move. They must also stay green after the shortAvgDelta rise fix, because that fix only opens a
 * branch that was closed here, it never closes one that was open.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `f03fa321a6` (file SHA `057813c03b`, unchanged on tip
 * `c5db5a0333`). Study source set: [MaxSmbLadder] is commonMain and has no mocks, so the tests live
 * in `commonTest` (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ISF.CommandedIsfOrderTest],
 * not `androidHostTest` (mockito/Robolectric). Assertions are the same locks as the Truth/JUnit
 * reference corpus.
 */
class MaxSmbLadderCharacterizationTest {

    private val maxSmb = 1.60
    private val maxSmbHighBg = 2.70

    private fun decide(
        bg: Double,
        combinedDelta: Double,
        slope: Double,
        shortAvgDelta: Double = 0.0,
        honeymoon: Boolean = false,
    ) = MaxSmbLadder.decide(
        bgMgdl = bg,
        combinedDelta = combinedDelta,
        slopeFromMinDeviation = slope,
        shortAvgDeltaMgdl5m = shortAvgDelta,
        honeymoon = honeymoon,
        maxSmb = maxSmb,
        maxSmbHighBg = maxSmbHighBg,
    )

    @Test
    fun `BG 250 and above takes the critical plateau branch whatever the slope`() {
        val decision = decide(bg = 250.0, combinedDelta = 0.2, slope = 0.02)

        assertEquals(MaxSmbLadder.LADDER_PLATEAU_CRITICAL, decision.branch)
        assertEquals(maxSmbHighBg, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 280 falling fast drops out of the critical plateau branch`() {
        // combinedDelta <= -5 is the guard that keeps the emergency ceiling off a fast fall.
        val decision = decide(bg = 280.0, combinedDelta = -6.0, slope = 0.02)

        assertEquals(MaxSmbLadder.LADDER_FALLING_60, decision.branch)
        assertEquals(maxSmbHighBg * 0.6, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 160 with a slope above one takes the confirmed rise branch`() {
        val decision = decide(bg = 160.0, combinedDelta = 2.0, slope = 1.2)

        assertEquals(MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH, decision.branch)
        assertEquals(maxSmbHighBg, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 130 with a slope above one takes the sensitive 85 branch`() {
        val decision = decide(bg = 130.0, combinedDelta = 2.0, slope = 1.1)

        assertEquals(MaxSmbLadder.LADDER_SENSITIVE_85, decision.branch)
        assertEquals(maxSmbHighBg * 0.85, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 220 on a flat plateau takes the moderate plateau branch`() {
        val decision = decide(bg = 220.0, combinedDelta = 0.5, slope = 0.02)

        assertEquals(MaxSmbLadder.LADDER_PLATEAU_MODERATE_75, decision.branch)
        assertEquals(maxSmbHighBg * 0.75, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 190 falling moderately takes the falling 60 branch`() {
        val decision = decide(bg = 190.0, combinedDelta = -4.0, slope = 0.02)

        assertEquals(MaxSmbLadder.LADDER_FALLING_60, decision.branch)
        assertEquals(maxSmbHighBg * 0.6, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `BG 100 takes the standard branch`() {
        val decision = decide(bg = 100.0, combinedDelta = 1.0, slope = 1.5)

        assertEquals(MaxSmbLadder.LADDER_STANDARD, decision.branch)
        assertEquals(maxSmb, decision.ceilingU, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the honeymoon rise needs BG 180 and a slope of 1 point 4`() {
        // Just under both honeymoon thresholds, so the ladder must stay on standard.
        assertEquals(
            MaxSmbLadder.LADDER_STANDARD,
            decide(bg = 179.0, combinedDelta = 2.0, slope = 1.5, honeymoon = true).branch,
        )
        assertEquals(
            MaxSmbLadder.LADDER_STANDARD,
            decide(bg = 185.0, combinedDelta = 2.0, slope = 1.3, honeymoon = true).branch,
        )
        // Both thresholds met, so the ladder promotes.
        assertEquals(
            MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH,
            decide(bg = 185.0, combinedDelta = 2.0, slope = 1.4, honeymoon = true).branch,
        )
    }

    @Test
    fun `the partial branches never fall below the standard preference`() {
        // A user whose standard preference is higher than 85 percent of the high BG one still gets
        // the standard preference, because the ladder takes the larger of the two.
        val decision = MaxSmbLadder.decide(
            bgMgdl = 220.0,
            combinedDelta = 0.5,
            slopeFromMinDeviation = 0.02,
            shortAvgDeltaMgdl5m = 0.0,
            honeymoon = false,
            maxSmb = 3.0,
            maxSmbHighBg = 2.0,
        )

        assertEquals(MaxSmbLadder.LADDER_PLATEAU_MODERATE_75, decision.branch)
        assertEquals(3.0, decision.ceilingU, absoluteTolerance = 1e-9)
    }
}
