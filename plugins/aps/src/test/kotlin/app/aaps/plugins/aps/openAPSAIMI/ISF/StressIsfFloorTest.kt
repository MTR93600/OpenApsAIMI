package app.aaps.plugins.aps.openAPSAIMI.ISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Holds the stress signature and the floor it arms.
 *
 * Two properties matter more than the rest and are pinned at the bottom of this file:
 * the default multiplier must reproduce the old behaviour bit for bit, and the gesture must never be
 * able to lower a commanded sensitivity, because lowering it is what permits more insulin.
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
    ) = StressIsfFloor.evaluate(
        hrNowBpm = hrNowBpm,
        rhrRestingBpm = rhrRestingBpm,
        stepsLast15m = stepsLast15m,
        nowMs = nowMs,
        signatureSinceMs = previous?.signatureSinceMs,
        lastEvaluatedMs = previous?.lastEvaluatedMs,
        wasActive = previous?.active == true,
        breakStartedMs = previous?.breakStartedMs,
    )

    // ---- the hold time ---------------------------------------------------------------------------

    @Test
    fun `the signature only activates after the hold time, kept without a break`() {
        var verdict = tick(t0, previous = null)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(0.0)
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_HOLDING)

        // 5 min in: the signature holds but the hold time is not reached.
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(5.0)

        // Exactly at the threshold it becomes active.
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(StressIsfFloor.MIN_HELD_MINUTES)
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_ACTIVE)
        assertThat(verdict.signatureSinceMs).isEqualTo(t0)
    }

    @Test
    fun `walking breaks the signature and resets the counter`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(5.0)

        // The limit itself is already walking, and the floor is not active yet so it gets no grace.
        verdict = tick(t0 + 10 * oneMinute, previous = verdict, stepsLast15m = StressIsfFloor.MAX_STEPS_LAST_15M)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(0.0)
        assertThat(verdict.signatureSinceMs).isNull()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)

        // The clock starts again from here, so 10 min after the break is not yet enough.
        verdict = tick(t0 + 15 * oneMinute, previous = verdict)
        verdict = tick(t0 + 20 * oneMinute, previous = verdict)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(5.0)
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
        assertThat(verdict.active).isFalse()
        assertThat(verdict.signatureSinceMs).isNull()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)
    }

    @Test
    fun `a gap longer than ten minutes between two evaluations breaks the continuity`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(5.0)

        // The loop was not running for 20 min. Nothing was observed, so nothing can be claimed.
        val afterGap = tick(t0 + 25 * oneMinute, previous = verdict)
        assertThat(afterGap.active).isFalse()
        assertThat(afterGap.heldMinutes).isWithin(1e-9).of(0.0)
        assertThat(afterGap.signatureSinceMs).isEqualTo(t0 + 25 * oneMinute)
        assertThat(afterGap.reason).startsWith(StressIsfFloor.REASON_GAP_RESTART)

        // One missed 5-minute tick is still continuous.
        var kept = tick(t0, previous = null)
        kept = tick(t0 + 10 * oneMinute, previous = kept)
        assertThat(kept.signatureSinceMs).isEqualTo(t0)
        assertThat(kept.active).isTrue()
    }

    // ---- missing data ----------------------------------------------------------------------------

    @Test
    fun `a missing heart rate never activates the signature`() {
        val noHr = tick(t0, previous = null, hrNowBpm = 0)
        assertThat(noHr.active).isFalse()
        assertThat(noHr.signatureSinceMs).isNull()
        assertThat(noHr.reason).startsWith(StressIsfFloor.REASON_NO_HR)
    }

    @Test
    fun `a missing resting heart rate never activates the signature`() {
        val noResting = tick(t0, previous = null, rhrRestingBpm = 0)
        assertThat(noResting.active).isFalse()
        assertThat(noResting.signatureSinceMs).isNull()
        assertThat(noResting.reason).startsWith(StressIsfFloor.REASON_NO_HR)
    }

    @Test
    fun `a heart rate that goes missing mid-episode drops the hold time`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict, hrNowBpm = 0)

        assertThat(verdict.active).isFalse()
        assertThat(verdict.heldMinutes).isWithin(1e-9).of(0.0)
        assertThat(verdict.signatureSinceMs).isNull()
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
        assertThat(verdict.active).isTrue()

        // One tick of walking. The floor stays on and says why.
        verdict = tick(t0 + 11 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isTrue()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_EXIT_GRACE)
        assertThat(verdict.breakStartedMs).isEqualTo(t0 + 11 * oneMinute)

        // The signature comes back. The original start instant is kept, so the hold time is not
        // built again from scratch.
        verdict = tick(t0 + 12 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_ACTIVE)
        assertThat(verdict.signatureSinceMs).isEqualTo(t0)
        assertThat(verdict.breakStartedMs).isNull()
    }

    @Test
    fun `an active floor drops once the break outlasts the grace time`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()

        // The break starts here. Four minutes later it is still inside the grace time.
        verdict = tick(t0 + 11 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isTrue()
        verdict = tick(t0 + 15 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isTrue()
        assertThat(verdict.breakStartedMs).isEqualTo(t0 + 11 * oneMinute)

        // Five minutes of walking is walking.
        verdict = tick(t0 + 16 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)
        assertThat(verdict.signatureSinceMs).isNull()
        assertThat(verdict.breakStartedMs).isNull()
    }

    /** Grace belongs to a floor that is already on. A signature still building up never gets it. */
    @Test
    fun `a signature that is not yet active gets no grace`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        assertThat(verdict.active).isFalse()

        verdict = tick(t0 + 6 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)
        assertThat(verdict.signatureSinceMs).isNull()
    }

    /** Nothing was observed during a gap, so an active floor cannot claim its grace time either. */
    @Test
    fun `a gap drops an active floor at once`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()

        verdict = tick(t0 + 30 * oneMinute, previous = verdict, stepsLast15m = 600)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)
    }

    /** A missing heart rate is missing data. It drops the floor whatever the grace time says. */
    @Test
    fun `a missing heart rate drops an active floor without grace`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()

        verdict = tick(t0 + 11 * oneMinute, previous = verdict, hrNowBpm = 0)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_HR)
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
        assertThat(verdict.active).isTrue()
    }

    /** Sustained walking is still excluded: the limit stays under the 375 the tree calls a walk. */
    @Test
    fun `sustained walking is not the stress signature`() {
        assertThat(StressIsfFloor.MAX_STEPS_LAST_15M).isLessThan(375)

        val verdict = tick(t0, previous = null, stepsLast15m = 375)
        assertThat(verdict.active).isFalse()
        assertThat(verdict.reason).startsWith(StressIsfFloor.REASON_NO_SIGNATURE)
    }

    // ---- the floor the signature arms ------------------------------------------------------------

    /**
     * The whole change rests on this: with no multiplier given, the function does what it did.
     *
     * A grid of commanded sensitivities against a grid of profiles, compared with `isEqualTo` and not
     * with a tolerance, because "unchanged" here means the same `Double`.
     */
    @Test
    fun `the default multiplier is the old behaviour, bit for bit`() {
        val commandedGrid = listOf(0.069, 4.54, 20.0, 48.0, 59.999, 60.0, 60.001, 67.4, 120.0, 132.0, 300.0)
        val profileGrid = listOf(30.0, 50.0, 120.0, 240.0)

        commandedGrid.forEach { commanded ->
            profileGrid.forEach { profile ->
                assertThat(
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                    ),
                ).isEqualTo(maxOf(commanded, profile * DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR))

                // Passing the default explicitly must give the very same Double.
                assertThat(
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                        floorMultiplier = DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR,
                    ),
                ).isEqualTo(
                    DynamicSensitivityPolicy.floorAgainstProfile(
                        commandedMgdlPerU = commanded,
                        profileIsfMgdlPerU = profile,
                    ),
                )
            }
        }

        // The fail-open paths are unchanged too.
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, null)).isEqualTo(4.54)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, 0.0)).isEqualTo(4.54)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, Double.NaN)).isEqualTo(4.54)
    }

    /** At the armed multiplier the result is `max(commanded, profile)` and never under the command. */
    @Test
    fun `the armed multiplier can only raise the sensitivity, never lower it`() {
        val commandedGrid = listOf(0.069, 4.54, 20.0, 48.0, 67.4, 119.999, 120.0, 120.001, 132.0, 300.0)
        val profileGrid = listOf(30.0, 50.0, 120.0, 240.0)

        commandedGrid.forEach { commanded ->
            profileGrid.forEach { profile ->
                val floored = DynamicSensitivityPolicy.floorAgainstProfile(
                    commandedMgdlPerU = commanded,
                    profileIsfMgdlPerU = profile,
                    floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
                )
                assertThat(floored).isEqualTo(maxOf(commanded, profile))
                assertThat(floored).isAtLeast(commanded)
            }
        }
    }

    /** The measured case of 2026-09-12: HR 82, resting 50, 48 steps, held 10 min, ISF 67.4, profile 120. */
    @Test
    fun `the real morning episode reaches the floor and is raised to the profile value`() {
        var verdict = tick(t0, previous = null)
        verdict = tick(t0 + 5 * oneMinute, previous = verdict)
        verdict = tick(t0 + 10 * oneMinute, previous = verdict)
        assertThat(verdict.active).isTrue()

        val commanded = 67.4
        val profile = 120.0
        val floored = DynamicSensitivityPolicy.floorAgainstProfile(
            commandedMgdlPerU = commanded,
            profileIsfMgdlPerU = profile,
            floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
        )
        assertThat(floored).isWithin(1e-9).of(120.0)
        // Without the gesture the same tick keeps the crushed value: 67.4 is above 0.5 x 120 = 60.
        assertThat(
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = commanded,
                profileIsfMgdlPerU = profile,
            ),
        ).isEqualTo(commanded)
    }

    /** The inverse measured case: a sensitivity already above the profile is not pulled down. */
    @Test
    fun `a sensitivity above the profile is left alone by the armed floor`() {
        val commanded = 132.0
        assertThat(
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = commanded,
                profileIsfMgdlPerU = 120.0,
                floorMultiplier = StressIsfFloor.ARMED_FLOOR_MULTIPLIER,
            ),
        ).isEqualTo(commanded)
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
        assertThat(verdict.active).isTrue()

        val armed = false
        val multiplier =
            if (verdict.active && armed) StressIsfFloor.ARMED_FLOOR_MULTIPLIER
            else DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR

        val commanded = CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = 67.4,
            profileIsfMgdlPerU = 120.0,
            floorMultiplier = multiplier,
        )
        assertThat(commanded).isEqualTo(
            DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = 67.4,
                profileIsfMgdlPerU = 120.0,
            ),
        )
        assertThat(commanded).isEqualTo(67.4)
    }
}
