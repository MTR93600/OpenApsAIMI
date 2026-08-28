package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

/**
 * Refined Nutrition Models for V2
 */
data class VisibleFoodItem(val name: String, val amountInfo: String)

data class MacroRange(val estimate: Double, val min: Double, val max: Double)

data class EstimationResult(
    val description: String,
    val visibleItems: List<VisibleFoodItem>,
    val uncertainItems: List<String>,
    val carbs: MacroRange,
    val protein: MacroRange,
    val fat: MacroRange,
    val fpuEquivalent: Double,
    val glycemicIndex: String,
    val absorptionSpeed: String,
    val confidence: String,
    val portionConfidence: String,
    val hiddenCarbRisk: String,
    val needsManualConfirmation: Boolean,
    val insulinRelevantNotes: List<String>,
    val reasoning: String,
    val recommendedCarbsForDose: Double,
    val recommendedCarbsReason: String
)
