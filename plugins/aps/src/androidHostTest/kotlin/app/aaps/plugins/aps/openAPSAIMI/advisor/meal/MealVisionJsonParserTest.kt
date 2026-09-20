package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionBasis
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEnvironment
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealVisionJsonParserTest {

    @Test
    fun `parseModelContentToEstimation unwraps markdown fence`() {
        val raw = """
            ```json
            {"food_name":"Soup","visible_items":[],"uncertain_items":[],"carbs_g":{"estimate":20,"min":18,"max":25},"protein_g":{"estimate":5,"min":4,"max":6},"fat_g":{"estimate":8,"min":6,"max":10},"absorption_speed":"MIXED","glycemic_index":"MEDIUM","confidence":"HIGH","portion_confidence":"HIGH","hidden_carb_risk":"LOW","needs_manual_confirmation":false,"insulin_relevant_notes":[],"rationale":"ok"}
            ```
        """.trimIndent()
        val r = MealVisionJsonParser.parseModelContentToEstimation(raw)
        assertEquals("Soup", r.description)
        assertEquals(20.0, r.carbs.estimate, 0.01)
    }

    @Test
    fun `parseModelContentToEstimation returns secured error on garbage`() {
        val r = MealVisionJsonParser.parseModelContentToEstimation("not json at all {{{")
        assertEquals("Parse Error", r.description)
        assertTrue(r.needsManualConfirmation)
    }

    @Test
    fun `buildAnalysisUserPrompt escapes quotes in user context`() {
        val p = MealVisionUserPrompt.buildAnalysisUserPrompt("""He said "large" portion""")
        assertTrue(!p.contains("\"large\""))
        assertTrue(p.contains("'large'"))
    }

    @Test
    fun `appendHarmoniaContext keeps meal estimate boundary explicit`() {
        val enriched = MealVisionUserPrompt.appendHarmoniaContext(
            userDescription = "Large plate",
            harmoniaDecision = HarmoniaDecision(
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
            ),
        )

        assertTrue(enriched.contains("AIMI Harmonia context"))
        assertTrue(enriched.contains("insulin_relevant_notes only"))
        assertTrue(enriched.contains("not visual carb estimation"))
        assertTrue(enriched.contains("applies_to_pump=false"))
    }
}
