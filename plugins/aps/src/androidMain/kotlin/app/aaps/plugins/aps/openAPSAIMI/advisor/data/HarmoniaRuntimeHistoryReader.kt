package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.core.data.json.OrgJsonCompat.optBooleanCompat
import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
                parseTick(Json.parseToJsonElement(line).jsonObject)?.let { return it }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun summarizeLast24Hours(
        file: File = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile(),
        nowMs: Long = aimiWallClockMs(),
    ): HarmoniaRuntimeHistorySummary? {
        if (!file.exists() || !file.canRead()) return null
        val cutoffMs = nowMs - WINDOW_24H_MS
        val tail = JsonlTailReader.readTailLines(file, maxLines = MAX_HISTORY_LINES)
        val records = mutableListOf<HarmoniaRuntimeTickRecord>()

        for (line in tail) {
            try {
                val root = Json.parseToJsonElement(line).jsonObject
                val timestampMs = root.optLongCompat("timestamp", 0L)
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

        val statusCounts = HarmoniaRuntimeTickStatus.entries.associateWith { status ->
            records.count { it.status == status }
        }
        val dominantStatus = HarmoniaRuntimeTickStatus.entries.maxByOrNull { statusCounts[it] ?: 0 }
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

    private fun parseTick(root: JsonObject): HarmoniaRuntimeTickRecord? {
        val adjustments = root.optJsonObjectCompat("adjustments") ?: return null
        val recursiveBelief = adjustments.optJsonObjectCompat("recursive_belief")
        val production = adjustments.optJsonObjectCompat("harmonia_production")
        val resolution = recursiveBelief?.optJsonObjectCompat("resolution")
        val harmonia = resolution?.optJsonObjectCompat("harmonia_basal_first")
        val harmoniaSmb = resolution?.optJsonObjectCompat("harmonia_smb")
        if (harmonia == null && production == null && harmoniaSmb == null) return null

        val basalFirstChannel = resolution?.optStringOrNull("basal_first_channel")
        val productionMode = production?.optStringOrNull("mode")
        val active = harmonia?.optBooleanCompat("active")
            ?: harmoniaSmb?.optBooleanCompat("active")
            ?: (production != null)
        val eligible = harmonia?.optBooleanCompat("eligible")
            ?: harmoniaSmb?.optBooleanCompat("eligible")
            ?: (productionMode == "READY" || productionMode == "APPLIED")
        val selectedForProduction = harmonia?.optBooleanCompat("selected_for_production")
            ?: production?.optBooleanCompat("selected_for_production")
            ?: false
        val productionBlocker = if (productionMode == "BLOCKED") {
            production?.optStringOrNull("runtime_blocker")
                ?: production?.optJsonArrayCompat("safety_blockers")?.optStringAt(0)?.takeIf { it.isNotBlank() }
                ?: production?.optStringOrNull("reason")
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
            timestampMs = root.optLongCompat("timestamp", 0L),
            status = status,
            basalFirstChannel = basalFirstChannel,
            productionMode = productionMode,
            active = active,
            eligible = eligible,
            sourceAction = harmonia?.optStringOrNull("source_action") ?: production?.optStringOrNull("source_action"),
            branch = harmonia?.optStringOrNull("branch") ?: production?.optStringOrNull("branch"),
            mealConflict = harmonia?.optBooleanCompat("meal_conflict") ?: false,
            postHypoBlock = harmonia?.optBooleanCompat("post_hypo_block") ?: false,
            exerciseBlock = harmonia?.optBooleanCompat("exercise_block") ?: false,
            hardSafetyBlock = harmonia?.optBooleanCompat("hard_safety_block") ?: false,
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
            addsSmbAuthority = production?.optBooleanCompat("adds_smb_authority") ?: false,
            smbEligible = harmoniaSmb?.optBooleanCompat("eligible") ?: false,
            smbAppliedToRbtDemand = harmoniaSmb?.optBooleanCompat("applied_to_rbt_demand") ?: false,
            smbReducesRbtDemand = harmoniaSmb?.optBooleanCompat("reduces_rbt_demand") ?: false,
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

    private fun JsonObject.jsonIsNull(key: String): Boolean {
        val element = this[key]
        return element == null || element is JsonNull
    }

    private fun JsonArray.optStringAt(index: Int): String {
        if (index !in indices) return ""
        val element = this[index]
        return when (element) {
            is JsonNull -> "null"
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    private fun JsonObject.optStringOrNull(key: String): String? =
        if (jsonIsNull(key)) null else optStringCompat(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.optDoubleOrNull(key: String): Double? =
        if (jsonIsNull(key)) null else optDoubleCompat(key, Double.NaN).takeIf { it.isFinite() }

    private fun JsonObject.optIntOrNull(key: String): Int? =
        if (jsonIsNull(key)) null else optIntCompat(key, 0)
}
