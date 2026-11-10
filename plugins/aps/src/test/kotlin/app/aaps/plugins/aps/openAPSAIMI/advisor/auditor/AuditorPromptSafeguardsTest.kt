package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionBasis
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEnvironment
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaHarmonizer
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyTreeState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealRiseGeometry
import app.aaps.plugins.aps.openAPSAIMI.patient.MealTerminalsAgree
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble

class AuditorPromptSafeguardsTest {

    @Test
    fun `prompt contains critical safety assertions`() {
        // Given a standard input
        val input = createDummyInput()
        
        // When building the prompt
        val prompt = AuditorPromptBuilder.buildPrompt(input)
        
        // Then it must contain critical safety sections (Audit compliance)
        
        // 1. Section Header
        assertTrue(prompt.contains("SAFETY ASSERTIONS (REQUIRED)"), "Prompt must contain Safety Assertions section")
            
        // 2. Critical Rules
        assertTrue(prompt.contains("DATA_INTEGRITY"), "Prompt must contain DATA_INTEGRITY rule for missing data")
            
        assertTrue(prompt.contains("HYPO_RULE"), "Prompt must contain HYPO_RULE for low BG safety")
        
        assertTrue(prompt.contains("STACKING_RULE"), "Prompt must contain STACKING_RULE for IOB peaks")
            
        assertTrue(prompt.contains("ANTI-HALLUCINATION"), "Prompt must contain ANTI-HALLUCINATION safeguards")
            
        // 3. Specific Bound Checks
        assertTrue(prompt.contains("< 75 mg/dL"), "Prompt must enforce strict hypo threshold (75mg/dL)")
            
        assertTrue(prompt.contains("uncertain_data"), "Prompt must allow flagging uncertain data")
            
        // 4. Instructions
        assertTrue(prompt.contains("degradedMode"), "Prompt must contain instructions on degraded mode")
    }

    @Test
    fun `prompt contains LLM World conservative preamble`() {
        val prompt = AuditorPromptBuilder.buildPrompt(createDummyInput())
        assertTrue(
            prompt.contains(LlmWorldConservativePreamble.FOR_JSON_CONTRACT.substring(0, 40)),
            "Prompt must contain LLM World JSON preamble",
        )
        assertTrue(prompt.contains("do not change output schema"), "Preamble must preserve output contract")
        assertTrue(prompt.contains("Do not recommend free insulin doses"), "Preamble must forbid dose recommendations")
    }

    @Test
    fun `prompt carries Harmonia simulation as sandbox branch`() {
        val prompt = AuditorPromptBuilder.buildPrompt(
            createDummyInput().copy(harmoniaDecision = createSimulation())
        )

        assertTrue(prompt.contains("Harmonia Simulation Branch"), "Prompt must explain Harmonia sandbox semantics")
        assertTrue(prompt.contains("harmonia_simulation"), "Prompt input must include Harmonia simulation JSON")
        assertTrue(prompt.contains("applies_to_pump"), "Prompt input must expose pump isolation")
        assertTrue(prompt.contains("false"), "Harmonia simulation must be marked as not applied to pump")
    }

    @Test
    fun `prompt encodes cascade meal certainty and never reopen block`() {
        val prompt = AuditorPromptBuilder.buildPrompt(createDummyInput())
        assertTrue(prompt.contains("meal_certainty.level=HIGH"), "Prompt must teach HIGH meal CONFIRM")
        assertTrue(prompt.contains("never reopen"), "Prompt must forbid reopening sync BLOCK")
        assertTrue(prompt.contains("decision_basis"), "Prompt must reference decision_basis")
    }

    @Test
    fun `auditor input json exposes meal certainty and decision basis`() {
        val certainty = MealCertainty(
            level = MealCertaintyLevel.HIGH,
            treeState = MealCertaintyTreeState.DIGESTION_ACTIVE,
            absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            riseGeometry = MealRiseGeometry.OK,
            terminalsAgree = MealTerminalsAgree.OK,
            effortVeto = false,
            softCorroboration = false,
        )
        val json = createDummyInput().copy(
            harmoniaDecision = createSimulation(),
            mealCertainty = certainty,
            harmonizerOutcome = HarmoniaHarmonizer.Outcome(
                posture = HarmoniaHarmonizer.Posture.CONFIRM,
                reasons = listOf("meal_certainty_high_confirm"),
            ),
        ).toJSON()
        assertTrue(json.has("meal_certainty"))
        assertTrue(json.has("decision_basis"))
        assertTrue(json.has("harmonia_harmonizer"))
        assertEquals("HIGH", json.getJSONObject("meal_certainty").getString("level"))
    }

    private fun createDummyInput(): AuditorInput {
        return AuditorInput(
            snapshot = Snapshot(
                bg = 120.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                unit = "mg/dl",
                timestamp = System.currentTimeMillis(),
                cgmAgeMin = 2,
                noise = "Clean",
                iob = 1.0,
                iobActivity = 0.1,
                cob = 0.0,
                isfProfile = 40.0,
                isfUsed = 40.0,
                ic = 10.0,
                target = 100.0,
                pkpd = PKPDSnapshot(300, 75, 0.2, true, 0.0),
                activity = ActivitySnapshot(0, 0, null, null),
                physio = null,
                states = StatesSnapshot("Normal", 10, "Idle", null, 1.0),
                limits = LimitsSnapshot(2.0, 3.0, 5.0, 2.0, null, null),
                decisionAimi = DecisionSnapshot(0.0, null, null, 5.0, emptyList()),
                lastDelivery = LastDeliverySnapshot(null, null, null, null, null, null)
            ),
            history = History(
                emptyList(), emptyList(), emptyList(), emptyList(), 
                emptyList(), emptyList(), emptyList()
            ),
            stats = Stats7d(
                80.0, 1.0, 19.0, 130.0, 30.0, 40.0, 50.0, 50.0
            ),
            trajectory = null
        )
    }

    private fun createSimulation(): HarmoniaDecision =
        HarmoniaDecision(
            timestampMs = 1_718_000_000_000L,
            branch = "RESISTANCE_PROBABLE",
            action = HarmoniaAction.BASAL_FIRST,
            eligible = true,
            targetBasalUph = 1.2,
            targetSmbU = 0.0,
            basalFactor = 1.2,
            smbFactor = 0.0,
            environment = HarmoniaDecisionEnvironment(
                currentBgMgdl = 180.0,
                deltaMgdl5m = 2.0,
                iobU = 1.0,
                cobG = 0.0,
                currentBasalUph = 1.0,
                maxBasalUph = 5.0,
                maxSmbU = 1.0,
                maxIobU = 5.0,
            ),
            capsApplied = emptyList(),
            blockers = emptyList(),
            rationale = listOf("test"),
            compactSummary = "Harmonia sim: basal_first RESISTANCE_PROBABLE | basal 1.20U/h | smb 0.00U",
            decisionBasis = HarmoniaDecisionBasis(
                trunkState = GlobalPhysiologicalState.RESISTANCE_PROBABLE,
                trunkConfidence = 0.8,
                trunkRisk = PhysiologicalRiskLevel.MODERATE,
                primaryReason = "resistance_or_stress",
                contributingBranches = emptyList(),
                actionCoherentWithTrunk = true,
            ),
        )
}
