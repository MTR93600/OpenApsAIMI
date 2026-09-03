package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap

/**
 * Common interface for AI vision providers.
 *
 * [EstimationResult], [FoodAnalysisPrompt] and friends already live in this package
 * (`MealEstimateModels.kt`, `FoodAnalysisPrompt.kt`) - ported earlier, ahead of this interface,
 * onto `kotlinx.serialization` instead of `org.json`. No import needed, same package.
 */
interface AIVisionProvider {
    suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult
    val displayName: String
    val providerId: String
}
