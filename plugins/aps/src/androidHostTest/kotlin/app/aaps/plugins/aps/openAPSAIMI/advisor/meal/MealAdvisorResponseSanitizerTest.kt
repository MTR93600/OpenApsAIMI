package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealAdvisorResponseSanitizerTest {

    @Test
    fun `fixMacroRange reorders inverted min max`() {
        val r = MacroRange(estimate = 50.0, min = 80.0, max = 40.0)
        val fixed = MealAdvisorResponseSanitizer.fixMacroRange(r)
        assertTrue(fixed.min <= fixed.estimate)
        assertTrue(fixed.estimate <= fixed.max)
        assertEquals(50.0, fixed.estimate, 0.01)
    }

    @Test
    fun `sanitizeModelText strips control characters`() {
        val raw = "Soup\u0000\u0001Bowl"
        val out = MealAdvisorResponseSanitizer.sanitizeModelText(raw, 100)
        assertTrue(!out.contains('\u0000'))
        assertEquals("SoupBowl", out)
    }

    @Test
    fun `secureEstimationResult clamps recommended carbs`() {
        val hi = MacroRange(estimate = 60.0, min = 50.0, max = 70.0)
        val raw = EstimationResult(
            description = "X",
            visibleItems = emptyList(),
            uncertainItems = emptyList(),
            carbs = hi,
            protein = MacroRange(0.0, 0.0, 0.0),
            fat = MacroRange(0.0, 0.0, 0.0),
            fpuEquivalent = 5.0,
            glycemicIndex = "MEDIUM",
            absorptionSpeed = "MIXED",
            confidence = "HIGH",
            portionConfidence = "HIGH",
            hiddenCarbRisk = "LOW",
            needsManualConfirmation = false,
            insulinRelevantNotes = emptyList(),
            reasoning = "ok",
            recommendedCarbsForDose = 999.0,
            recommendedCarbsReason = "model bug",
        )
        val secured = MealAdvisorResponseSanitizer.secureEstimationResult(raw)
        assertTrue(secured.recommendedCarbsForDose <= MealAdvisorResponseSanitizer.MAX_RECOMMENDED_CARB_GRAMS)
        assertTrue(secured.recommendedCarbsForDose <= hi.max + 1e-6)
    }

    @Test
    fun `secureEstimationResult forces manual when positive carbs but zero recommendation`() {
        val carbs = MacroRange(estimate = 40.0, min = 35.0, max = 45.0)
        val raw = EstimationResult(
            description = "Meal",
            visibleItems = emptyList(),
            uncertainItems = emptyList(),
            carbs = carbs,
            protein = MacroRange(0.0, 0.0, 0.0),
            fat = MacroRange(0.0, 0.0, 0.0),
            fpuEquivalent = 0.0,
            glycemicIndex = "MEDIUM",
            absorptionSpeed = "MIXED",
            confidence = "HIGH",
            portionConfidence = "HIGH",
            hiddenCarbRisk = "LOW",
            needsManualConfirmation = false,
            insulinRelevantNotes = emptyList(),
            reasoning = "x",
            recommendedCarbsForDose = 0.0,
            recommendedCarbsReason = "bad",
        )
        val secured = MealAdvisorResponseSanitizer.secureEstimationResult(raw)
        assertTrue(secured.needsManualConfirmation)
        assertEquals(0.0, secured.recommendedCarbsForDose, 0.001)
    }
}
