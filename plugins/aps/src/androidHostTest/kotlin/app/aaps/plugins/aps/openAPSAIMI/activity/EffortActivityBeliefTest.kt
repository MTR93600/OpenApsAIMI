package app.aaps.plugins.aps.openAPSAIMI.activity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EffortActivityBeliefTest {

    private val t0 = 1_719_730_800_000L // arbitrary base

    private fun inputs(
        nowMs: Long = t0,
        steps5: Int = 0,
        steps15: Int = 0,
        steps60: Int = 0,
        hrAvg15: Int = 0,
        hrResting: Int = 0,
        hrvZ: Double? = null,
        stress: Double = 0.0,
    ) = EffortActivityBelief.Inputs(nowMs, steps5, steps15, steps60, hrAvg15, hrResting, hrvZ, stress)

    @Test
    fun idle_noSignals_doesNotReduceInsulin() {
        val (a, _) = EffortActivityBelief.assess(inputs(), EffortActivityBelief.Memory())
        assertThat(a.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.NEUTRAL)
        assertThat(a.smbFactor).isEqualTo(1.0)
        assertThat(a.basalFactor).isEqualTo(1.0)
        assertThat(a.reducesInsulin).isFalse()
    }

    @Test
    fun briskWalk_now_isActiveExertionAndReducesSmbAndBasal() {
        // ~67 steps/min over 15 min + HR elevation
        val (a, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 3000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        assertThat(a.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.EXERTION)
        assertThat(a.smbFactor).isLessThan(1.0)
        assertThat(a.basalFactor).isLessThan(1.0)
        assertThat(a.smbFactor).isAtLeast(0.45)
        assertThat(mem.lastEffortMs).isEqualTo(t0)
        assertThat(mem.peakStepsPerMin).isGreaterThan(40.0)
    }

    @Test
    fun recentEffort_memoryKeepsProtectionAfterMovementStops() {
        // The reported episode: walked, then stopped >15 min (tree window cleared) but <120 min ago.
        val (walk, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        assertThat(walk.state).isEqualTo(EffortActivityBelief.State.ACTIVE)

        // 35 min later, no current steps at all → tree would be IDLE, but memory must still protect.
        val (after, _) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 35 * 60_000L, steps5 = 0, steps15 = 0, steps60 = 0, hrAvg15 = 70, hrResting = 65),
            mem,
        )
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.RECENT_EFFORT)
        assertThat(after.posture).isEqualTo(EffortActivityBelief.Posture.EXERTION)
        assertThat(after.smbFactor).isLessThan(1.0) // still reducing — the core fix
        assertThat(after.reducesInsulin).isTrue()
    }

    @Test
    fun recentEffort_decaysToNeutralAfterMemoryHorizon() {
        val (_, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        // 130 min later → past the 120-min horizon → IDLE, no reduction.
        val (after, _) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 130 * 60_000L),
            mem,
        )
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(after.smbFactor).isEqualTo(1.0)
        assertThat(after.basalFactor).isEqualTo(1.0)
    }

    @Test
    fun stressWithoutMovement_isStressPostureAndDoesNotReduceInsulin() {
        // HR elevated + HRV depressed + high stress prob, but no steps → must NOT lower insulin.
        val (a, _) = EffortActivityBelief.assess(
            inputs(steps5 = 0, steps15 = 0, steps60 = 0, hrAvg15 = 95, hrResting = 65, hrvZ = -1.2, stress = 0.7),
            EffortActivityBelief.Memory(),
        )
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.STRESS)
        assertThat(a.smbFactor).isEqualTo(1.0)
        assertThat(a.basalFactor).isEqualTo(1.0)
        assertThat(a.confidence).isGreaterThan(0.0)
    }

    @Test
    fun hrvDepression_raisesExertionConfidence() {
        // Moderate movement only (no HR/sustained terms) so the reduction is NOT already saturated
        // at the floor — then HRV depression must deepen it.
        val base = EffortActivityBelief.assess(
            inputs(steps5 = 150, steps15 = 450),
            EffortActivityBelief.Memory(),
        ).first
        val withHrv = EffortActivityBelief.assess(
            inputs(steps5 = 150, steps15 = 450, hrvZ = -1.0),
            EffortActivityBelief.Memory(),
        ).first
        assertThat(base.smbFactor).isGreaterThan(0.45) // not saturated
        assertThat(withHrv.smbFactor).isLessThan(base.smbFactor) // stronger reduction with corroboration
    }

    @Test
    fun assess_isDeterministic() {
        val i = inputs(steps5 = 300, steps15 = 900, steps60 = 3000, hrAvg15 = 100, hrResting = 65)
        val first = EffortActivityBelief.assess(i, EffortActivityBelief.Memory())
        val second = EffortActivityBelief.assess(i, EffortActivityBelief.Memory())
        assertThat(first.first).isEqualTo(second.first)
        assertThat(first.second).isEqualTo(second.second)
    }

    // ── Onset vs memory stamping ────────────────────────────────────────────────

    @Test
    fun shortStepBurst_withLittleMovementIn15mWindow_reducesNowButArmsNoMemory() {
        // 200 steps inside one 5-min window but only 210 over 15 min: walking to the kitchen, not
        // exercise. The current tick may still protect (fast onset), but nothing may survive it.
        val (burst, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 200, steps15 = 210, steps60 = 240),
            EffortActivityBelief.Memory(),
        )
        assertThat(burst.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(burst.smbFactor).isLessThan(1.0) // fast onset kept
        assertThat(burst.reasons).contains("burst_uncorroborated")
        assertThat(mem.lastEffortMs).isEqualTo(0L) // memory NOT armed
        assertThat(mem.effortMinutes).isEqualTo(0.0)

        // Next tick, movement gone → protection is gone with it.
        val (after, _) = EffortActivityBelief.assess(inputs(nowMs = t0 + 5 * 60_000L), mem)
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(after.smbFactor).isEqualTo(1.0)
        assertThat(after.basalFactor).isEqualTo(1.0)
    }

    @Test
    fun shortStepBurst_duringDecay_doesNotReArmFullProtection() {
        // Reproduces the measured field defect: the factor decayed 0.45 → 0.54 and then jumped back to
        // 0.45 because ~125+ steps inside a single 5-min window re-stamped the memory at full strength.
        val (_, walkMem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000),
            EffortActivityBelief.Memory(),
        )
        assertThat(walkMem.lastEffortMs).isEqualTo(t0)

        // 20 min later a bare 5-min burst: 40 steps/min over 5 min, 14 steps/min over 15 min.
        val (burst, burstMem) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 20 * 60_000L, steps5 = 200, steps15 = 210, steps60 = 250),
            walkMem,
        )
        assertThat(burst.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(burstMem.lastEffortMs).isEqualTo(t0) // the clock was NOT restarted

        // 25 min after the walk the decay must have carried on, not restarted.
        val (after, _) = EffortActivityBelief.assess(inputs(nowMs = t0 + 25 * 60_000L), burstMem)
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.RECENT_EFFORT)
        assertThat(after.smbFactor).isWithin(1e-6).of(0.7555556) // was 0.4729 before the fix
    }

    @Test
    fun sustainedWalk_stillProducesFullProtection() {
        // 30 min of brisk walking, one tick every 5 min.
        var mem = EffortActivityBelief.Memory()
        for (tick in 0..6) {
            val (a, next) = EffortActivityBelief.assess(
                inputs(nowMs = t0 + tick * 5 * 60_000L, steps5 = 350, steps15 = 1000, steps60 = 4000),
                mem,
            )
            mem = next
            assertThat(a.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
            assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.EXERTION)
            assertThat(a.smbFactor).isEqualTo(0.45) // full protection on every tick
            assertThat(a.basalFactor).isEqualTo(0.70)
        }
        assertThat(mem.effortMinutes).isEqualTo(30.0) // earns the full 120-min horizon

        // 60 min after the session ends the protection is exactly what it was before this change.
        val (after, _) = EffortActivityBelief.assess(inputs(nowMs = t0 + (30 + 60) * 60_000L), mem)
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.RECENT_EFFORT)
        assertThat(after.smbFactor).isWithin(1e-9).of(0.725)
        assertThat(after.basalFactor).isWithin(1e-9).of(0.85)

        // Still protecting at 119 min, released just after 120 min.
        assertThat(
            EffortActivityBelief.assess(inputs(nowMs = t0 + (30 + 119) * 60_000L), mem).first.smbFactor,
        ).isLessThan(1.0)
        assertThat(
            EffortActivityBelief.assess(inputs(nowMs = t0 + (30 + 121) * 60_000L), mem).first.smbFactor,
        ).isEqualTo(1.0)
    }

    // ── Decay horizon follows the effort length ─────────────────────────────────

    @Test
    fun briefEffort_releasesWellBefore120Minutes() {
        // One corroborated tick only: 400 steps over 15 min (26.7 steps/min) → 45-min horizon.
        val (_, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 200, steps15 = 400, steps60 = 400),
            EffortActivityBelief.Memory(),
        )
        assertThat(mem.effortMinutes).isEqualTo(5.0)

        // 20 min later: recency 1 − 20/45 = 0.5556, peak scale 26.67/40 = 0.6667 → strength 0.3704.
        val (at20, _) = EffortActivityBelief.assess(inputs(nowMs = t0 + 20 * 60_000L), mem)
        assertThat(at20.state).isEqualTo(EffortActivityBelief.State.RECENT_EFFORT)
        assertThat(at20.smbFactor).isWithin(1e-6).of(0.7962963) // was 0.5417 before the fix
        assertThat(at20.basalFactor).isWithin(1e-6).of(0.8888889)

        // Fully released at 50 min — far short of the old 120-min horizon.
        val (at50, _) = EffortActivityBelief.assess(inputs(nowMs = t0 + 50 * 60_000L), mem)
        assertThat(at50.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(at50.smbFactor).isEqualTo(1.0)
        assertThat(at50.basalFactor).isEqualTo(1.0)
    }

    @Test
    fun expiredStrongEffort_doesNotLendItsStrengthToALaterLightOne() {
        val (_, strongMem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000),
            EffortActivityBelief.Memory(),
        )
        assertThat(strongMem.peakStepsPerMin).isWithin(1e-6).of(66.666667)

        // 50 min later (past the 45-min horizon of that brief-but-hard effort) a light walk starts.
        val (_, lightMem) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 50 * 60_000L, steps5 = 130, steps15 = 400, steps60 = 400),
            strongMem,
        )
        assertThat(lightMem.peakStepsPerMin).isWithin(1e-6).of(26.666667) // its own rate, not 66.7
        assertThat(lightMem.effortMinutes).isEqualTo(5.0)
    }

    // ── Ordinary indoor life must not be read as effort ─────────────────────────

    @Test
    fun sittingStillWithAFewSteps_producesNoReduction() {
        // Measured field tail: 0-15 steps per 15 min while hyperglycaemic and resting.
        for (steps in intArrayOf(0, 9, 12, 15)) {
            val (a, mem) = EffortActivityBelief.assess(
                inputs(steps5 = steps, steps15 = steps, steps60 = steps * 4, hrAvg15 = 96, hrResting = 49),
                EffortActivityBelief.Memory(),
            )
            assertThat(a.state).isEqualTo(EffortActivityBelief.State.IDLE)
            assertThat(a.smbFactor).isEqualTo(1.0)
            assertThat(a.basalFactor).isEqualTo(1.0)
            assertThat(a.reducesInsulin).isFalse()
            assertThat(mem.lastEffortMs).isEqualTo(0L)
        }
    }

    @Test
    fun stressPosture_neverReducesInsulin_atThisPatientsRestingHeartRate() {
        // Resting HR 49, waking HR up to 96 → a 47 bpm elevation with no movement at all.
        val (a, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 0, steps15 = 0, steps60 = 0, hrAvg15 = 96, hrResting = 49, hrvZ = -1.4, stress = 0.8),
            EffortActivityBelief.Memory(),
        )
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.STRESS)
        assertThat(a.smbFactor).isEqualTo(1.0)
        assertThat(a.basalFactor).isEqualTo(1.0)
        assertThat(mem.lastEffortMs).isEqualTo(0L)
    }

    // ── Wearable gaps and bounds ────────────────────────────────────────────────

    @Test
    fun noHeartRateData_leavesStepDrivenBehaviourUnchanged() {
        val (walk, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000, hrAvg15 = 0, hrResting = 0, hrvZ = null),
            EffortActivityBelief.Memory(),
        )
        assertThat(walk.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(walk.smbFactor).isEqualTo(0.45) // steps alone still protect

        val (idle, _) = EffortActivityBelief.assess(
            inputs(hrAvg15 = 0, hrResting = 0, hrvZ = null),
            EffortActivityBelief.Memory(),
        )
        assertThat(idle.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(idle.smbFactor).isEqualTo(1.0)

        // A resting value without a live value must not read as a negative elevation either.
        val (halfData, _) = EffortActivityBelief.assess(
            inputs(hrAvg15 = 0, hrResting = 49, hrvZ = null),
            EffortActivityBelief.Memory(),
        )
        assertThat(halfData.posture).isEqualTo(EffortActivityBelief.Posture.NEUTRAL)
        assertThat(halfData.smbFactor).isEqualTo(1.0)
    }

    @Test
    fun factorsStayWithinBounds_forNaNAndOutOfRangeInputs() {
        val cases = listOf(
            inputs(steps5 = -100, steps15 = -1, steps60 = -5, hrAvg15 = -20, hrResting = -49) to
                EffortActivityBelief.Memory(),
            inputs(steps5 = Int.MAX_VALUE, steps15 = Int.MAX_VALUE, steps60 = Int.MAX_VALUE) to
                EffortActivityBelief.Memory(),
            inputs(hrvZ = Double.NaN, stress = Double.NaN) to EffortActivityBelief.Memory(),
            inputs(hrAvg15 = 96, hrResting = 49, hrvZ = -1.4, stress = Double.NaN) to
                EffortActivityBelief.Memory(),
            // Caller-restored memory that is itself corrupt.
            inputs(nowMs = t0 + 10 * 60_000L) to
                EffortActivityBelief.Memory(t0, Double.NaN, Double.NaN),
            inputs(nowMs = t0 + 10 * 60_000L) to
                EffortActivityBelief.Memory(t0, Double.POSITIVE_INFINITY, -999.0),
            // nowMs behind the stamp (clock moved back).
            inputs(nowMs = t0 - 30 * 60_000L) to EffortActivityBelief.Memory(t0, 66.0, 30.0),
        )
        for ((input, memory) in cases) {
            val (a, next) = EffortActivityBelief.assess(input, memory)
            assertThat(a.smbFactor).isAtMost(1.0)
            assertThat(a.smbFactor).isAtLeast(0.45)
            assertThat(a.basalFactor).isAtMost(1.0)
            assertThat(a.basalFactor).isAtLeast(0.70)
            assertThat(a.confidence).isAtLeast(0.0)
            assertThat(a.confidence).isAtMost(1.0)
            assertThat(a.smbFactor.isNaN()).isFalse()
            assertThat(a.basalFactor.isNaN()).isFalse()
            // A memory armed on this tick is always built from clean numbers; a corrupt memory the
            // caller handed in is passed back untouched rather than laundered.
            if (next != memory) {
                assertThat(next.effortMinutes.isFinite()).isTrue()
                assertThat(next.peakStepsPerMin.isFinite()).isTrue()
            }
        }
    }

    /**
     * `DetermineBasalAIMI2` runs this belief twice over: once on the armed memory, once on a separate
     * shadow memory so the export can show what the belief asked for while the opt-in key is off. Two
     * chains over the same ticks must reach the same verdict, otherwise the shadow is not a faithful
     * preview of what arming the key would do.
     */
    @Test
    fun twoSeparateMemoryChainsOverTheSameTicksAgree() {
        val ticks = listOf(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 3000, hrAvg15 = 105, hrResting = 65),
            inputs(nowMs = t0 + 20 * 60_000L, steps5 = 0, steps15 = 120, steps60 = 3000, hrAvg15 = 88, hrResting = 65),
            inputs(nowMs = t0 + 50 * 60_000L, steps5 = 0, steps15 = 0, steps60 = 1200, hrAvg15 = 70, hrResting = 65),
        )
        var armedMemory = EffortActivityBelief.Memory()
        var shadowMemory = EffortActivityBelief.Memory()
        for (tick in ticks) {
            val (armed, nextArmed) = EffortActivityBelief.assess(tick, armedMemory)
            val (shadow, nextShadow) = EffortActivityBelief.assess(tick, shadowMemory)
            assertThat(shadow.smbFactor).isEqualTo(armed.smbFactor)
            assertThat(shadow.state).isEqualTo(armed.state)
            assertThat(shadow.posture).isEqualTo(armed.posture)
            armedMemory = nextArmed
            shadowMemory = nextShadow
        }
        assertThat(shadowMemory).isEqualTo(armedMemory)
    }

    /**
     * The shadow chain must never be able to move the armed one. The memory is an immutable data
     * class, so advancing one is not supposed to touch the other; this pins that down, because the
     * disarmed export path advances the shadow memory on every tick.
     */
    @Test
    fun advancingTheShadowMemoryLeavesTheArmedMemoryUntouched() {
        val armedMemory = EffortActivityBelief.Memory()
        var shadowMemory = armedMemory
        repeat(5) { i ->
            val (_, next) = EffortActivityBelief.assess(
                inputs(nowMs = t0 + i * 60_000L, steps5 = 350, steps15 = 1000, steps60 = 3000, hrAvg15 = 105, hrResting = 65),
                shadowMemory,
            )
            shadowMemory = next
        }
        assertThat(shadowMemory).isNotEqualTo(armedMemory)
        assertThat(armedMemory.lastEffortMs).isEqualTo(0L)
        assertThat(armedMemory.peakStepsPerMin).isEqualTo(0.0)
        assertThat(armedMemory.effortMinutes).isEqualTo(0.0)
        // A cold armed chain must still reach a walking verdict, proving the shadow took nothing from it.
        val (fresh, _) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 3000, hrAvg15 = 105, hrResting = 65),
            armedMemory,
        )
        assertThat(fresh.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(fresh.smbFactor).isLessThan(1.0)
    }
}
