package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityWindow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinKineticsAuthority
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.pkpd.zeroedAimiProfile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The DIA and peak written to the log must be the ones that dosed, and the dosing ones must follow
 * the causal context of the previous tick.
 *
 * Before the fix the plugin resolved the kinetics with no causal context and the snapshot resolved
 * them again with the context of the tick, so under stress the log said DIA 6.0 h while 5.0 h dosed.
 */
class KineticsSingleSourceTest {

    private val preferences = MapPreferences()

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

    /** What OpenAPSAIMIPlugin resolves before determine_basal, with the posterior of the previous tick. */
    private fun dosingKinetics(previousTickPosterior: CausalStatePosterior?) =
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

    private fun snapshot(dosing: InsulinKineticsView, thisTickPosterior: CausalStatePosterior?) =
        AimiIntelligenceSnapshotBuilder.build(
            AimiIntelligenceSnapshotBuilder.BuildInput(
                timestampMs = 0L,
                dosingKinetics = dosing,
                profile = zeroedAimiProfile(peakTime = 75.0).copy(dia = 5.0),
                pkpdRuntime = null,
                causalPosterior = thisTickPosterior,
                learningDiagnostics = diagnostics,
            ),
        )

    @Test
    fun snapshotLogsTheDiaThatDosed() {
        val dosing = dosingKinetics(previousTickPosterior = stress)
        // The posterior of this tick is different (a meal): the log must not resolve again with it.
        val snap = snapshot(dosing, thisTickPosterior = meal)
        assertEquals(dosing.effective.diaHours, snap.kinetics.effective.diaHours)
        assertEquals(dosing.effective.peakMinutes, snap.kinetics.effective.peakMinutes)
        assertEquals(dosing.effective.diaHours, snap.mlFeatures.effectiveDiaHours)
    }

    @Test
    fun dosingDiaAndPeakFollowTheCausalShiftOfThePreviousTick() {
        val dosing = dosingKinetics(previousTickPosterior = stress)
        // Stress at full confidence: DIA +1.0 h, peak +8 min. PK/PD is off by default.
        assertEquals(6.0, dosing.effective.diaHours, 1e-9)
        assertEquals(83.0, dosing.effective.peakMinutes, 1e-9)
        assertEquals(5.0, dosing.noContextEffective.diaHours, 1e-9)
        assertEquals(75.0, dosing.noContextEffective.peakMinutes, 1e-9)
    }

    @Test
    fun withoutPosteriorDosingIsTheSameAsBefore() {
        val dosing = dosingKinetics(previousTickPosterior = null)
        assertEquals(5.0, dosing.effective.diaHours, 1e-9)
        assertEquals(75.0, dosing.effective.peakMinutes, 1e-9)
        assertEquals(dosing.effective, dosing.noContextEffective)
        assertEquals("no_posterior", dosing.contextReason)
    }

