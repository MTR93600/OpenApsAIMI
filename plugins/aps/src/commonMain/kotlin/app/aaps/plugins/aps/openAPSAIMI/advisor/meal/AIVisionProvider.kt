package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

/**
 * Common interface for AI vision providers.
 *
 * [EstimationResult], [FoodAnalysisPrompt] and friends already live in this package
 * (`MealEstimateModels.kt`, `FoodAnalysisPrompt.kt`) - ported earlier, ahead of this interface,
 * onto `kotlinx.serialization` instead of `org.json`. No import needed, same package.
 *
 * The image arrives as [AimiImage] (JPEG bytes), never as a platform bitmap: the platform
 * compresses its native image type before calling. Android: `Bitmap.toAimiImage()`.
 */
interface AIVisionProvider {
    suspend fun estimateFromImage(image: AimiImage, userDescription: String, apiKey: String): EstimationResult
    val displayName: String
    val providerId: String
}
