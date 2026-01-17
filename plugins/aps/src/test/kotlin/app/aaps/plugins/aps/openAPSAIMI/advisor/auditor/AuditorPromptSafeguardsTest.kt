package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.Assert.assertTrue
import org.junit.Test

class AuditorPromptSafeguardsTest {

    @Test
    fun `prompt contains critical safety assertions`() {
        // Given a standard input
        val input = createDummyInput()
        
        // When building the prompt
        val prompt = AuditorPromptBuilder.buildPrompt(input)
        
        // Then it must contain critical safety sections (Audit compliance)
        
        // 1. Section Header
        assertTrue("Prompt must contain Safety Assertions section", 
            prompt.contains("SAFETY ASSERTIONS (REQUIRED)"))
            
        // 2. Critical Rules
        assertTrue("Prompt must contain DATA_INTEGRITY rule for missing data", 
            prompt.contains("DATA_INTEGRITY"))
            
        assertTrue("Prompt must contain HYPO_RULE for low BG safety", 
            prompt.contains("HYPO_RULE"))
        
        assertTrue("Prompt must contain STACKING_RULE for IOB peaks", 
            prompt.contains("STACKING_RULE"))
            
        assertTrue("Prompt must contain ANTI-HALLUCINATION safeguards", 
            prompt.contains("ANTI-HALLUCINATION"))
            
        // 3. Specific Bound Checks
        assertTrue("Prompt must enforce strict hypo threshold (75mg/dL)", 
            prompt.contains("< 75 mg/dL"))
            
        assertTrue("Prompt must allow flagging uncertain data", 
            prompt.contains("uncertain_data"))
            
        // 4. Instructions
        assertTrue("Prompt must contain instructions on degraded mode",
            prompt.contains("degradedMode"))
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
            )
        )
    }
}
