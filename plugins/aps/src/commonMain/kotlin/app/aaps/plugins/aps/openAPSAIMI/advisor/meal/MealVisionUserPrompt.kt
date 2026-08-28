package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision

/**
 * Builds the user text sent to vision LLMs and hardens free-form context so it cannot
 * break the surrounding request JSON or inject instruction-like delimiters.
 */
object MealVisionUserPrompt {

    private const val MAX_USER_CONTEXT_CHARS = 600

    fun sanitizeUserContextForPrompt(raw: String): String {
        val stripped = MealAdvisorResponseSanitizer.sanitizeModelText(raw, MAX_USER_CONTEXT_CHARS)
        return stripped
            .replace('"', '\'')
            .replace('\\', '/')
            .trim()
    }

    fun buildAnalysisUserPrompt(userDescription: String): String {
        val guard = LlmWorldConservativePreamble.FOR_UNTRUSTED_USER_CONTEXT
        val trimmed = userDescription.trim()
        if (trimmed.isEmpty()) {
            return "Analyze this meal image and return JSON only according to the required schema. $guard"
        }
        val safe = sanitizeUserContextForPrompt(trimmed)
        return "User description: \"$safe\". Analyze this meal image and return JSON only according to the required schema. $guard"
    }

    fun appendHarmoniaContext(
        userDescription: String,
        harmoniaDecision: HarmoniaDecision?,
    ): String {
        if (harmoniaDecision == null) return userDescription
        val context = "AIMI Harmonia context for insulin_relevant_notes only, not visual carb estimation: " +
            "${harmoniaDecision.compactSummary}; applies_to_pump=false."
        return listOf(userDescription.trim(), context)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_USER_CONTEXT_CHARS)
    }
}
