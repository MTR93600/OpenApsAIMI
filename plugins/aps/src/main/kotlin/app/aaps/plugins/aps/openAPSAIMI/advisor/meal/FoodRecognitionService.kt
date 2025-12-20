package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.content.Context
import android.graphics.Bitmap
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Food Recognition Service - Multi-Model Support
 * Supports OpenAI, Gemini, DeepSeek, and Claude vision APIs
 * Uses Factory pattern to select provider based on preferences
 */
class FoodRecognitionService(
    private val context: Context,
    private val preferences: Preferences
) {
    
    /**
     * Factory: Create appropriate provider based on preferences
     */
    private fun getProvider(): AIVisionProvider {
        val providerName = preferences.get(StringKey.AimiAdvisorProvider)
        
        return when (providerName.uppercase()) {
            "OPENAI" -> OpenAIVisionProvider()
            "GEMINI" -> GeminiVisionProvider()
            "DEEPSEEK" -> DeepSeekVisionProvider()
            "CLAUDE" -> ClaudeVisionProvider()
            else -> {
                // Fallback to OpenAI if unknown provider
                OpenAIVisionProvider()
            }
        }
    }
    
    /**
     * Get API key for current provider
     */
    private fun getApiKey(providerId: String): String {
        return when (providerId.uppercase()) {
            "OPENAI" -> preferences.get(StringKey.AimiAdvisorOpenAIKey)
            "GEMINI" -> preferences.get(StringKey.AimiAdvisorGeminiKey)
            "DEEPSEEK" -> preferences.get(StringKey.AimiAdvisorDeepSeekKey)
            "CLAUDE" -> preferences.get(StringKey.AimiAdvisorClaudeKey)
            else -> ""
        }
    }
    
    /**
     * Estimate carbs and macros from food image
     * Uses currently selected provider from preferences
     */
    suspend fun estimateCarbsFromImage(bitmap: Bitmap): EstimationResult = withContext(Dispatchers.IO) {
        val provider = getProvider()
        val apiKey = getApiKey(provider.providerId)
        
        if (apiKey.isBlank()) {
            return@withContext EstimationResult(
                description = "API Key Missing",
                carbsGrams = 0.0,
                proteinGrams = 0.0,
                fatGrams = 0.0,
                fpuEquivalent = 0.0,
                reasoning = "Please configure ${provider.displayName} API key in AIMI Preferences → Meal Advisor."
            )
        }
        
        try {
            return@withContext provider.estimateFromImage(bitmap, apiKey)
        } catch (e: Exception) {
            return@withContext EstimationResult(
                description = "Error",
                carbsGrams = 0.0,
                proteinGrams = 0.0,
                fatGrams = 0.0,
                fpuEquivalent = 0.0,
                reasoning = "${provider.displayName} Error: ${e.message}"
            )
        }
    }
}
