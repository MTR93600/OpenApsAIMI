package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Shadow-only orchestrator for inter-engine attribution and anti double-counting.
 *
 * IMPORTANT: This class never changes delivered insulin decisions. It only computes
 * a "what-would-be" budgeted fusion to analyze conflicts between engines.
 */
class AIMIDecisionOrchestratorShadowMTR {

    data class ShadowResult(
        val enabled: Boolean = true,
        val budgetedIsfFactor: Double = 1.0,
        val budgetedBasalFactor: Double = 1.0,
        val budgetedSmbFactor: Double = 1.0,
        val overlapPenalty: Double = 1.0,
        val contributions: Map<String, Double> = emptyMap(),
        val notes: List<String> = emptyList(),
    )

    fun compute(
        rawMultipliers: PhysioMultipliersMTR,
        cgateIsfMultiplier: Double,
        inflammation: InflammationLatentStateMTR,
    ): ShadowResult {
        val notes = mutableListOf<String>()

        val semanticBrake = rawMultipliers.reactivityFactor.coerceIn(0.90, 1.10)
        val cgateBrake = cgateIsfMultiplier.coerceIn(0.85, 1.15)
        val inflammationBrake = (1.0 - (inflammation.index * 0.12)).coerceIn(0.88, 1.0)

        // Anti double-counting:
        // if semantic brake already strong AND inflammation high confidence/high index,
        // apply overlap penalty so fused shadow recommendation stays conservative.
        val overlapPenalty = when {
            semanticBrake < 0.90 && inflammation.index > 0.60 && inflammation.confidence > 0.60 -> 0.95
            semanticBrake < 0.95 && inflammation.index > 0.45 && inflammation.confidence > 0.50 -> 0.97
            else -> 1.0
        }
        if (overlapPenalty < 1.0) notes.add("overlap_penalty_applied")

        val budgetedSmbFactor = (semanticBrake * inflammationBrake * overlapPenalty)
            .coerceIn(0.90, 1.10)
        val budgetedBasalFactor = (rawMultipliers.basalFactor * inflammationBrake * overlapPenalty)
            .coerceIn(0.85, 1.15)
        val budgetedIsfFactor = (rawMultipliers.isfFactor * cgateBrake * overlapPenalty)
            .coerceIn(0.85, 1.15)

        val contributions = linkedMapOf(
            "semantic_reactivity" to semanticBrake,
            "cgate_isf" to cgateBrake,
            "inflammation_latent" to inflammationBrake,
            "overlap_penalty" to overlapPenalty,
        )

        return ShadowResult(
            enabled = true,
            budgetedIsfFactor = budgetedIsfFactor,
            budgetedBasalFactor = budgetedBasalFactor,
            budgetedSmbFactor = budgetedSmbFactor,
            overlapPenalty = overlapPenalty,
            contributions = contributions,
            notes = notes,
        )
    }
}
