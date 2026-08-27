package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import java.io.File
import org.json.JSONObject

internal enum class HarmoniaRuntimeTickStatus {
    NATIVE_APPLIED,
    NATIVE_READY,
    NATIVE_BLOCKED,
    T3C_PRIORITY,
    UNAVAILABLE,
}

internal data class HarmoniaRuntimeTickRecord(
    val timestampMs: Long,
    val status: HarmoniaRuntimeTickStatus,
    val basalFirstChannel: String?,
    val productionMode: String?,
    val active: Boolean,
    val eligible: Boolean,
    val sourceAction: String?,
    val branch: String?,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val basalDemandRateUph: Double?,
    val boundedRateUph: Double?,
    val maxBasalCapUph: Double?,
    val appliedRateUph: Double?,
    val appliedDurationMin: Int?,
    val blocker: String?,
    val selectedForProduction: Boolean,
    val addsSmbAuthority: Boolean,
    val smbEligible: Boolean,
    val smbAppliedToRbtDemand: Boolean,
    val smbReducesRbtDemand: Boolean,
    val targetSmbU: Double?,
    val boundedSmbU: Double?,
    val maxSmbCapU: Double?,
    val smbDemandBeforeU: Double?,
    val smbDemandAfterU: Double?,
    val smbBlocker: String?,
)

internal data class HarmoniaRuntimeNumericStats(
    val count: Int,
    val average: Double,
    val min: Double,
    val max: Double,
)

internal data class HarmoniaRuntimeHistorySummary(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val tickCount: Int,
    val notEnoughData: Boolean,
    val dominantStatus: HarmoniaRuntimeTickStatus?,
    val nativeAppliedCount: Int,
    val nativeReadyCount: Int,
    val nativeBlockedCount: Int,
    val t3cPriorityCount: Int,
    val smbAppliedCount: Int,
    val smbReadyCount: Int,
    val smbBlockedCount: Int,
    val dominantBlocker: String?,
    val demandStats: HarmoniaRuntimeNumericStats?,
    val appliedRateStats: HarmoniaRuntimeNumericStats?,
    val smbDemandStats: HarmoniaRuntimeNumericStats?,
)

internal object HarmoniaRuntimeHistoryReader {

    private const val WINDOW_24H_MS = 24L * 60L * 60L * 1000L
    private const val MAX_HISTORY_LINES = 400
    private const val MAX_LATEST_LINES = 120
    private const val MIN_HISTORY_TICKS = 6

