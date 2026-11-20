package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step P1d: the causal context moves the dosing DIA and peak only when PK/PD is on.
 *
 * dev_OAPSAIMI resolved the dosing kinetics with no posterior, so the context never dosed there. With
 * PK/PD off (the default) the shift must be 0 again, so the default setup doses like dev. With PK/PD on
 * nothing changes. Profile DIA 5 h, peak 75 min in every test.
 */
class KineticsContextPkpdGateTest {

    private val preferences = MapPreferences()
    private val minute = 60_000L
    private val tick = 5 * minute

    private fun posterior(state: CausalStateId) = CausalStatePosterior(
        fastMealProb = if (state == CausalStateId.FAST_MEAL) 1.0 else 0.0,
        prolongedMealProb = if (state == CausalStateId.PROLONGED_MEAL) 1.0 else 0.0,
        postHypoRecoveryProb = if (state == CausalStateId.POST_HYPO_RECOVERY) 1.0 else 0.0,
        stressResistanceProb = if (state == CausalStateId.STRESS_RESISTANCE) 1.0 else 0.0,
        exerciseAfterburnProb = if (state == CausalStateId.EXERCISE_AFTERBURN) 1.0 else 0.0,
        inflammatoryDriftProb = if (state == CausalStateId.INFLAMMATORY_DRIFT) 1.0 else 0.0,
        dominant = state,
        dominantConfidence = 1.0,
        learningQuality = 1.0,
    )

    private val stress = posterior(CausalStateId.STRESS_RESISTANCE)
    private val meal = posterior(CausalStateId.FAST_MEAL)

    private val diagnostics = PkpdLearningDiagnostics.from(
        causalStatePosterior = null,
        allowLearning = false,
        exerciseFlag = false,
        iobU = 0.0,
        carbsActiveG = 0.0,
        bg = 120.0,
        deltaMgDlPer5 = 0.0,
    )

