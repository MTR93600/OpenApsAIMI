package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaHarmonizerTest {

    @Test
    fun highFragility_softensDelivery() {
        val outcome = HarmoniaHarmonizer.evaluate(
            tree = null,
            simulation = null,
            bgMgdl = 122.0,
            deltaMgdl5m = 0.4,
            profileBasalUph = 1.0,
            proposedTbrUph = 2.0,
            eventualBgMgdl = 180.0,
            targetBgMgdl = 100.0,
            correctionFragilityScore = 0.72,
            postHyperExhaustionScore = 0.40,
        )
        assertThat(outcome?.posture).isEqualTo(HarmoniaHarmonizer.Posture.SOFTEN)
        assertThat(outcome?.tbrFactor).isLessThan(1.0)
    }

    @Test
    fun hypoBlock_ignoredDuringHyperWithoutRecentLow() {
        val tree = hyperTree()
        val simulation = HarmoniaDecision(
            timestampMs = 1L,
            branch = "HYPO_RISK",
            action = HarmoniaAction.BLOCKED,
            eligible = false,
            targetBasalUph = 1.0,
            targetSmbU = 0.0,
            basalFactor = 1.0,
            smbFactor = 0.0,
            environment = HarmoniaDecisionEnvironment(
                currentBgMgdl = 294.0,
                deltaMgdl5m = 8.0,
                iobU = 10.0,
                cobG = 0.0,
                currentBasalUph = 1.0,
                maxBasalUph = 5.0,
                maxSmbU = 1.0,
                maxIobU = 20.0,
            ),
            capsApplied = emptyList(),
            blockers = listOf("hypo_or_recovery", "critical_risk"),
            rationale = emptyList(),
            compactSummary = "blocked",
            decisionBasis = stubBasis(GlobalPhysiologicalState.HYPO_RISK, HarmoniaAction.BLOCKED),
        )

        val outcome = HarmoniaHarmonizer.evaluate(
            tree = tree,
            simulation = simulation,
            bgMgdl = 294.0,
            deltaMgdl5m = 8.0,
            profileBasalUph = 0.7,
            proposedTbrUph = 5.0,
            eventualBgMgdl = 240.0,
            targetBgMgdl = 95.0,
            correctionFragilityScore = 0.58,
            postHyperExhaustionScore = 0.81,
            minBgLookback75m = 240.0,
        )

        assertThat(outcome?.reasons).doesNotContain("harmonia_hypo_block")
        assertThat(outcome?.reasons).contains("post_hyper_fragility")
    }

    @Test
    fun hypoBlock_softensWhenGenuineHypoContext() {
        val simulation = HarmoniaDecision(
            timestampMs = 1L,
            branch = "HYPO_RISK",
            action = HarmoniaAction.BLOCKED,
            eligible = false,
            targetBasalUph = 1.0,
            targetSmbU = 0.0,
            basalFactor = 1.0,
            smbFactor = 0.0,
            environment = HarmoniaDecisionEnvironment(
                currentBgMgdl = 118.0,
                deltaMgdl5m = -0.5,
                iobU = 1.0,
                cobG = 0.0,
                currentBasalUph = 1.0,
                maxBasalUph = 5.0,
                maxSmbU = 1.0,
                maxIobU = 20.0,
            ),
            capsApplied = emptyList(),
            blockers = listOf("hypo_or_recovery"),
            rationale = emptyList(),
            compactSummary = "blocked",
            decisionBasis = stubBasis(GlobalPhysiologicalState.HYPO_RISK, HarmoniaAction.BLOCKED),
        )

        val outcome = HarmoniaHarmonizer.evaluate(
            tree = null,
            simulation = simulation,
            bgMgdl = 118.0,
            deltaMgdl5m = -0.5,
            profileBasalUph = 0.7,
            proposedTbrUph = 4.0,
            eventualBgMgdl = 130.0,
            targetBgMgdl = 95.0,
            correctionFragilityScore = 0.40,
            postHyperExhaustionScore = 0.30,
            minBgLookback75m = 72.0,
        )

        assertThat(outcome?.posture).isEqualTo(HarmoniaHarmonizer.Posture.SOFTEN)
        assertThat(outcome?.reasons).contains("harmonia_hypo_block")
    }

    @Test
    fun isGenuineHypoContext_requiresLowBgOrLookback() {
        assertThat(HarmoniaHarmonizer.isGenuineHypoContext(bgMgdl = 118.0, minBgLookback75m = 200.0)).isTrue()
        assertThat(HarmoniaHarmonizer.isGenuineHypoContext(bgMgdl = 294.0, minBgLookback75m = 240.0)).isFalse()
        assertThat(HarmoniaHarmonizer.isGenuineHypoContext(bgMgdl = 294.0, minBgLookback75m = 72.0)).isTrue()
    }

    private fun hyperTree(): PhysiologicalTreeSnapshot {
        val state = PhysiologicalSignalState(
            detected = true,
            confidence = 0.72,
            intensity = 0.72,
            label = "Hyper load",
            reasons = listOf("recent_hyper=0.70"),
        )
        return PhysiologicalTreeSnapshot(
            timestamp = 1L,
            roots = PhysiologicalRoots(
                profileState = state,
                tddState = state,
                basalState = state,
                isfState = state,
                preferenceState = state,
                historicalPatternState = state,
                mlAsyncState = state,
            ),
            trunk = PhysiologicalTrunk(
                globalState = GlobalPhysiologicalState.HYPER_RISK,
                confidence = 0.72,
                riskLevel = PhysiologicalRiskLevel.HIGH,
                dataCoherence = DataCoherenceLevel.PARTIAL,
                mainReason = "hyper",
            ),
            branches = PhysiologicalBranches(
                digestion = state,
                meal = state,
                activity = state,
                postActivity = state,
                sleepRecovery = state,
                stress = state,
                hormonalResistance = state,
                insulinEffectiveness = state,
                sensorTrust = state,
                hypoRisk = state.copy(confidence = 1.0),
                hyperRisk = state,
            ),
            leaves = PhysiologicalLeaves(
                userSummary = "",
                auditorNotes = emptyList(),
                advisorHints = emptyList(),
                mealAdvisorHints = emptyList(),
                aimiContextNotes = emptyList(),
                safetyNotes = emptyList(),
                decisionExplanation = emptyList(),
                noActionReasons = emptyList(),
            ),
            fruits = PhysiologicalFruits(null, emptyList(), emptyList()),
            seasons = PhysiologicalSeasons(null, null, null, null, null),
            compactSummary = "hyper",
        )
    }

    @Test
    fun mealCertaintyHigh_confirmsMealSupportOnDigestion() {
        val certainty = MealCertainty(
            level = MealCertaintyLevel.HIGH,
            treeState = MealCertaintyTreeState.DIGESTION_ACTIVE,
            absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            riseGeometry = MealRiseGeometry.OK,
            terminalsAgree = MealTerminalsAgree.OK,
            effortVeto = false,
            softCorroboration = false,
            reasons = listOf("level_high_digestion_rise"),
        )
        val simulation = HarmoniaDecision(
            timestampMs = 1L,
            branch = "DIGESTION_ACTIVE",
            action = HarmoniaAction.MEAL_SUPPORT,
            eligible = true,
            targetBasalUph = 1.1,
            targetSmbU = 0.3,
            basalFactor = 1.1,
            smbFactor = 0.3,
            environment = HarmoniaDecisionEnvironment(
                currentBgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                iobU = 1.0,
                cobG = 0.0,
                currentBasalUph = 1.0,
                maxBasalUph = 5.0,
                maxSmbU = 1.0,
                maxIobU = 5.0,
                mealRiseConfirmed = true,
                targetBgMgdl = 100.0,
            ),
            capsApplied = emptyList(),
            blockers = emptyList(),
            rationale = listOf("meal_certainty_high"),
            compactSummary = "meal",
            decisionBasis = stubBasis(GlobalPhysiologicalState.DIGESTION_ACTIVE, HarmoniaAction.MEAL_SUPPORT),
            mealCertainty = certainty,
        )
        val outcome = HarmoniaHarmonizer.evaluate(
            tree = null,
            simulation = simulation,
            bgMgdl = 190.0,
            deltaMgdl5m = 3.0,
            profileBasalUph = 0.7,
            proposedTbrUph = 1.1,
            eventualBgMgdl = 200.0,
            targetBgMgdl = 100.0,
            correctionFragilityScore = 0.2,
            postHyperExhaustionScore = 0.1,
            mealCertainty = certainty,
        )
        assertThat(outcome?.posture).isEqualTo(HarmoniaHarmonizer.Posture.CONFIRM)
        assertThat(outcome?.reasons).contains("meal_certainty_high_confirm")
        assertThat(HarmoniaHarmonizer.blocksAuditorSoftLanding(outcome)).isFalse()
    }

    @Test
    fun blocksAuditorSoftLanding_onBlockPosture() {
        val blocked = HarmoniaHarmonizer.Outcome(
            posture = HarmoniaHarmonizer.Posture.BLOCK,
            tbrFactor = 0.0,
            smbFactor = 0.0,
            reasons = listOf("critical_physio_risk"),
        )
        assertThat(HarmoniaHarmonizer.blocksAuditorSoftLanding(blocked)).isTrue()
    }

    private fun stubBasis(
        trunk: GlobalPhysiologicalState,
        action: HarmoniaAction,
    ): HarmoniaDecisionBasis =
        HarmoniaDecisionBasis(
            trunkState = trunk,
            trunkConfidence = 0.8,
            trunkRisk = PhysiologicalRiskLevel.MODERATE,
            primaryReason = "test",
            contributingBranches = emptyList(),
            actionCoherentWithTrunk = HarmoniaDecisionEngine.isActionCoherentWithTrunk(trunk, action),
            mismatchReason = null,
        )
}
