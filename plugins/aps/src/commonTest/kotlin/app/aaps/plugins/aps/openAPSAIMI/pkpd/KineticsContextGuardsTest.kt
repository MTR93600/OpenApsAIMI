package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiIntelligenceSnapshotBuilder
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.IntelligenceSnapshotJson
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step P1b: two guards on the causal context shift of the DIA and peak that dose.
 * - The DIA and peak governor switches are the field off switch of the context shift. Since step P1d
 *   the shift also needs PK/PD on (see `KineticsContextPkpdGateTest`).
 * - The dosing DIA moves at most 0.25 h and the peak at most 2 min per 5 minutes (step P1c: the step
 *   counts time, not loop runs).
 * And the one-shot move of the old wizard pace (NORMAL) to the FASTER default.
 */
class KineticsContextGuardsTest {

    private val preferences = MapPreferences()
    private val minute = 60_000L
    private val tick = 5 * minute

    private val stress = CausalStatePosterior(
        stressResistanceProb = 1.0,
        dominant = CausalStateId.STRESS_RESISTANCE,
        dominantConfidence = 1.0,
        learningQuality = 1.0,
    )

    private val meal = CausalStatePosterior(
        fastMealProb = 1.0,
        dominant = CausalStateId.FAST_MEAL,
        dominantConfidence = 1.0,
        learningQuality = 1.0,
    )

    private val diagnostics = PkpdLearningDiagnostics.from(
        causalStatePosterior = null,
        allowLearning = false,
        exerciseFlag = false,
        iobU = 0.0,
        carbsActiveG = 0.0,
        bg = 120.0,
        deltaMgDlPer5 = 0.0,
    )

