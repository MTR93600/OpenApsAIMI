package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ThermalHypothesis {
    DATA_PENDING,
    BASELINE_STABLE,
    INFLAMMATORY_DRIFT,
    RECOVERY_COOLING,
    HYPO_SYMPATHETIC_COOLING,
    FATIGUE_DYSREGULATION,
    CYCLE_BBT_RISE,
}

/**
 * Product-facing thermal belief — evolution vs personal baseline, not absolute fever.
 */
data class ThermalBeliefDigest(
    val hypothesis: ThermalHypothesis = ThermalHypothesis.DATA_PENDING,
    val deltaVsBaselineC: Double = 0.0,
    val slope6hC: Double = 0.0,
    val slope24hC: Double = 0.0,
    val circadianDisruption: Double = 0.0,
    val inflammationIndex: Double = 0.0,
    val recoveryBurden: Double = 0.0,
    val confidence: Double = 0.0,
    val narrative: String = "",
    val wCycleHint: String? = null,
    val basalBodyTempC: Double? = null,
    val sampleCount: Int = 0,
    val dataOrigin: String = "Unknown",
    val sourceTier: ThermalSourceTier = ThermalSourceTier.MEASURED,
) {
    fun hasUsableData(): Boolean =
        hypothesis != ThermalHypothesis.DATA_PENDING && confidence >= 0.35

    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("hypothesis", hypothesis.name)
            put("delta_vs_baseline_c", deltaVsBaselineC)
            put("slope_6h_c", slope6hC)
            put("slope_24h_c", slope24hC)
            put("circadian_disruption", circadianDisruption)
            put("inflammation_index", inflammationIndex)
            put("recovery_burden", recoveryBurden)
            put("confidence", confidence)
            put("narrative", narrative)
            put("wcycle_hint", wCycleHint)
            put("basal_body_temp_c", basalBodyTempC)
            put("sample_count", sampleCount)
            put("data_origin", dataOrigin)
            put("source_tier", sourceTier.name)
        }

    companion object {
        val EMPTY = ThermalBeliefDigest()
    }
}
