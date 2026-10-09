package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import kotlinx.coroutines.withContext

/**
 * Food Recognition Service - Multi-Model Support
 * Supports OpenAI, Gemini, DeepSeek, and Claude vision APIs
 * Uses Factory pattern to select provider based on preferences
 */
class FoodRecognitionService(
    private val geminiModelResolver: GeminiModelResolver,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val aimiHttp: AimiHttp
) {

    /**
     * Factory: Create appropriate provider based on preferences
     */
    private fun getProvider(): AIVisionProvider {
        val providerName = preferences.get(StringKey.AimiAdvisorProvider)

        return when (providerName.uppercase()) {
            "OPENAI" -> OpenAIVisionProvider(aimiHttp)
            "GEMINI" -> GeminiVisionProvider(geminiModelResolver, aapsLogger, aimiHttp)
            "DEEPSEEK" -> DeepSeekVisionProvider(aimiHttp)
            "CLAUDE" -> ClaudeVisionProvider(aimiHttp)
            else -> {
                // Fallback to OpenAI if unknown provider
                OpenAIVisionProvider(aimiHttp)
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
    suspend fun estimateCarbsFromImage(image: AimiImage, userDescription: String = ""): EstimationResult =
        withContext(aapsIoDispatcher) {
            val provider = getProvider()
            val apiKey = getApiKey(provider.providerId)

            if (apiKey.isBlank()) {
                return@withContext FoodAnalysisPrompt.emptyErrorResult(
                    "API Key Missing",
                    "Please configure ${provider.displayName} API key in AIMI Preferences → Meal Advisor."
                )
            }

            try {
                val enrichedDescription = MealVisionUserPrompt.appendHarmoniaContext(
                    userDescription = userDescription,
                    harmoniaDecision = PatientStateRuntimeRepository.getLatest()?.harmoniaDecision,
                )
                provider.estimateFromImage(image, enrichedDescription, apiKey)
            } catch (e: Exception) {
                FoodAnalysisPrompt.emptyErrorResult(
                    "Error",
                    "${provider.displayName} Error: ${e.message}"
                )
            }
        }
}
