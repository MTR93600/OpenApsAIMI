package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds the stress signature and the floor it arms.
 *
 * Two properties matter more than the rest and are pinned at the bottom of this file:
 * the default multiplier must reproduce the old behaviour bit for bit, and the gesture must never be
 * able to lower a commanded sensitivity, because lowering it is what permits more insulin.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `e87cb03e87` + `9d0e9bdb9f` (file unchanged on
 * `f61474bb73`; clinical files unchanged on later tip `d4fa6b91ef`). Study source set:
 * [StressIsfFloor] is commonMain and has no mocks, so the tests live in `commonTest`
 * (`kotlin.test`) — same layout as [HeartRateTrendIsfTest] / [CommandedIsfOrderTest], not
 * `androidHostTest` (mockito/Robolectric). Assertions are the same locks as the Truth/JUnit
 * reference corpus. Backtick names have commas removed (KMP).
 */
class StressIsfFloorTest {

    private val t0 = 1_757_640_000_000L
    private val oneMinute = 60_000L

    /** A calm resting rate, and a heart rate clearly above it. Taken from the 2026-09-12 episode. */
    private val restingBpm = 50
    private val stressBpm = 82
    private val calmSteps = 48

    private fun tick(
        nowMs: Long,
        previous: StressIsfFloor.Verdict?,
        hrNowBpm: Int = stressBpm,
        rhrRestingBpm: Int = restingBpm,
        stepsLast15m: Int = calmSteps,
        deltaMgdl5m: Double = 0.0,
    ) = StressIsfFloor.evaluate(
        hrNowBpm = hrNowBpm,
        rhrRestingBpm = rhrRestingBpm,
        stepsLast15m = stepsLast15m,
        nowMs = nowMs,
        signatureSinceMs = previous?.signatureSinceMs,
        lastEvaluatedMs = previous?.lastEvaluatedMs,
        wasActive = previous?.active == true,
        breakStartedMs = previous?.breakStartedMs,
        deltaMgdl5m = deltaMgdl5m,
        breakHrBpm = previous?.breakHrBpm,
    )

    // ---- the hold time ---------------------------------------------------------------------------