    /** Profile DIA 5 h, peak 75 min. PK/PD is off unless a test turns it on. */
    private fun resolve(previousTickPosterior: CausalStatePosterior?): InsulinKineticsView =
        InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray<IobTotal>(),
                profileDiaHours = 5.0,
                profilePeakMinutes = 75,
                pkpdRuntime = null,
                causalPosterior = previousTickPosterior,
                preferences = preferences,
                learningDiagnostics = diagnostics,
            ),
        )

    /**
     * A resolved view with a fixed dosing DIA and peak, for the rate limit tests. The limit does not care
     * where the target comes from, so these tests do not depend on the causal context (step P1d).
     */
    private fun target(diaHours: Double, peakMinutes: Double): InsulinKineticsView {
        val base = resolve(previousTickPosterior = null)
        return base.copy(effective = base.effective.copy(diaHours = diaHours, peakMinutes = peakMinutes))
    }

    /** The old stress target with PK/PD off before step P1d: DIA 5 + 1.0 h, peak 75 + 8 min. */
    private fun stressTarget() = target(6.0, 83.0)

    /** The old meal target with PK/PD off before step P1d: DIA 5 - 0.8 h, peak 75 - 3 min. */
    private fun mealTarget() = target(4.2, 72.0)

    // --- Off switch (the context shift needs PK/PD on, step P1d) ---

    @Test
    fun diaGovernorOffGivesNoDiaContextShift() {
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        preferences.booleans[BooleanKey.OApsAIMIDiaGovernorEnabled] = false
        val view = resolve(previousTickPosterior = stress)
        // Stress would add 1.0 h. With the DIA switch off the DIA stays the profile DIA.
        assertEquals(5.0, view.effective.diaHours, 1e-9)
        assertEquals(0.0, view.diaGovernor.diaContextualShiftHours, 1e-9)
        // The peak switch is still on, so the peak keeps its stress shift (+8 min).
        assertEquals(83.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun peakGovernorOffGivesNoPeakContextShift() {
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        preferences.booleans[BooleanKey.OApsAIMIPeakGovernorEnabled] = false
        val view = resolve(previousTickPosterior = stress)
        assertEquals(75.0, view.effective.peakMinutes, 1e-9)
        assertEquals(0.0, view.peakGovernor.peakContext, 1e-9)
        // The DIA switch is still on, so the DIA keeps its stress shift, blended with the learned DIA
        // (here the profile DIA, no runtime): 6.0 x 0.55 + 5.0 x 0.45 = 5.55 h.
        assertEquals(5.55, view.effective.diaHours, 1e-9)
    }

    @Test
    fun bothSwitchesOffDoseLikeNoContext() {
        preferences.booleans[BooleanKey.OApsAIMIDiaGovernorEnabled] = false
        preferences.booleans[BooleanKey.OApsAIMIPeakGovernorEnabled] = false
        val view = resolve(previousTickPosterior = meal)
        assertEquals(view.noContextEffective, view.effective)
    }

    // --- Rate limit ---

    @Test
    fun firstTickIsNotLimited() {
        val limiter = DosingKineticsRateLimiter()
        val view = limiter.limit(nowMs = 0L, resolved = stressTarget())
        assertEquals(6.0, view.effective.diaHours, 1e-9)
        assertEquals(83.0, view.effective.peakMinutes, 1e-9)
        assertEquals(view.targetEffective, view.effective)
    }

    @Test
    fun stressToMealFlipMovesAtMostOneStepPerTickAndReachesTheTarget() {
        val limiter = DosingKineticsRateLimiter()
        var previous = limiter.limit(nowMs = 0L, resolved = stressTarget()).effective
        assertEquals(6.0, previous.diaHours, 1e-9)
        assertEquals(83.0, previous.peakMinutes, 1e-9)
        // Meal at full confidence: DIA 5 - 0.8 = 4.2 h, peak 75 - 3 = 72 min. Distance 1.8 h and 11 min.
        var reachedAt = -1
        for (i in 1..12) {
            val view = limiter.limit(nowMs = i * tick, resolved = mealTarget())
            assertEquals(4.2, view.targetEffective.diaHours, 1e-9)
            assertEquals(72.0, view.targetEffective.peakMinutes, 1e-9)
            val diaStep = abs(view.effective.diaHours - previous.diaHours)
            val peakStep = abs(view.effective.peakMinutes - previous.peakMinutes)
            assertTrue(diaStep <= DosingKineticsRateLimiter.MAX_DIA_STEP_H + 1e-9, "tick $i: DIA moved $diaStep h")
            assertTrue(peakStep <= DosingKineticsRateLimiter.MAX_PEAK_STEP_MIN + 1e-9, "tick $i: peak moved $peakStep min")
            if (i == 1) {
                assertEquals(5.75, view.effective.diaHours, 1e-9)
                assertEquals(81.0, view.effective.peakMinutes, 1e-9)
            }
            if (reachedAt < 0 && view.effective == view.targetEffective) reachedAt = i
            previous = view.effective
        }
        // 1.8 h at 0.25 h per 5-minute tick needs 8 ticks; the peak (11 min at 2 min) is there after 6.
        assertEquals(8, reachedAt)
        assertEquals(4.2, previous.diaHours, 1e-9)
        assertEquals(72.0, previous.peakMinutes, 1e-9)
    }

    @Test
    fun afterMoreThanFifteenMinutesWithoutATickThereIsNoLimit() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        val view = limiter.limit(nowMs = 15 * minute + 1L, resolved = mealTarget())
        assertEquals(4.2, view.effective.diaHours, 1e-9)
        assertEquals(72.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun atFifteenMinutesTheLimitStillHolds() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        val view = limiter.limit(nowMs = 15 * minute, resolved = mealTarget())
        // At most one full step per call, whatever the gap: 6.0 - 0.25 = 5.75 h, 83 - 2 = 81 min.
        assertEquals(5.75, view.effective.diaHours, 1e-9)
        assertEquals(81.0, view.effective.peakMinutes, 1e-9)
    }

    // --- Rate limit counts time, not ticks (step P1c) ---

    @Test
    fun oneMinuteTicksMoveOneFifthOfTheStep() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        val first = limiter.limit(nowMs = minute, resolved = mealTarget())
        assertEquals(5.95, first.effective.diaHours, 1e-9)
        assertEquals(82.6, first.effective.peakMinutes, 1e-9)
        assertEquals(4.2, first.targetEffective.diaHours, 1e-9)
        // Five 1-minute ticks move as far as one 5-minute tick.
        var view = first
        for (i in 2..5) view = limiter.limit(nowMs = i * minute, resolved = mealTarget())
        assertEquals(5.75, view.effective.diaHours, 1e-9)
        assertEquals(81.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun twoCallsInTheSameMinuteDoNotDoubleTheStep() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        limiter.limit(nowMs = 30_000L, resolved = mealTarget())
        val view = limiter.limit(nowMs = minute, resolved = mealTarget())
        // Together the two calls get the step of 1 minute, not 2 steps of 1 minute.
        assertEquals(5.95, view.effective.diaHours, 1e-9)
        assertEquals(82.6, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun tenMinuteGapStillMovesOneStepAtMost() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        val view = limiter.limit(nowMs = 10 * minute, resolved = mealTarget())
        assertEquals(5.75, view.effective.diaHours, 1e-9)
        assertEquals(81.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun earlyFiveMinuteTickGetsItsShareOfTheStep() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        // The loop runs on the wall clock, so a 5-minute loop jitters: a gap of 4 min 50 s
        // allows 290 / 300 = 0.967 of a step, not a full step.
        val view = limiter.limit(nowMs = 290_000L, resolved = mealTarget())
        assertEquals(6.0 - 0.25 * 290.0 / 300.0, view.effective.diaHours, 1e-9)
        assertEquals(83.0 - 2.0 * 290.0 / 300.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun gapOfZeroAllowsNoMove() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = tick, resolved = stressTarget())
        val view = limiter.limit(nowMs = tick, resolved = mealTarget())
        assertEquals(6.0, view.effective.diaHours, 1e-9)
        assertEquals(83.0, view.effective.peakMinutes, 1e-9)
        assertEquals(4.2, view.targetEffective.diaHours, 1e-9)
    }

    @Test
    fun negativeGapAllowsNoMove() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = tick, resolved = stressTarget())
        val view = limiter.limit(nowMs = tick - minute, resolved = mealTarget())
        assertEquals(6.0, view.effective.diaHours, 1e-9)
        assertEquals(83.0, view.effective.peakMinutes, 1e-9)
        // The clock starts again from the earlier time: one minute later the step is 1/5 again.
        val next = limiter.limit(nowMs = tick, resolved = mealTarget())
        assertEquals(5.95, next.effective.diaHours, 1e-9)
    }

    @Test
    fun exportLogsTheTargetAndTheLimitedValue() {
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = stressTarget())
        val dosing = limiter.limit(nowMs = tick, resolved = mealTarget())
        val snapshot = AimiIntelligenceSnapshotBuilder.build(
            AimiIntelligenceSnapshotBuilder.BuildInput(
                timestampMs = tick,
                dosingKinetics = dosing,
                profile = zeroedAimiProfile(peakTime = 75.0).copy(dia = 5.0),
                pkpdRuntime = null,
                causalPosterior = null,
                learningDiagnostics = diagnostics,
            ),
        )
        val kinetics = IntelligenceSnapshotJson.toJsonObject(snapshot)["kinetics"] as JsonObject
        assertEquals(5.75, kinetics["effective_dia_h"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(4.2, kinetics["effective_dia_target_h"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(81.0, kinetics["effective_peak_min"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(72.0, kinetics["effective_peak_target_min"]!!.jsonPrimitive.double, 1e-9)
    }

    // --- Pace migration ---

    @Test
    fun oldWizardNormalPaceMovesToFasterOnce() {
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 5.0
        assertTrue(PkpdLearningBounds.migrateOldWizardPaceOnce(preferences))
        assertEquals(2.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH), 1e-9)
        assertEquals(20.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin), 1e-9)
        assertTrue(preferences.get(BooleanNonKey.OApsAIMIPkpdPaceMigrated))

        // A user who picks NORMAL again after the migration keeps it.
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 5.0
        assertFalse(PkpdLearningBounds.migrateOldWizardPaceOnce(preferences))
        assertEquals(0.5, preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH), 1e-9)
        assertEquals(5.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin), 1e-9)
    }

    @Test
    fun anyOtherStoredPaceIsKept() {
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 10.0
        assertFalse(PkpdLearningBounds.migrateOldWizardPaceOnce(preferences))
        assertEquals(0.5, preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH), 1e-9)
        assertEquals(10.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin), 1e-9)
        // It ran: the flag is set, so it never looks again.
        assertTrue(preferences.get(BooleanNonKey.OApsAIMIPkpdPaceMigrated))
    }

    @Test
    fun nothingStoredWritesNothing() {
        assertFalse(PkpdLearningBounds.migrateOldWizardPaceOnce(preferences))
        assertTrue(preferences.doublePuts.isEmpty())
        assertTrue(preferences.get(BooleanNonKey.OApsAIMIPkpdPaceMigrated))
    }

    @Test
    fun aBackupMadeAfterTheUpdateKeepsADeliberateNormalPace() {
        // Owner decision of step D1: the flag is exported, so a backup made after the update carries
        // it. Restored on a new phone, the flag arrives with the pace and the NORMAL pace stays.
        assertTrue(BooleanNonKey.OApsAIMIPkpdPaceMigrated.exportable)
        preferences.put(BooleanNonKey.OApsAIMIPkpdPaceMigrated, true)
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 5.0
        assertFalse(PkpdLearningBounds.migrateOldWizardPaceOnce(preferences))
        assertEquals(0.5, preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH), 1e-9)
        assertEquals(5.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin), 1e-9)
    }
}