    @Test
    fun exportShowsDosingAndNoContextValues() {
        val json = IntelligenceSnapshotJson.toJsonObject(snapshot(dosingKinetics(stress), thisTickPosterior = null))
        val kinetics = json["kinetics"] as JsonObject
        assertEquals(6.0, kinetics["effective_dia_h"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(5.0, kinetics["effective_dia_no_context_h"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(83.0, kinetics["effective_peak_min"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(75.0, kinetics["effective_peak_no_context_min"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun causalPeakShiftIsReportedAsContextNotAsTrajectory() {
        val peak = dosingKinetics(previousTickPosterior = stress).peakGovernor
        // Same dosing peak as before: profile 75 + stress 8.
        assertEquals(83.0, peak.effectivePeakMinutes, 1e-9)
        // But the 8 minutes are the causal context, not a trajectory nudge.
        assertEquals(0.0, peak.peakTrajectory, 1e-9)
        assertEquals(8.0, peak.peakContext, 1e-9)
        assertEquals("CONTEXT", peak.dominantBranch)
    }

    @Test
    fun exportNamesTheKineticsContextReasonApartFromTheCausalBlock() {
        val dosing = dosingKinetics(previousTickPosterior = stress)
        val json = IntelligenceSnapshotJson.toJsonObject(snapshot(dosing, thisTickPosterior = meal))
        val kinetics = json["kinetics"] as JsonObject
        assertEquals(dosing.contextReason, kinetics["kinetics_context_reason"]!!.jsonPrimitive.content)
        assertNull(kinetics["context_reason"])
    }

    @Test
    fun snapshotStructuralIsTheRuntimeOfThisTick() {
        // The plugin dosed with no runtime; determine_basal then learned and passes its runtime.
        val snap = AimiIntelligenceSnapshotBuilder.build(
            AimiIntelligenceSnapshotBuilder.BuildInput(
                timestampMs = 0L,
                dosingKinetics = dosingKinetics(previousTickPosterior = null),
                profile = zeroedAimiProfile(peakTime = 75.0).copy(dia = 5.0),
                pkpdRuntime = runtime(PkPdParams(diaHrs = 4.0, peakMin = 70.0)),
                causalPosterior = null,
                learningDiagnostics = diagnostics,
            ),
        )
        assertEquals(4.0, snap.kinetics.structural.diaHrs, 1e-9)
        assertEquals(70.0, snap.kinetics.structural.peakMin, 1e-9)
        // The DIA and peak stay the ones that dosed.
        assertEquals(5.0, snap.kinetics.effective.diaHours, 1e-9)
    }

    @Test
    fun dosingValuesOnlyViewDoesNotExportGovernorBlocks() {
        val view = InsulinKineticsAuthority.fromDosingValues(
            resolved = dosingKinetics(previousTickPosterior = null),
            diaHours = 4.5,
            peakMinutes = 70.0,
        )
        assertEquals(4.5, view.effective.diaHours, 1e-9)
        assertEquals(70.0, view.effective.peakMinutes, 1e-9)
        assertFalse(view.governorsDosed)
        val kinetics = IntelligenceSnapshotJson.toJsonObject(snapshot(view, thisTickPosterior = null))["kinetics"] as JsonObject
        assertNull(kinetics["peak_governor"])
        assertNull(kinetics["dia_governor"])
        assertEquals(false, kinetics["governors_dosed"]!!.jsonPrimitive.boolean)
        assertEquals(4.5, kinetics["effective_dia_h"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun governorBlocksLogTheLimitedValueThatDosedAndTheTarget() {
        // Stress target 6.0 h; the rate limit let only 5.25 h dose (DosingKineticsRateLimiter).
        val resolved = dosingKinetics(previousTickPosterior = stress)
        val limited = resolved.copy(
            effective = resolved.effective.copy(diaHours = 5.25, peakMinutes = resolved.effective.peakMinutes - 2.0),
            targetEffective = resolved.effective,
        )
        val kinetics = IntelligenceSnapshotJson.toJsonObject(snapshot(limited, thisTickPosterior = null))["kinetics"] as JsonObject
        val dia = kinetics["dia_governor"] as JsonObject
        assertEquals(5.25, dia["effective_dia_h"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(6.0, dia["effective_dia_target_h"]!!.jsonPrimitive.double, 1e-9)
        val peak = kinetics["peak_governor"] as JsonObject
        assertEquals(limited.effective.peakMinutes, peak["effective_peak_min"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(resolved.peakGovernor.effectivePeakMinutes, peak["effective_peak_target_min"]!!.jsonPrimitive.double, 1e-9)
    }

    private fun runtime(params: PkPdParams) = PkPdRuntime(
        params = params,
        tailFraction = 0.2,
        fusedIsf = 50.0,
        profileIsf = 50.0,
        tddIsf = 50.0,
        pkpdScale = 1.0,
        weightKineticFactor = 1.0,
        physioAbsorptionFactor = 1.0,
        physioSiFactor = 1.0,
        damping = SmbDamping(),
        activity = InsulinActivityState(
            window = InsulinActivityWindow(onsetMin = 20.0, peakMin = 70.0, offsetMin = 180.0, diaMin = 240.0),
            relativeActivity = 0.5,
            normalizedPosition = 0.4,
            postWindowFraction = 0.1,
            anticipationWeight = 0.1,
            minutesUntilOnset = 0.0,
            stage = InsulinActivityStage.RISING,
        ),
    )
}