    fun readLatestTick(
        file: File = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile(),
    ): HarmoniaRuntimeTickRecord? {
        if (!file.exists() || !file.canRead()) return null
        val tail = JsonlTailReader.readTailLines(file, maxLines = MAX_LATEST_LINES)
        for (line in tail) {
            try {
                parseTick(JSONObject(line))?.let { return it }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun summarizeLast24Hours(
        file: File = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile(),
        nowMs: Long = System.currentTimeMillis(),
    ): HarmoniaRuntimeHistorySummary? {
        if (!file.exists() || !file.canRead()) return null
        val cutoffMs = nowMs - WINDOW_24H_MS
        val tail = JsonlTailReader.readTailLines(file, maxLines = MAX_HISTORY_LINES)
        val records = mutableListOf<HarmoniaRuntimeTickRecord>()

        for (line in tail) {
            try {
                val root = JSONObject(line)
                val timestampMs = root.optLong("timestamp", 0L)
                if (timestampMs in 1 until cutoffMs) break
                val tick = parseTick(root) ?: continue
                if (tick.timestampMs >= cutoffMs) {
                    records.add(tick)
                }
            } catch (_: Exception) {
                continue
            }
        }

        if (records.isEmpty()) {
            return HarmoniaRuntimeHistorySummary(
                windowStartMs = cutoffMs,
                windowEndMs = nowMs,
                tickCount = 0,
                notEnoughData = true,
                dominantStatus = null,
                nativeAppliedCount = 0,
                nativeReadyCount = 0,
                nativeBlockedCount = 0,
                t3cPriorityCount = 0,
                smbAppliedCount = 0,
                smbReadyCount = 0,
                smbBlockedCount = 0,
                dominantBlocker = null,
                demandStats = null,
                appliedRateStats = null,
                smbDemandStats = null,
            )
        }

        val statusCounts = HarmoniaRuntimeTickStatus.values().associateWith { status ->
            records.count { it.status == status }
        }
        val dominantStatus = HarmoniaRuntimeTickStatus.values().maxByOrNull { statusCounts[it] ?: 0 }
        val dominantBlocker = records
            .mapNotNull { it.blocker }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val demandStats = buildNumericStats(
            records.mapNotNull { tick ->
                val demand = tick.boundedRateUph ?: tick.basalDemandRateUph
                demand?.takeIf { it > 0.0 }
            },
        )
        val appliedRateStats = buildNumericStats(
            records.mapNotNull { tick -> tick.appliedRateUph?.takeIf { it > 0.0 } },
        )
        val smbDemandStats = buildNumericStats(
            records.mapNotNull { tick -> tick.smbDemandAfterU?.takeIf { it > 0.0 } },
        )

        return HarmoniaRuntimeHistorySummary(
            windowStartMs = cutoffMs,
            windowEndMs = nowMs,
            tickCount = records.size,
            notEnoughData = records.size < MIN_HISTORY_TICKS,
            dominantStatus = dominantStatus,
            nativeAppliedCount = statusCounts[HarmoniaRuntimeTickStatus.NATIVE_APPLIED] ?: 0,
            nativeReadyCount = statusCounts[HarmoniaRuntimeTickStatus.NATIVE_READY] ?: 0,
            nativeBlockedCount = statusCounts[HarmoniaRuntimeTickStatus.NATIVE_BLOCKED] ?: 0,
            t3cPriorityCount = statusCounts[HarmoniaRuntimeTickStatus.T3C_PRIORITY] ?: 0,
            smbAppliedCount = records.count { it.smbAppliedToRbtDemand },
            smbReadyCount = records.count { it.smbEligible },
            smbBlockedCount = records.count { it.smbBlocker != null },
            dominantBlocker = dominantBlocker,
            demandStats = demandStats,
            appliedRateStats = appliedRateStats,
            smbDemandStats = smbDemandStats,
        )
    }

    private fun parseTick(root: JSONObject): HarmoniaRuntimeTickRecord? {
        val adjustments = root.optJSONObject("adjustments") ?: return null
        val recursiveBelief = adjustments.optJSONObject("recursive_belief")
        val production = adjustments.optJSONObject("harmonia_production")
        val resolution = recursiveBelief?.optJSONObject("resolution")
        val harmonia = resolution?.optJSONObject("harmonia_basal_first")
        val harmoniaSmb = resolution?.optJSONObject("harmonia_smb")
        if (harmonia == null && production == null && harmoniaSmb == null) return null

        val basalFirstChannel = resolution?.optStringOrNull("basal_first_channel")
        val productionMode = production?.optStringOrNull("mode")
        val active = harmonia?.optBoolean("active", false)
            ?: harmoniaSmb?.optBoolean("active", false)
            ?: (production != null)
        val eligible = harmonia?.optBoolean("eligible", false)
            ?: harmoniaSmb?.optBoolean("eligible", false)
            ?: (productionMode == "READY" || productionMode == "APPLIED")
        val selectedForProduction = harmonia?.optBoolean("selected_for_production", false)
            ?: production?.optBoolean("selected_for_production", false)
            ?: false
        val productionBlocker = if (productionMode == "BLOCKED") {
            production.optStringOrNull("runtime_blocker")
                ?: production.optJSONArray("safety_blockers")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: production.optStringOrNull("reason")
        } else {
            null
        }
        val blocker = harmonia?.optStringOrNull("runtime_blocker")
            ?: harmonia?.optStringOrNull("dominant_blocker")
            ?: productionBlocker
            ?: harmoniaSmb?.optStringOrNull("dominant_blocker")

        val status = deriveStatus(
            productionMode = productionMode,
            basalFirstChannel = basalFirstChannel,
            active = active,
            eligible = eligible,
            selectedForProduction = selectedForProduction,
        )

        return HarmoniaRuntimeTickRecord(
            timestampMs = root.optLong("timestamp", 0L),
            status = status,
            basalFirstChannel = basalFirstChannel,
            productionMode = productionMode,
            active = active,
            eligible = eligible,
            sourceAction = harmonia?.optStringOrNull("source_action") ?: production?.optStringOrNull("source_action"),
            branch = harmonia?.optStringOrNull("branch") ?: production?.optStringOrNull("branch"),
            mealConflict = harmonia?.optBoolean("meal_conflict", false) ?: false,
            postHypoBlock = harmonia?.optBoolean("post_hypo_block", false) ?: false,
            exerciseBlock = harmonia?.optBoolean("exercise_block", false) ?: false,
            hardSafetyBlock = harmonia?.optBoolean("hard_safety_block", false) ?: false,
            basalDemandRateUph = harmonia?.optDoubleOrNull("basal_demand_rate_uph")
                ?: production?.optDoubleOrNull("requested_rate_uph"),
            boundedRateUph = harmonia?.optDoubleOrNull("bounded_rate_uph")
                ?: production?.optDoubleOrNull("bounded_rate_uph"),
            maxBasalCapUph = harmonia?.optDoubleOrNull("max_basal_cap_uph"),
            appliedRateUph = harmonia?.optDoubleOrNull("applied_rate_uph")
                ?: production?.optDoubleOrNull("applied_rate_uph"),
            appliedDurationMin = harmonia?.optIntOrNull("applied_duration_min")
                ?: production?.optIntOrNull("applied_duration_min"),
            blocker = blocker,
            selectedForProduction = selectedForProduction,
            addsSmbAuthority = production?.optBoolean("adds_smb_authority", false) ?: false,
            smbEligible = harmoniaSmb?.optBoolean("eligible", false) ?: false,
            smbAppliedToRbtDemand = harmoniaSmb?.optBoolean("applied_to_rbt_demand", false) ?: false,
            smbReducesRbtDemand = harmoniaSmb?.optBoolean("reduces_rbt_demand", false) ?: false,
            targetSmbU = harmoniaSmb?.optDoubleOrNull("simulated_smb_u"),
            boundedSmbU = harmoniaSmb?.optDoubleOrNull("bounded_smb_u"),
            maxSmbCapU = harmoniaSmb?.optDoubleOrNull("max_smb_cap_u"),
            smbDemandBeforeU = harmoniaSmb?.optDoubleOrNull("demand_before_u"),
            smbDemandAfterU = harmoniaSmb?.optDoubleOrNull("demand_after_u"),
            smbBlocker = harmoniaSmb?.optStringOrNull("dominant_blocker"),
        )
    }

    private fun deriveStatus(
        productionMode: String?,
        basalFirstChannel: String?,
        active: Boolean,
        eligible: Boolean,
        selectedForProduction: Boolean,
    ): HarmoniaRuntimeTickStatus =
        when {
            productionMode == "APPLIED" || selectedForProduction -> HarmoniaRuntimeTickStatus.NATIVE_APPLIED
            eligible && basalFirstChannel == "T3C_BASAL_FIRST" -> HarmoniaRuntimeTickStatus.T3C_PRIORITY
            productionMode == "READY" || (eligible && basalFirstChannel == "HARMONIA_PRODUCTION_BASAL_FIRST") ->
                HarmoniaRuntimeTickStatus.NATIVE_READY
            productionMode == "BLOCKED" || (active && !eligible) -> HarmoniaRuntimeTickStatus.NATIVE_BLOCKED
            active && eligible -> HarmoniaRuntimeTickStatus.NATIVE_READY
            else -> HarmoniaRuntimeTickStatus.UNAVAILABLE
        }

    private fun buildNumericStats(values: List<Double>): HarmoniaRuntimeNumericStats? {
        if (values.isEmpty()) return null
        return HarmoniaRuntimeNumericStats(
            count = values.size,
            average = values.average(),
            min = values.minOrNull() ?: 0.0,
            max = values.maxOrNull() ?: 0.0,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)
}