    private fun resolve(
        previousTickPosterior: CausalStatePosterior?,
        sitePeakShiftMinutes: Double = 0.0,
        trajectoryPeakNudgeMinutes: Double = 0.0,
    ): InsulinKineticsView =
        InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray<IobTotal>(),
                profileDiaHours = 5.0,
                profilePeakMinutes = 75,
                pkpdRuntime = null,
                causalPosterior = previousTickPosterior,
                sitePeakShiftMinutes = sitePeakShiftMinutes,
                trajectoryPeakNudgeMinutes = trajectoryPeakNudgeMinutes,
                preferences = preferences,
                learningDiagnostics = diagnostics,
            ),
        )

    // --- PK/PD off (default) ---

    @Test
    fun pkpdOffDosesTheProfileUnderStress() {
        val view = resolve(previousTickPosterior = stress)
        assertEquals(5.0, view.effective.diaHours, 1e-9)
        assertEquals(75.0, view.effective.peakMinutes, 1e-9)
        assertEquals(view.noContextEffective, view.effective)
        assertEquals(0.0, view.diaGovernor.diaContextualShiftHours, 1e-9)
        assertEquals(0.0, view.peakGovernor.peakContext, 1e-9)
    }

    @Test
    fun pkpdOffGivesNoShiftForAnyCausalState() {
        val states = listOf(
            CausalStateId.POST_HYPO_RECOVERY,
            CausalStateId.STRESS_RESISTANCE,
            CausalStateId.INFLAMMATORY_DRIFT,
            CausalStateId.FAST_MEAL,
            CausalStateId.PROLONGED_MEAL,
            CausalStateId.EXERCISE_AFTERBURN,
        )
        for (state in states) {
            val view = resolve(previousTickPosterior = posterior(state))
            assertEquals(view.noContextEffective, view.effective, "state $state")
            assertEquals(5.0, view.effective.diaHours, 1e-9, "state $state")
            assertEquals(75.0, view.effective.peakMinutes, 1e-9, "state $state")
        }
    }

    @Test
    fun pkpdOffIgnoresContextWithBothGovernorsOn() {
        preferences.booleans[BooleanKey.OApsAIMIDiaGovernorEnabled] = true
        preferences.booleans[BooleanKey.OApsAIMIPeakGovernorEnabled] = true
        val view = resolve(previousTickPosterior = meal)
        assertEquals(5.0, view.effective.diaHours, 1e-9)
        assertEquals(75.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun pkpdOffNamesTheIgnoredContextInTheReason() {
        val view = resolve(previousTickPosterior = stress)
        assertTrue(
            view.contextReason.startsWith(InsulinKineticsAuthority.CONTEXT_PKPD_OFF_PREFIX + "stress_delayed_peak"),
            "reason was ${view.contextReason}",
        )
    }

    @Test
    fun pkpdOffWithoutPosteriorSaysNoPosterior() {
        assertEquals("no_posterior", resolve(previousTickPosterior = null).contextReason)
    }

    @Test
    fun pkpdOffKeepsSiteAndTrajectoryPeakShifts() {
        val view = resolve(previousTickPosterior = stress, sitePeakShiftMinutes = 6.0, trajectoryPeakNudgeMinutes = 3.0)
        // Only the causal part goes away: 75 + 6 (site) + 3 (trajectory) = 84 min, without the stress 8 min.
        assertEquals(84.0, view.effective.peakMinutes, 1e-9)
        assertEquals(view.noContextEffective, view.effective)
    }

    // --- PK/PD on: unchanged ---

    @Test
    fun pkpdOnKeepsTheStressShift() {
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        val view = resolve(previousTickPosterior = stress)
        // (5.0 + 1.0) x 0.55 + 5.0 x 0.45 (profile DIA as learned DIA, no runtime) = 5.55 h; peak 75 + 8.
        assertEquals(5.55, view.effective.diaHours, 1e-9)
        assertEquals(83.0, view.effective.peakMinutes, 1e-9)
        assertTrue(view.contextReason.startsWith("stress_delayed_peak"), "reason was ${view.contextReason}")
    }

    @Test
    fun pkpdOnKeepsTheMealShift() {
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        val view = resolve(previousTickPosterior = meal)
        // (5.0 - 0.8) x 0.55 + 5.0 x 0.45 = 4.56 h; peak 75 - 3.
        assertEquals(4.56, view.effective.diaHours, 1e-9)
        assertEquals(72.0, view.effective.peakMinutes, 1e-9)
    }

    // --- Turning PK/PD off goes back through the rate limit ---

    @Test
    fun turningPkpdOffWalksTheDosingDiaBackStepByStep() {
        val limiter = DosingKineticsRateLimiter()
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        val on = limiter.limit(nowMs = 0L, resolved = resolve(previousTickPosterior = stress))
        assertEquals(5.55, on.effective.diaHours, 1e-9)
        assertEquals(83.0, on.effective.peakMinutes, 1e-9)

        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = false
        val expectedDia = listOf(5.30, 5.05, 5.0)
        val expectedPeak = listOf(81.0, 79.0, 77.0)
        for (i in 1..3) {
            val view = limiter.limit(nowMs = i * tick, resolved = resolve(previousTickPosterior = stress))
            assertEquals(5.0, view.targetEffective.diaHours, 1e-9, "tick $i")
            assertEquals(expectedDia[i - 1], view.effective.diaHours, 1e-9, "tick $i")
            assertEquals(expectedPeak[i - 1], view.effective.peakMinutes, 1e-9, "tick $i")
        }
        val last = limiter.limit(nowMs = 4 * tick, resolved = resolve(previousTickPosterior = stress))
        assertEquals(75.0, last.effective.peakMinutes, 1e-9)
    }

    @Test
    fun pkpdOffProfileDiaChangeIsRateLimited() {
        // Not exact parity with dev: dev applied a new profile DIA at once, here it walks 0.25 h per 5 min.
        val limiter = DosingKineticsRateLimiter()
        limiter.limit(nowMs = 0L, resolved = resolve(previousTickPosterior = null))
        val longer = InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray<IobTotal>(),
                profileDiaHours = 7.0,
                profilePeakMinutes = 75,
                pkpdRuntime = null,
                causalPosterior = null,
                preferences = preferences,
                learningDiagnostics = diagnostics,
            ),
        )
        val view = limiter.limit(nowMs = tick, resolved = longer)
        assertEquals(7.0, view.targetEffective.diaHours, 1e-9)
        assertEquals(5.25, view.effective.diaHours, 1e-9)
    }
}
