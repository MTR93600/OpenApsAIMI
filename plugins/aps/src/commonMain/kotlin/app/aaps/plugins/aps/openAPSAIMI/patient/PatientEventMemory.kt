package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PatientEventMemory(
    val recentHyperLoad: Double = 0.0,
    val recentHypoLoad: Double = 0.0,
    val postHyperExhaustionScore: Double = 0.0,
    val correctionFragilityScore: Double = 0.0,
    val source: String = "event_memory_v1",
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("recent_hyper_load", recentHyperLoad)
            put("recent_hypo_load", recentHypoLoad)
            put("post_hyper_exhaustion_score", postHyperExhaustionScore)
            put("correction_fragility_score", correctionFragilityScore)
            put("source", source)
        }

    companion object {
        val EMPTY = PatientEventMemory()
    }
}
