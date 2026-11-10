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
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaDecisionEngineTest {

    @Test
    fun evaluate_prefersProtectiveWhenHormonalResistanceAndHypoRisk() {
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

        assertThat(decision?.action).isEqualTo(HarmoniaAction.PROTECTIVE_REDUCTION)
        assertThat(decision?.decisionBasis?.primaryReason).isEqualTo("hormonal_with_hypo_risk")
    }

    @Test
    fun evaluate_returnsBasalFirstForResistanceWithoutPumpAuthority() {
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

        assertThat(decision?.eligible).isTrue()
        assertThat(decision?.action).isEqualTo(HarmoniaAction.BASAL_FIRST)
        assertThat(decision?.basalFactor).isWithin(0.001).of(1.18)
        assertThat(decision?.targetSmbU).isEqualTo(0.0)
        assertThat(decision?.toJsonObject()?.getBoolean("applies_to_pump")).isFalse()
        assertThat(decision?.compactSummary).contains("Harmonia sim:")
    }

    @Test
    fun evaluate_basalFirstHonorsGovernorHardUnityWithoutFallback118() {
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

        assertThat(decision?.action).isEqualTo(HarmoniaAction.BASAL_FIRST)
        assertThat(decision?.basalFactor).isEqualTo(1.0)
        assertThat(decision?.targetBasalUph).isWithin(0.001).of(1.0)
    }

    @Test
    fun evaluate_basalFirstUsesGovernorAmpWhenAboveUnity() {
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

        assertThat(decision?.action).isEqualTo(HarmoniaAction.BASAL_FIRST)
        assertThat(decision?.basalFactor).isWithin(0.001).of(1.22)
        assertThat(decision?.targetBasalUph).isWithin(0.001).of(1.20) // pump step 0.05
    }

    @Test
    fun evaluate_blocksWhenHypoRecoveryOrLowBgIsPresent() {
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

        assertThat(decision?.eligible).isFalse()
        assertThat(decision?.action).isEqualTo(HarmoniaAction.BLOCKED)
        assertThat(decision?.targetSmbU).isEqualTo(0.0)
        assertThat(decision?.blockers).contains("hypo_or_recovery")
    }

    @Test
    fun evaluate_blocksOnlyDuringSensorWarmupWindow() {
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
        assertThat(warmup?.eligible).isFalse()
        assertThat(warmup?.blockers).contains("sensor_warmup")

        // Established sensor (real age in days) → no warmup block.
        val established = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(resistanceState),
            environment = safeEnvironment().copy(sensorAgeMin = 4_000),
        )
        assertThat(established?.blockers).doesNotContain("sensor_warmup")
        assertThat(established?.eligible).isTrue()

        // Unknown insertion time (no SENSOR_CHANGE event) maps to 0 → treated as established.
        val unknownInsertion = HarmoniaDecisionEngine.evaluate(
            tree = buildTree(resistanceState),
            environment = safeEnvironment().copy(sensorAgeMin = 0),
        )
        assertThat(unknownInsertion?.blockers).doesNotContain("sensor_warmup")
        assertThat(unknownInsertion?.eligible).isTrue()
    }

    @Test
    fun evaluate_capsBasalAndSmbInsideVirtualPumpLimits() {
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

        assertThat(decision?.eligible).isTrue()
        assertThat(decision?.targetBasalUph).isAtMost(5.0)
        assertThat(decision?.targetSmbU).isAtMost(0.10)
        assertThat(decision?.capsApplied).contains("pump_basal_cap_or_step")
        assertThat(decision?.capsApplied).contains("pump_smb_or_iob_cap")
    }

    @Test
    fun randomizedEnvironment_isDeterministicForReplay() {
        val first = HarmoniaDecisionEngine.randomizedEnvironment(seed = 42L)
        val second = HarmoniaDecisionEngine.randomizedEnvironment(seed = 42L)

        assertThat(first).isEqualTo(second)
        assertThat(first.seed).isEqualTo(42L)
    }

    @Test
    fun productionDecision_exportsBasalFirstContractWithoutSmbAuthority() {
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

        assertThat(decision.getBoolean("basal_first_only")).isTrue()
        assertThat(decision.getBoolean("adds_smb_authority")).isFalse()
        assertThat(decision.getBoolean("applies_to_pump")).isTrue()
        assertThat(decision.getString("mode")).isEqualTo("APPLIED")
        assertThat(decision.getDouble("bounded_rate_uph")).isEqualTo(1.3)
    }

    @Test
    fun evaluate_fragilityTriggersStabilize() {
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

        assertThat(decision?.action).isEqualTo(HarmoniaAction.STABILIZE)
        assertThat(decision?.targetBasalUph).isLessThan(safeEnvironment().currentBasalUph)
    }

    @Test
    fun evaluate_h4MealRiseBridgePrefersMealSupportOverProtectiveDuringDigestion() {
        val tree = digestionTreeWithEffort(effortActiveConfidence = 0.70)
        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.DIGESTION_ACTIVE)
        assertThat(tree?.branches?.activity?.confidence).isAtLeast(0.55)

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
        assertThat(protective?.action).isEqualTo(HarmoniaAction.PROTECTIVE_REDUCTION)

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
        assertThat(bridged?.action).isEqualTo(HarmoniaAction.MEAL_SUPPORT)
        assertThat(bridged?.rationale).contains("h4_meal_rise_bridge")
        assertThat(bridged?.rationale).contains("meal_certainty_high")
        assertThat(bridged?.mealCertainty?.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(bridged?.decisionBasis?.primaryReason).isEqualTo("meal_certainty_high")
        assertThat(bridged?.smbFactor).isGreaterThan(0.0)
    }

    @Test
    fun evaluate_h4MealRiseBridgeDoesNotFireBelowTargetBand() {
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
        assertThat(decision?.action).isEqualTo(HarmoniaAction.PROTECTIVE_REDUCTION)
        assertThat(decision?.rationale).doesNotContain("h4_meal_rise_bridge")
    }

    @Test
    fun evaluate_exportsDecisionBasisAlignedWithTrunkBranch() {
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
        assertThat(decision).isNotNull()
        assertThat(decision!!.branch).isEqualTo(decision.decisionBasis.trunkState.name)
        assertThat(decision.decisionBasis.primaryReason).isEqualTo("resistance_or_stress")
        assertThat(decision.decisionBasis.actionCoherentWithTrunk).isTrue()
        assertThat(decision.toJsonObject().getJSONObject("decision_basis").getString("trunk_state"))
            .isEqualTo(decision.branch)
        assertThat(decision.version).isEqualTo(2)
    }

    @Test
    fun evaluate_h4BridgeSetsPrimaryReasonAndContributingDigestion() {
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
        assertThat(decision?.action).isEqualTo(HarmoniaAction.MEAL_SUPPORT)
        assertThat(decision?.decisionBasis?.primaryReason).isEqualTo("meal_certainty_high")
        assertThat(decision?.decisionBasis?.trunkState).isEqualTo(GlobalPhysiologicalState.DIGESTION_ACTIVE)
        assertThat(decision?.mealCertainty?.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(decision?.decisionBasis?.contributingBranches?.any { it.name == "digestion" }).isTrue()
    }

    @Test
    fun trunkActionMatrix_flagsHypoMealAsIncoherent() {
        assertThat(
            HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.HYPO_RISK,
                HarmoniaAction.MEAL_SUPPORT,
            ),
        ).isFalse()
        assertThat(
            HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.DIGESTION_ACTIVE,
                HarmoniaAction.MEAL_SUPPORT,
            ),
        ).isTrue()
        assertThat(
            HarmoniaDecisionEngine.isActionCoherentWithTrunk(
                GlobalPhysiologicalState.SENSOR_UNCERTAIN,
                HarmoniaAction.BASAL_FIRST,
            ),
        ).isFalse()
    }

    @Test
    fun evaluate_h4MealRiseBridgeDoesNotFireOnFallingDelta() {
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
        assertThat(falling?.action).isEqualTo(HarmoniaAction.PROTECTIVE_REDUCTION)
        assertThat(falling?.rationale).doesNotContain("h4_meal_rise_bridge")

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
        assertThat(flat?.action).isEqualTo(HarmoniaAction.PROTECTIVE_REDUCTION)
        assertThat(flat?.rationale).doesNotContain("h4_meal_rise_bridge")
    }

    @Test
    fun evaluate_medMealCertainty_softens_when_peakHeavy() {
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
        assertThat(softened?.action).isEqualTo(HarmoniaAction.STABILIZE)
        assertThat(softened?.decisionBasis?.primaryReason).contains("meal_certainty_med_kinetics_peak")

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
        assertThat(highStillMeal?.action).isEqualTo(HarmoniaAction.MEAL_SUPPORT)
        assertThat(highStillMeal?.decisionBasis?.primaryReason).isEqualTo("meal_certainty_high")
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
