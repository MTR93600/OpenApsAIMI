package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ContraceptiveType
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineApplicationMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineDosePathOwner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.VerneuilStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhysiologicalTreeBuilderTest {

    @Test
    fun build_returnsNullWhenDisabled() {
        val state = stableState()
        val tree = PhysiologicalTreeBuilder.build(
            enabled = false,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

        assertNull(tree)
    }

    @Test
    fun build_createsStableTreeWithMinimalCoherentData() {
        val state = stableState()
        val tree = buildTree(state)

        assertNotNull(tree)
        assertEquals(GlobalPhysiologicalState.STABLE, tree?.trunk?.globalState)
        assertEquals(DataCoherenceLevel.GOOD, tree?.trunk?.dataCoherence)
        assertTrue(tree?.compactSummary?.contains("Tree:") == true)
        assertTrue(tree?.compactSummary?.contains("sensor ok") == true)
    }

    @Test
    fun build_degradesWhenSensorConfidenceIsLow() {
        val state = stableState().copy(sensorConfidence = 0.22)
        val tree = buildTree(state)

        assertEquals(GlobalPhysiologicalState.SENSOR_UNCERTAIN, tree?.trunk?.globalState)
        assertEquals(DataCoherenceLevel.LOW, tree?.trunk?.dataCoherence)
        assertEquals(false, tree?.branches?.sensorTrust?.detected)
        assertTrue(tree?.leaves?.safetyNotes?.joinToString()?.contains("Sensor uncertainty") == true)
    }

    @Test
    fun build_marksMealAndDigestionBranchesForFirstWave() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 0.78,
            mealProb = 0.82,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.84,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        val tree = buildTree(state)

        assertEquals(true, tree?.branches?.digestion?.detected)
        assertEquals(true, tree?.branches?.meal?.detected)
        assertEquals(GlobalPhysiologicalState.DIGESTION_ACTIVE, tree?.trunk?.globalState)
        assertTrue(tree?.leaves?.mealAdvisorHints?.joinToString()?.contains("Meal evidence") == true)
    }

    @Test
    fun build_marksResistanceWhenEndogenousAndStressSignalsDominate() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.62,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.86,
                stressResistanceProb = 0.48,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.86,
                learningQuality = 0.70,
            ),
            falseMealSuppression = true,
        )
        val tree = buildTree(state)

        assertEquals(true, tree?.branches?.hormonalResistance?.detected)
        assertEquals(GlobalPhysiologicalState.RESISTANCE_PROBABLE, tree?.trunk?.globalState)
        assertTrue(tree?.compactSummary?.contains("resistance probable") == true)
    }

    @Test
    fun build_keepsHypoRecoveryAsHighPriorityContext() {
        val state = stableState().copy(
            postHypoReboundProb = 0.72,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.78,
                dominant = CausalStateId.POST_HYPO_RECOVERY,
                dominantConfidence = 0.78,
                learningQuality = 0.40,
            ),
            eventMemory = PatientEventMemory(
                recentHypoLoad = 0.70,
                correctionFragilityScore = 0.66,
            ),
        )
        val tree = buildTree(state)

        assertEquals(true, tree?.branches?.hypoRisk?.detected)
        assertEquals(GlobalPhysiologicalState.HYPO_RISK, tree?.trunk?.globalState)
        assertTrue((tree?.trunk?.riskLevel)!! >= PhysiologicalRiskLevel.HIGH)
        assertTrue(tree?.leaves?.noActionReasons?.contains("hypo_or_recovery_risk") == true)
    }

    @Test
    fun build_prefersHyperTrunkWhenStaleMemoryHypoDuringActiveHyperCorrection() {
        val state = stableState().copy(
            postHypoReboundProb = 0.0,
            mealProb = 0.90,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.90,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.90,
                learningQuality = 0.70,
            ),
            eventMemory = PatientEventMemory(
                recentHypoLoad = 1.0,
                recentHyperLoad = 0.70,
                correctionFragilityScore = 0.60,
                postHyperExhaustionScore = 0.80,
            ),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            currentBgMgdl = 294.0,
            deltaMgdl5m = 8.0,
        )

        assertNotEquals(GlobalPhysiologicalState.HYPO_RISK, tree?.trunk?.globalState)
        assertNotEquals(PhysiologicalRiskLevel.CRITICAL, tree?.trunk?.riskLevel)
    }

    @Test
    fun build_handlesMissingWearableAndMlWithoutCrash() {
        val state = stableState().copy(
            sleepDebtScore = 0.40,
            eventMemory = PatientEventMemory(postHyperExhaustionScore = 0.45),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            physioLive = PhysioLiveDigest(),
            thermalBelief = ThermalBeliefDigest(
                hypothesis = ThermalHypothesis.DATA_PENDING,
                confidence = 0.0,
            ),
        )

        assertNotNull(tree)
        assertTrue(tree?.roots?.mlAsyncState?.safetyImpact?.contains("no direct dosing") == true)
        assertEquals(true, tree?.branches?.sleepRecovery?.detected)
    }

    @Test
    fun toJsonObject_exportsContextOnlyWithoutInsulinCommandFields() {
        val json = buildTree(stableState())!!.toJsonObject()
        val serialized = json.toString()

        assertEquals("harmonia_basal_and_smb_arbitration", json["insulin_authority"]?.jsonPrimitive?.content)
        assertEquals(true, json.containsKey("insulin_intent"))
        assertFalse(serialized.contains("\"smb_u\""))
        assertFalse(serialized.contains("tbr_uph"))
        assertFalse(serialized.contains("bolus_u"))
    }

    @Test
    fun build_marksHormonalBranchFromWCycleLutealBelief() {
        val belief = sampleLutealBelief(effectiveBasal = 1.22, hypoDampen = 1.0, hypoLoad = 0.0)
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = stableState(),
            patientModeDecision = PatientModeOrchestrator.evaluate(stableState()),
            wCycleBelief = belief,
        )
        assertEquals(true, tree?.branches?.hormonalResistance?.detected)
        assertTrue(tree?.branches?.hormonalResistance?.label?.contains("LUTEAL") == true)
        assertTrue(tree?.branches?.hormonalResistance?.reasons?.joinToString()?.contains("wcycle_phase=LUTEAL") == true)
        assertTrue(tree?.seasons?.hormonalPattern?.contains("wcycle_luteal") == true)
    }

    @Test
    fun build_hormonalSafetyNotesProtectWhenHypoLoadHigh() {
        val belief = sampleLutealBelief(effectiveBasal = 1.05, hypoDampen = 0.3, hypoLoad = 0.8, hypoGuard = true)
        val state = stableState().copy(
            eventMemory = PatientEventMemory(recentHypoLoad = 0.70, correctionFragilityScore = 0.50),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            wCycleBelief = belief,
        )
        assertTrue(tree?.branches?.hormonalResistance?.safetyImpact?.contains("protect") == true)
    }

    private fun sampleLutealBelief(
        effectiveBasal: Double,
        hypoDampen: Double,
        hypoLoad: Double,
        hypoGuard: Boolean = false,
    ): WCycleBelief =
        WCycleBelief(
            enabled = true,
            phase = CyclePhase.LUTEAL,
            dayInCycle = 21,
            trackingMode = CycleTrackingMode.CALENDAR_VARIABLE,
            contraceptive = ContraceptiveType.COPPER_IUD,
            thyroid = ThyroidStatus.EUTHYROID,
            verneuil = VerneuilStatus.ACTIVE,
            applicationMode = EndocrineApplicationMode.APPLIED,
            ampContraceptive = 1.0,
            ampTrackingMode = 1.0,
            ampCombined = 1.0,
            dawnBias = 1.0,
            intendedBasalAmp = 1.25,
            intendedSmbAmp = 1.12,
            intendedIcAmp = 1.15,
            hypoLoad = hypoLoad,
            hypoLoadDampen = hypoDampen,
            hypoGuardActive = hypoGuard,
            inflamSharedBudgetHint = 1.05,
            effectiveBasalAmp = effectiveBasal,
            effectiveSmbAmp = 1.0 + (1.12 - 1.0) * hypoDampen,
            effectiveIcAmp = 1.0 + (1.15 - 1.0) * hypoDampen,
            legacyDoseBasalAmp = 1.25,
            legacyDoseSmbAmp = 1.12,
            legacyDoseIcAmp = 1.15,
            dosePathOwner = EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT,
            confidence = 0.72,
            reasons = listOf("test"),
        )

    // --- insulin intent: a confirmed meal versus the activity veto (2026-08-10 lunch) ---

    /** Reproduces the 12:36 tick: BG 237, Δ 20.1, meal + digestion at 1.0, post-activity 0.79. */
    private fun undeclaredMealAtBg237(): PhysiologicalTreeSnapshot =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = mealState(),
            patientModeDecision = PatientModeOrchestrator.evaluate(mealState()),
            currentBgMgdl = 237.0,
            deltaMgdl5m = 20.1,
            effortRecentConfidence = 0.79,
        )!!

    @Test
    fun insulinIntent_postActivityVetoesAConfirmedMealRise() {
        val tree = undeclaredMealAtBg237()

        assertTrue(tree.branches.hypoRisk.confidence < 0.55)
        assertEquals(0.79, tree.branches.postActivity.confidence, 1e-9)
        assertEquals(InsulinIntent.PROTECTIVE, tree.insulinIntent)
    }

    @Test
    fun withMealCertainty_highMealOutranksThePostActivityVeto() {
        val revised = PhysiologicalTreeBuilder.withMealCertainty(
            snapshot = undeclaredMealAtBg237(),
            mealOverridesProtective = true,
            deltaMgdl5m = 20.1,
            currentBgMgdl = 237.0,
        )

        assertEquals(InsulinIntent.NEED_MORE_INSULIN, revised.insulinIntent)
        assertTrue(revised.insulinUrgency > 0.0)
    }

    @Test
    fun withMealCertainty_leavesTheSnapshotAloneWhenTheMealIsNotConfirmed() {
        val tree = undeclaredMealAtBg237()
        val revised = PhysiologicalTreeBuilder.withMealCertainty(
            snapshot = tree,
            mealOverridesProtective = false,
            deltaMgdl5m = 20.1,
            currentBgMgdl = 237.0,
        )

        assertSame(tree, revised)
    }

    @Test
    fun withMealCertainty_neverOverridesTheHypoGate() {
        val hypoState = mealState().copy(postHypoReboundProb = 0.80)
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = hypoState,
            patientModeDecision = PatientModeOrchestrator.evaluate(hypoState),
            currentBgMgdl = 237.0,
            deltaMgdl5m = 20.1,
            effortRecentConfidence = 0.79,
        )!!
        val revised = PhysiologicalTreeBuilder.withMealCertainty(
            snapshot = tree,
            mealOverridesProtective = true,
            deltaMgdl5m = 20.1,
            currentBgMgdl = 237.0,
        )

        assertEquals(InsulinIntent.PROTECTIVE, revised.insulinIntent)
    }

    private fun mealState(): PatientStateSnapshot =
        stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 1.0,
            mealProb = 1.0,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 1.0,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 1.0,
                learningQuality = 0.80,
            ),
        )

    private fun buildTree(state: PatientStateSnapshot): PhysiologicalTreeSnapshot? =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

    private fun stableState(): PatientStateSnapshot =
        PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            phase = PhysiologicalPhase.OFF,
            phaseConfidence = 0.50,
            mealAbsorptionPhase = MealAbsorptionPhase.NONE,
            mealAbsorptionBelief = 0.0,
            mealProb = 0.08,
            endogenousGlucoseDrive = 0.10,
            transientResistanceProb = 0.08,
            sleepDebtScore = 0.0,
            postHypoReboundProb = 0.0,
            sensorConfidence = 0.88,
            causalPosterior = CausalStatePosterior(
                dominant = CausalStateId.UNKNOWN,
                dominantConfidence = 0.0,
                learningQuality = 0.86,
            ),
        )
}
