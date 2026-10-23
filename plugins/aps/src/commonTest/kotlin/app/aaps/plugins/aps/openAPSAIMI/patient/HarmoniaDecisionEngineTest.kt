package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ContraceptiveType
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineApplicationMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineDosePathOwner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.VerneuilStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of `HarmoniaDecisionEngineTest` from `dev_OAPSAIMI` (`plugins/aps/src/test`).
 *
 * [HarmoniaDecisionEngine], the tree builder and the mode orchestrator are all in commonMain and
 * pure, so the test runs on every target. Adapted: Truth and JUnit replaced by `kotlin.test`, names
 * in camelCase, and the `org.json` reads (`getBoolean`, `getString`, `getDouble`, `getJSONObject`)
 * replaced by kotlinx serialization reads of the same keys. The cases and the numbers are the same
 * as on dev; the engine logic is the same as on dev (only JSON and number formatting differ).
 */
class HarmoniaDecisionEngineTest {

    @Test
    fun evaluatePrefersProtectiveWhenHormonalResistanceAndHypoRisk() {
        val state = stableState().copy(
            endogenousGlucoseDrive = 0.70,
            eventMemory = PatientEventMemory(
                recentHypoLoad = 0.35,
                correctionFragilityScore = 0.20,
            ),
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.60,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.60,
                learningQuality = 0.70,
            ),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            wCycleBelief = WCycleBelief(
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
                hypoLoad = 0.35,
                hypoLoadDampen = 0.70,
                hypoGuardActive = true,
                inflamSharedBudgetHint = 1.05,
                effectiveBasalAmp = 1.18,
                effectiveSmbAmp = 1.08,
                effectiveIcAmp = 1.10,
                legacyDoseBasalAmp = 1.25,
                legacyDoseSmbAmp = 1.12,
                legacyDoseIcAmp = 1.15,
                dosePathOwner = EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT,
                confidence = 0.72,
                reasons = listOf("test"),
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(currentBgMgdl = 145.0, deltaMgdl5m = 0.0),
        )

        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, decision?.action)
        assertEquals("hormonal_with_hypo_risk", decision?.decisionBasis?.primaryReason)
    }

    @Test
    fun evaluateReturnsBasalFirstForResistanceWithoutPumpAuthority() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.70,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.62,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment(),
        )

        assertTrue((decision?.eligible)!!)
        assertEquals(HarmoniaAction.BASAL_FIRST, decision?.action)
        assertEquals(1.18, (decision?.basalFactor)!!, 0.001)
        assertEquals(0.0, decision?.targetSmbU)
        assertEquals(false, decision?.toJsonObject()?.get("applies_to_pump")?.jsonPrimitive?.boolean)
        assertTrue(((decision?.compactSummary)!!).contains("Harmonia sim:"))
    }

    @Test
    fun evaluateBasalFirstHonorsGovernorHardUnityWithoutFallback118() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
            eventMemory = PatientEventMemory(recentHypoLoad = 0.10),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                currentBasalUph = 1.0,
                endocrineBasalAmp = 1.0,
            ),
        )

        assertEquals(HarmoniaAction.BASAL_FIRST, decision?.action)
        assertEquals(1.0, decision?.basalFactor)
        assertEquals(1.0, (decision?.targetBasalUph)!!, 0.001)
    }

    @Test
    fun evaluateBasalFirstUsesGovernorAmpWhenAboveUnity() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
            eventMemory = PatientEventMemory(recentHypoLoad = 0.10),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                currentBasalUph = 1.0,
                endocrineBasalAmp = 1.22,
            ),
        )

        assertEquals(HarmoniaAction.BASAL_FIRST, decision?.action)
        assertEquals(1.22, (decision?.basalFactor)!!, 0.001)
        assertEquals(1.20, (decision?.targetBasalUph)!!, 0.001) // pump step 0.05
    }

    @Test
    fun evaluateBlocksWhenHypoRecoveryOrLowBgIsPresent() {
        val state = stableState().copy(
            postHypoReboundProb = 0.72,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.80,
                dominant = CausalStateId.POST_HYPO_RECOVERY,
                dominantConfidence = 0.80,
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(currentBgMgdl = 76.0, deltaMgdl5m = -1.2),
        )

        assertFalse((decision?.eligible)!!)
        assertEquals(HarmoniaAction.BLOCKED, decision?.action)
        assertEquals(0.0, decision?.targetSmbU)
        assertTrue(((decision?.blockers)!!).contains("hypo_or_recovery"))
    }

    @Test
    fun evaluateBlocksOnlyDuringSensorWarmupWindow() {
        val resistanceState = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.70,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.62,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )

        // Fresh sensor inside the warmup window → blocked.
        val warmup = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(resistanceState),
            environment = safeEnvironment().copy(sensorAgeMin = 30),
        )
        assertFalse((warmup?.eligible)!!)
        assertTrue(((warmup?.blockers)!!).contains("sensor_warmup"))

        // Established sensor (real age in days) → no warmup block.
        val established = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(resistanceState),
            environment = safeEnvironment().copy(sensorAgeMin = 4_000),
        )
        assertFalse(((established?.blockers)!!).contains("sensor_warmup"))
        assertTrue((established?.eligible)!!)

        // Unknown insertion time (no SENSOR_CHANGE event) maps to 0 → treated as established.
        val unknownInsertion = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(resistanceState),
            environment = safeEnvironment().copy(sensorAgeMin = 0),
        )
        assertFalse(((unknownInsertion?.blockers)!!).contains("sensor_warmup"))
        assertTrue((unknownInsertion?.eligible)!!)
    }

    @Test
    fun evaluateCapsBasalAndSmbInsideVirtualPumpLimits() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 0.80,
            mealProb = 0.88,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.90,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.90,
                learningQuality = 0.85,
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                deltaMgdl5m = 4.0,
                currentBasalUph = 4.9,
                maxBasalUph = 5.0,
                maxSmbU = 0.12,
                iobU = 4.50,
                maxIobU = 5.0,
            ),
        )

        assertTrue((decision?.eligible)!!)
        assertTrue(((decision?.targetBasalUph)!!) <= 5.0)
        assertTrue(((decision?.targetSmbU)!!) <= 0.10)
        assertTrue(((decision?.capsApplied)!!).contains("pump_basal_cap_or_step"))
        assertTrue(((decision?.capsApplied)!!).contains("pump_smb_or_iob_cap"))
    }

    @Test
    fun randomizedEnvironmentIsDeterministicForReplay() {
        val first = HarmoniaDecisionEngine.randomizedEnvironment(seed = 42L)
        val second = HarmoniaDecisionEngine.randomizedEnvironment(seed = 42L)

        assertEquals(second, first)
        assertEquals(42L, first.seed)
    }

    @Test
    fun productionDecisionExportsBasalFirstContractWithoutSmbAuthority() {
        val decision = HarmoniaProductionDecision(
            timestampMs = 1_718_000_000_000L,
            mode = HarmoniaProductionMode.APPLIED,
            selectedForProduction = true,
            requestedRateUph = 2.4,
            boundedRateUph = 1.3,
            appliedRateUph = 1.3,
            appliedDurationMin = 30,
            runtimeBlocker = null,
            safetyBlockers = emptyList(),
            sourceAction = HarmoniaAction.BASAL_FIRST,
            branch = "RESISTANT",
            reason = "harmonia_basal_first_applied",
        ).toJsonObject()

        assertTrue(decision.getValue("basal_first_only").jsonPrimitive.boolean)
        assertFalse(decision.getValue("adds_smb_authority").jsonPrimitive.boolean)
        assertTrue(decision.getValue("applies_to_pump").jsonPrimitive.boolean)
        assertEquals("APPLIED", decision.getValue("mode").jsonPrimitive.content)
        assertEquals(1.3, decision.getValue("bounded_rate_uph").jsonPrimitive.double)
    }

    @Test
    fun evaluateFragilityTriggersStabilize() {
        val state = stableState().copy(
            postHypoReboundProb = 0.35,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.20,
                dominant = CausalStateId.UNKNOWN,
                dominantConfidence = 0.0,
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                correctionFragilityScore = 0.62,
                postHyperExhaustionScore = 0.30,
            ),
        )

        assertEquals(HarmoniaAction.STABILIZE, decision?.action)
        assertTrue(((decision?.targetBasalUph)!!) < safeEnvironment().currentBasalUph)
    }

    @Test
    fun evaluateH4MealRiseBridgePrefersMealSupportOverProtectiveDuringDigestion() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.70)
        assertEquals(GlobalPhysiologicalState.DIGESTION_ACTIVE, tree?.trunk?.globalState)
        assertTrue(((tree?.branches?.activity?.confidence)!!) >= 0.55)

        // Below meal band → not HIGH; activity branch wins protective.
        val protective = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = false,
                targetBgMgdl = 100.0,
                currentBgMgdl = 120.0,
                deltaMgdl5m = 4.0,
            ),
        )
        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, protective?.action)

        // Digestion + rise above band → MealCertainty HIGH beats activity (sticky meal_rise not required).
        val bridged = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = false,
                targetBgMgdl = 100.0,
                currentBgMgdl = 190.0,
                deltaMgdl5m = 4.0,
            ),
        )
        assertEquals(HarmoniaAction.MEAL_SUPPORT, bridged?.action)
        assertTrue(((bridged?.rationale)!!).contains("h4_meal_rise_bridge"))
        assertTrue(((bridged?.rationale)!!).contains("meal_certainty_high"))
        assertEquals(MealCertaintyLevel.HIGH, bridged?.mealCertainty?.level)
        assertEquals("meal_certainty_high", bridged?.decisionBasis?.primaryReason)
        assertTrue(((bridged?.smbFactor)!!) > 0.0)
    }

    @Test
    fun evaluateH4MealRiseBridgeDoesNotFireBelowTargetBand() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.70)
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = true,
                targetBgMgdl = 100.0,
                currentBgMgdl = 120.0, // target+20, below +30 band
                deltaMgdl5m = 4.0,
            ),
        )
        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, decision?.action)
        assertFalse(((decision?.rationale)!!).contains("h4_meal_rise_bridge"))
    }

    @Test
    fun evaluateExportsDecisionBasisAlignedWithTrunkBranch() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.70,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.62,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment(),
        )
        val nonNull = assertNotNull(decision)
        assertEquals(nonNull.decisionBasis.trunkState.name, nonNull.branch)
        assertEquals("resistance_or_stress", nonNull.decisionBasis.primaryReason)
        assertTrue(nonNull.decisionBasis.actionCoherentWithTrunk)
        assertEquals(
            nonNull.branch,
            nonNull.toJsonObject().getValue("decision_basis").jsonObject.getValue("trunk_state").jsonPrimitive.content,
        )
        assertEquals(2, nonNull.version)
    }

    @Test
    fun evaluateH4BridgeSetsPrimaryReasonAndContributingDigestion() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.70)
        val decision = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = true,
                targetBgMgdl = 100.0,
                currentBgMgdl = 190.0,
                deltaMgdl5m = 4.0,
            ),
        )
        assertEquals(HarmoniaAction.MEAL_SUPPORT, decision?.action)
        assertEquals("meal_certainty_high", decision?.decisionBasis?.primaryReason)
        assertEquals(GlobalPhysiologicalState.DIGESTION_ACTIVE, decision?.decisionBasis?.trunkState)
        assertEquals(MealCertaintyLevel.HIGH, decision?.mealCertainty?.level)
        assertTrue((decision?.decisionBasis?.contributingBranches?.any { it.name == "digestion" })!!)
    }

    @Test
    fun trunkActionMatrixFlagsHypoMealAsIncoherent() {
        assertFalse(HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.HYPO_RISK,
                HarmoniaAction.MEAL_SUPPORT,
            ),)
        assertTrue(HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.DIGESTION_ACTIVE,
                HarmoniaAction.MEAL_SUPPORT,
            ),)
        assertFalse(HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.SENSOR_UNCERTAIN,
                HarmoniaAction.BASAL_FIRST,
            ),)
    }

    @Test
    fun evaluateH4MealRiseBridgeDoesNotFireOnFallingDelta() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.70)
        // Field replay (KFC peak descent): high BG + meal_rise but negative delta must stay protective.
        val falling = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = true,
                targetBgMgdl = 100.0,
                currentBgMgdl = 280.0,
                deltaMgdl5m = -6.5,
            ),
        )
        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, falling?.action)
        assertFalse(((falling?.rationale)!!).contains("h4_meal_rise_bridge"))

        val flat = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                cobG = 0.0,
                mealRiseConfirmed = true,
                targetBgMgdl = 100.0,
                currentBgMgdl = 280.0,
                deltaMgdl5m = 0.3, // below H4_MIN_RISING_DELTA_MGDL (0.8)
            ),
        )
        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, flat?.action)
        assertFalse(((flat?.rationale)!!).contains("h4_meal_rise_bridge"))
    }

    @Test
    fun evaluateMedMealCertaintySoftensWhenPeakHeavy() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.0)
        val medMeal = MealCertainty(
            level = MealCertaintyLevel.MED,
            treeState = MealCertaintyTreeState.DIGESTION_ACTIVE,
            absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            riseGeometry = MealRiseGeometry.OK,
            terminalsAgree = MealTerminalsAgree.OK,
            effortVeto = false,
            softCorroboration = false,
            reasons = listOf("test_med"),
        )
        val peakHeavy = BodyKineticsDigest(
            effectiveDiaHours = 6.0,
            effectivePeakMinutes = 55.0,
            activityStage = ActivityStage.PEAK,
            peakHeavy = true,
            tailHeavy = false,
            residualEffect = 0.7,
            reason = "PEAK peakHeavy",
        )
        val softened = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                currentBgMgdl = 160.0,
                deltaMgdl5m = 1.5,
                bodyKinetics = peakHeavy,
            ),
            mealCertainty = medMeal,
        )
        assertEquals(HarmoniaAction.STABILIZE, softened?.action)
        assertTrue(((softened?.decisionBasis?.primaryReason)!!).contains("meal_certainty_med_kinetics_peak"))

        val highMeal = medMeal.copy(level = MealCertaintyLevel.HIGH)
        val highStillMeal = HarmoniaDecisionEngine.evaluate(
            tree = tree,
            environment = safeEnvironment().copy(
                currentBgMgdl = 160.0,
                deltaMgdl5m = 1.5,
                bodyKinetics = peakHeavy,
            ),
            mealCertainty = highMeal,
        )
        assertEquals(HarmoniaAction.MEAL_SUPPORT, highStillMeal?.action)
        assertEquals("meal_certainty_high", highStillMeal?.decisionBasis?.primaryReason)
    }

    private fun digestionTreeWithEffort(effortActiveConfidence: Double): PhysiologicalTreeSnapshot? {
        val state = stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 0.78,
            mealProb = 0.82,
            postHypoReboundProb = 0.10,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.84,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        return PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            effortActiveConfidence = effortActiveConfidence,
        )
    }

    private fun buildTree(state: PatientStateSnapshot): PhysiologicalTreeSnapshot? =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

    private fun safeEnvironment(): HarmoniaDecisionEnvironment =
        HarmoniaDecisionEnvironment(
            currentBgMgdl = 190.0,
            deltaMgdl5m = 2.5,
            iobU = 1.0,
            cobG = 10.0,
            currentBasalUph = 1.0,
            maxBasalUph = 5.0,
            maxSmbU = 1.0,
            maxIobU = 5.0,
            sensorAgeMin = 1_500, // established sensor (~25 h), well past the warmup window
            sensorNoise = 0.1,
            targetBgMgdl = 100.0,
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