    @Test
    fun `the signature only activates after the hold time kept without a break`() {
        var verdict = tick(t0, previous = null)
        assertFalse(verdict.active)
        assertEquals(0.0, verdict.heldMinutes, absoluteTolerance = 1e-9)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_HOLDING))

        // 5 min in: the signature holds but the hold time is not reached.
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertFalse(verdict.active)
        assertEquals(5.0, verdict.heldMinutes, absoluteTolerance = 1e-9)

        // Exactly at the threshold it becomes active.
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)
        assertEquals(StressIsfFloor.MIN_HELD_MINUTES, verdict.heldMinutes, absoluteTolerance = 1e-9)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_ACTIVE))
        assertEquals(t0, verdict.signatureSinceMs)
    }

    @Test
    fun `walking breaks the signature and resets the counter`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertEquals(5.0, verdict.heldMinutes, absoluteTolerance = 1e-9)

        // The limit itself is already walking, and the floor is not active yet so it gets no grace.
        verdict = tick(t0 + 10 * oneMinute, previous = verdict, stepsLast15m = StressIsfFloor.MAX_STEPS_LAST_15M)
        assertFalse(verdict.active)
        assertEquals(0.0, verdict.heldMinutes, absoluteTolerance = 1e-9)
        assertNull(verdict.signatureSinceMs)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))

        // The clock starts again from here, so 10 min after the break is not yet enough.
        verdict = tick(t0 + 15 * oneMinute, previous = verdict)
        verdict = tick(t0 + 20 * oneMinute, previous = verdict)
        assertFalse(verdict.active)
        assertEquals(5.0, verdict.heldMinutes, absoluteTolerance = 1e-9)
    }

    @Test
    fun `a heart rate that falls back breaks the signature and resets the counter`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)

        // One bpm under the threshold is not the signature.
        verdict = tick(
            t0 + 10 * oneMinute,
            previous = verdict,
            hrNowBpm = restingBpm + StressIsfFloor.HR_ABOVE_RESTING_BPM - 1,
        )
        assertFalse(verdict.active)
        assertNull(verdict.signatureSinceMs)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))
    }

    @Test
    fun `a gap longer than ten minutes between two evaluations breaks the continuity`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertEquals(5.0, verdict.heldMinutes, absoluteTolerance = 1e-9)

        // The loop was not running for 20 min. Nothing was observed, so nothing can be claimed.
        val afterGap = tick(t0 + 25 * oneMinute, previous = verdict)
        assertFalse(afterGap.active)
        assertEquals(0.0, afterGap.heldMinutes, absoluteTolerance = 1e-9)
        assertEquals(t0 + 25 * oneMinute, afterGap.signatureSinceMs)
        assertTrue(afterGap.reason.startsWith(StressIsfFloor.REASON_GAP_RESTART))

        // One missed 5-minute tick is still continuous.
        var kept = tick(t0, previous = null)
        kept = tick(t0 + 10 * oneMinute, previous = kept)
        assertEquals(t0, kept.signatureSinceMs)
        assertTrue(kept.active)
    }

    // ---- missing data ----------------------------------------------------------------------------

    @Test
    fun `a missing heart rate never activates the signature`() {
        val noHr = tick(t0, previous = null, hrNowBpm = 0)
        assertFalse(noHr.active)
        assertNull(noHr.signatureSinceMs)
        assertTrue(noHr.reason.startsWith(StressIsfFloor.REASON_NO_HR))
    }

    @Test
    fun `a missing resting heart rate never activates the signature`() {
        val noResting = tick(t0, previous = null, rhrRestingBpm = 0)
        assertFalse(noResting.active)
        assertNull(noResting.signatureSinceMs)
        assertTrue(noResting.reason.startsWith(StressIsfFloor.REASON_NO_HR))
    }

    @Test
    fun `a heart rate that goes missing mid-episode drops the hold time`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict, hrNowBpm = 0)

        assertFalse(verdict.active)
        assertEquals(0.0, verdict.heldMinutes, absoluteTolerance = 1e-9)
        assertNull(verdict.signatureSinceMs)
    }

    // ---- getting out is not getting in -----------------------------------------------------------

    /**
     * The case of 2026-09-13. Between 08:38 and 09:14 the step count crossed the old limit one tick
     * at a time - 91, 119, 171, 248 - while the heart rate stayed 24 to 44 bpm over resting. Each
     * crossing cancelled the floor and the hold time started again, so the floor was active on 8 of
     * the 420 ticks of that morning while blood glucose fell to 56.
     */
    @Test
    fun `an active floor survives a short step burst`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        // One tick of walking. The floor stays on and says why.
        verdict = tick(t0 + 11 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertTrue(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_EXIT_GRACE))
        assertEquals(t0 + 11 * oneMinute, verdict.breakStartedMs)

        // The signature comes back. The original start instant is kept, so the hold time is not
        // built again from scratch.
        verdict = tick(t0 + 12 * oneMinute, previous = verdict)
        assertTrue(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_ACTIVE))
        assertEquals(t0, verdict.signatureSinceMs)
        assertNull(verdict.breakStartedMs)
    }

    @Test
    fun `an active floor drops once the break outlasts the grace time`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        // The break starts here. Four minutes later it is still inside the grace time.
        verdict = tick(t0 + 11 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertTrue(verdict.active)
        verdict = tick(t0 + 15 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertTrue(verdict.active)
        assertEquals(t0 + 11 * oneMinute, verdict.breakStartedMs)

        // Five minutes of walking is walking.
        verdict = tick(t0 + 16 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))
        assertNull(verdict.signatureSinceMs)
        assertNull(verdict.breakStartedMs)
    }

    /** Grace belongs to a floor that is already on. A signature still building up never gets it. */
    @Test
    fun `a signature that is not yet active gets no grace`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertFalse(verdict.active)

        verdict = tick(t0 + 6 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))
        assertNull(verdict.signatureSinceMs)
    }

    /** Nothing was observed during a gap, so an active floor cannot claim its grace time either. */
    @Test
    fun `a gap drops an active floor at once`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        verdict = tick(t0 + 30 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))
    }

    /** A missing heart rate is missing data. It drops the floor whatever the grace time says. */
    @Test
    fun `a missing heart rate drops an active floor without grace`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        verdict = tick(t0 + 11 * oneMinute, previous = verdict, hrNowBpm = 0)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_HR))
    }

    // ---- how much movement still counts as "not walking" -----------------------------------------

    /**
     * Measured on 6950 ticks over 8 days: on morning ticks with the heart rate at least 20 bpm over
     * resting the median step count is 58 and the 90th centile is 303. Getting up and moving around
     * the house is not a walk, and the old limit of 100 called it one.
     */
    @Test
    fun `an ordinary morning of moving around is still the stress signature`() {
        var verdict = tick(t0, previous = null, stepsLast15m = 171)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict, stepsLast15m = 248)
        assertTrue(verdict.active)
    }

    /** Sustained walking is still excluded: the limit stays under the 375 the tree calls a walk. */
    @Test
    fun `sustained walking is not the stress signature`() {
        assertTrue(StressIsfFloor.MAX_STEPS_LAST_15M < 375)

        val verdict = tick(t0, previous = null, stepsLast15m = 375)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_SIGNATURE))
    }

    // ---- the floor the signature arms ------------------------------------------------------------

    /**
     * The whole change rests on this: with no multiplier given, the function does what it did.
     *
     * A grid of commanded sensitivities against a grid of profiles, compared with `isEqualTo` and not
     * with a tolerance, because "unchanged" here means the same `Double`.
     */
    @Test
    fun `the default multiplier is the old behaviour bit for bit`() {
        val commandedGrid = listOf(0.069, 4.54, 20.0, 48.0, 59.999, 60.0, 60.001, 67.4, 120.0, 132.0, 300.0)
        val profileGrid = listOf(30.0, 50.0, 120.0, 240.0)

        commandedGrid.forEach { commanded ->
            profileGrid.forEach { profile ->
                assertEquals(
                    maxOf(commanded, profile * DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR),
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                    ),
                )

                // Passing the default explicitly must give the very same Double.
                assertEquals(
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                    ),
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                        floorMultiplier = DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR,
                    ),
                )
            }
        }

        // The fail-open paths are unchanged too.
        assertEquals(4.54, DynamicSensitivityPolicy.floorAgainstProfile(4.54, null))
        assertEquals(4.54, DynamicSensitivityPolicy.floorAgainstProfile(4.54, 0.0))
        assertEquals(4.54, DynamicSensitivityPolicy.floorAgainstProfile(4.54, Double.NaN))
    }

    /** At the armed multiplier the result is `max(commanded, profile)` and never under the command. */
    @Test
    fun `the armed multiplier can only raise the sensitivity never lower it`() {
        val commandedGrid = listOf(0.069, 4.54, 20.0, 48.0, 67.4, 119.999, 120.0, 120.001, 132.0, 300.0)
        val profileGrid = listOf(30.0, 50.0, 120.0, 240.0)

        commandedGrid.forEach { commanded ->
            profileGrid.forEach { profile ->
                val floored = DynamicSensitivityPolicy.floorAgainstProfile(
                    commandedMgdlPerU = commanded,
                    profileIsfMgdlPerU = profile,
                    floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
                )
                assertEquals(maxOf(commanded, profile), floored)
                assertTrue(floored >= commanded)
            }
        }
    }

    /** The measured case of 2026-09-12: HR 82, resting 50, 48 steps, held 10 min, ISF 67.4, profile 120. */
    @Test
    fun `the real morning episode reaches the floor and is raised to the profile value`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        val commanded = 67.4
        val profile = 120.0
        val floored = DynamicSensitivityPolicy.floorAgainstProfile(
            commandedMgdlPerU = commanded,
            profileIsfMgdlPerU = profile,
            floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
        )
        assertEquals(120.0, floored, absoluteTolerance = 1e-9)
        // Without the gesture the same tick keeps the crushed value: 67.4 is above 0.5 x 120 = 60.
        assertEquals(
            commanded,
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = commanded,
                profileIsfMgdlPerU = profile,
            ),
        )
    }

    /** The inverse measured case: a sensitivity already above the profile is not pulled down. */
    @Test
    fun `a sensitivity above the profile is left alone by the armed floor`() {
        val commanded = 132.0
        assertEquals(
            commanded,
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = commanded,
                profileIsfMgdlPerU = 120.0,
                floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
            ),
        )
    }

    /**
     * The key disarmed: the verdict is still computed, and the commanded sensitivity is untouched.
     *
     * The call site chooses the multiplier; disarmed it passes the default. This reproduces that
     * choice against the armed one on the same inputs.
     */
    @Test
    fun `with the key disarmed the verdict is computed but the sensitivity is not moved`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertTrue(verdict.active)

        val armed = false
        val multiplier =
            if (verdict.active && armed) StressIsfFloor.ARMED_FLOOR_MULTIPLIER
            else DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR

        val commanded = CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = 67.4,
            profileIsfMgdlPerU = 120.0,
            floorMultiplier = multiplier,
        )
        assertEquals(
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = 67.4,
                profileIsfMgdlPerU = 120.0,
            ),
            commanded,
        )
        assertEquals(67.4, commanded)
    }

    // ---- heart rate has no authority during an undeclared meal rise -----------------------------

    /**
     * The 2026-09-17 01:00 episode. Glucose went 83 -> 195 in about half an hour. At 00:36 the
     * exported heart rate fell from 88 to 62 in one minute, which is under the resting + 20 the
     * signature needs, so the floor entered its 5-minute grace and dropped at 00:42 — the exact tick
     * the bolus reached its ceiling. The commanded sensitivity halved from 120 to 67.4 and 8 U went
     * in. The heart rate then returned to 100 at 00:54, but re-entering needs 10 unbroken minutes, so
     * the protection came back at 01:05 with the insulin already delivered.
     *
     * During a rise that steep the heart rate carries no information about the cause — measured on
     * that episode it read 88, then 62, then 100 within twenty minutes — and its only effect is to
     * remove a protection. So it must not be allowed to.
     */
    @Test
    fun `an active floor is not dropped by a heart rate reading while glucose rises fast`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        assertTrue(verdict.active)

        // The heart rate collapses to resting + 12 for longer than the exit grace, while glucose
        // climbs faster than anything cortisol can do.
        for (i in 13..30) {
            verdict = tick(t0 + i * oneMinute, verdict, hrNowBpm = restingBpm + 12, deltaMgdl5m = 13.7)
        }
        assertTrue(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_RISE_HOLD))
    }

    @Test
    fun `the same heart rate collapse still drops the floor when glucose is flat`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        assertTrue(verdict.active)

        // The reading moves each tick, so the fresh-sample rule does not come into it: this test is
        // about the rise freeze alone.
        for (i in 13..30) {
            verdict = tick(
                t0 + i * oneMinute, verdict,
                hrNowBpm = restingBpm + 11 + (i % 2),
                deltaMgdl5m = 0.5,
            )
        }
        assertFalse(verdict.active)
    }

    /**
     * The freeze holds the state, it does not create one. An inactive floor must not be switched on
     * by a rise: that would withhold insulin from a real meal on no evidence at all.
     */
    @Test
    fun `a fast rise never switches an inactive floor on`() {
        val verdict = tick(t0, previous = null, hrNowBpm = restingBpm + 2, deltaMgdl5m = 30.0)
        assertFalse(verdict.active)
    }

    @Test
    fun `a rise below the threshold does not freeze anything`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        for (i in 13..30) {
            verdict = tick(
                t0 + i * oneMinute, verdict,
                hrNowBpm = restingBpm + 11 + (i % 2),
                deltaMgdl5m = StressIsfFloor.RISE_HOLD_MGDL_PER_5MIN - 0.1,
            )
        }
        assertFalse(verdict.active)
    }

    @Test
    fun `a rise that is not a usable number does not freeze anything`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        for (i in 13..30) {
            verdict = tick(
                t0 + i * oneMinute, verdict,
                hrNowBpm = restingBpm + 11 + (i % 2),
                deltaMgdl5m = Double.NaN,
            )
        }
        assertFalse(verdict.active)
    }

    /**
     * A data gap must still drop the floor, rise or no rise: nothing was observed in between, so
     * there is no state worth freezing.
     */
    @Test
    fun `a data gap drops the floor even while glucose rises fast`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        assertTrue(verdict.active)
        verdict = tick(
            t0 + 40 * oneMinute, verdict,
            hrNowBpm = restingBpm + 12,
            deltaMgdl5m = 20.0,
        )
        assertFalse(verdict.active)
    }

    @Test
    fun `a missing heart rate drops the floor even while glucose rises fast`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        verdict = tick(t0 + 13 * oneMinute, verdict, hrNowBpm = 0, deltaMgdl5m = 20.0)
        assertFalse(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_NO_HR))
    }

    // ---- the grace must outlast the heart-rate refresh cadence ----------------------------------

    /**
     * Measured on 11 644 logged minutes over 12 days: the exported heart rate is not a per-minute
     * measurement but a staircase refreshed about every 9 minutes (median 9, 45 % of intervals
     * exactly 9, and `hr_now_bpm` equals `hr_avg_15m_bpm` on every single tick). Of 42 floor
     * releases, 20 came from the exit grace, and **all 20 rested on one unrefreshed sample**: the
     * 5-minute grace is shorter than the cadence, so it always expires on the very reading that
     * broke the signature, having never seen a second one.
     *
     * A protection may not be ended by a number nobody has looked at twice.
     */
    @Test
    fun `the floor is not released while the heart rate reading has never been refreshed`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        assertTrue(verdict.active)

        // The same held sample for far longer than the grace.
        val heldSample = restingBpm + 12
        for (i in 13..25) verdict = tick(t0 + i * oneMinute, verdict, hrNowBpm = heldSample)
        assertTrue(verdict.active)
        assertTrue(verdict.reason.startsWith(StressIsfFloor.REASON_EXIT_GRACE))
    }

    @Test
    fun `a fresh reading past the grace does release the floor`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        for (i in 13..19) verdict = tick(t0 + i * oneMinute, verdict, hrNowBpm = restingBpm + 12)
        assertTrue(verdict.active)
        // A new sample arrives, still out of signature, and the grace has long elapsed.
        verdict = tick(t0 + 20 * oneMinute, verdict, hrNowBpm = restingBpm + 11)
        assertFalse(verdict.active)
    }

    /**
     * And it cannot hold for ever on a reading that never moves: past
     * [StressIsfFloor.EXIT_GRACE_MAX_MINUTES] the floor drops whatever the sample does.
     */
    @Test
    fun `a reading that never moves still drops the floor at the hard cap`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        val heldSample = restingBpm + 12
        val cap = StressIsfFloor.EXIT_GRACE_MAX_MINUTES.toInt()
        for (i in 13..(13 + cap + 2)) verdict = tick(t0 + i * oneMinute, verdict, hrNowBpm = heldSample)
        assertFalse(verdict.active)
    }

    @Test
    fun `the fresh-reading rule does not delay a release when the sample moves at once`() {
        var verdict = tick(t0, previous = null)
        for (i in 1..12) verdict = tick(t0 + i * oneMinute, verdict)
        // Out of signature with a new value on every tick: the old 5-minute grace still governs.
        for (i in 13..19) verdict = tick(t0 + i * oneMinute, verdict, hrNowBpm = restingBpm + 12 - (i - 13))
        assertFalse(verdict.active)
    }
}
