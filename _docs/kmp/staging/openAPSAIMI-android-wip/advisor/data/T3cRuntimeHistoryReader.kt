package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import android.os.Environment
import java.io.File
import org.json.JSONObject

internal enum class T3cRuntimeTickStatus {
    NATIVE_APPLIED,
    NATIVE_READY,
    NATIVE_BLOCKED,
    LEGACY_FALLBACK,
    SAFETY_TERMINAL,
    UNAVAILABLE,
}

internal enum class T3cRuntimeOwnershipCategory {
    NATIVE,
    LEGACY,
    SAFETY,
    UNAVAILABLE,
}

internal data class T3cRuntimeTickRecord(
    val timestampMs: Long,
    val mode: String,
    val status: T3cRuntimeTickStatus,
    val ownershipCategory: T3cRuntimeOwnershipCategory,
    val nativeOwnerActive: Boolean,
    val legacyFallbackAllowed: Boolean,
    val ownershipReason: String?,
    val authorityApplied: Boolean,
    val shadowOnly: Boolean,
    val active: Boolean,
    val eligible: Boolean,
    val mealConflict: Boolean,
    val postHypoBlock: Boolean,
    val exerciseBlock: Boolean,
    val hardSafetyBlock: Boolean,
    val basalDemandRateUph: Double?,
    val boundedRateUph: Double?,
    val appliedRateUph: Double?,
    val appliedDurationMin: Int?,
    val blocker: String?,
    val selectedForProduction: Boolean,
    val historicalBypassNeutralized: Boolean,
)

internal data class T3cRuntimeNumericStats(
    val count: Int,
    val average: Double,
    val min: Double,
    val max: Double,
)

internal data class T3cOwnershipTransition(
    val from: T3cRuntimeOwnershipCategory,
    val to: T3cRuntimeOwnershipCategory,
    val count: Int,
)

internal enum class T3cAdvisorObservationFamily {
    STABILITY,
    MEAL_CAPTURE,
    PHYSIO_AMBIGUITY,
    POST_HYPO_RECOVERY,
    ACTIVITY,
    AUTONOMY,
    NATIVE_RBT,
}

internal enum class T3cAdvisorObservationLevel {
    HIGH,
    MEDIUM,
    LOW,
    STABLE,
}

internal enum class T3cAdvisorObservationSignal {
    SAFETY_GATES_OFTEN_BLOCK,
    MEAL_CONFLICTS_APPEAR,
    POST_HYPO_GUARD_DOMINATES,
    ACTIVITY_LOCKOUT_VISIBLE,
    LEGACY_FALLBACK_VISIBLE,
    NATIVE_APPLIES_WHEN_CLEAR,
    BLOCKERS_STAY_MIXED,
}

internal data class T3cAdvisorObservation(
    val family: T3cAdvisorObservationFamily,
    val level: T3cAdvisorObservationLevel,
    val signal: T3cAdvisorObservationSignal,
    val weight: Int,
)

internal data class T3cRuntimeHistorySummary(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val tickCount: Int,
    val notEnoughData: Boolean,
    val dominantStatus: T3cRuntimeTickStatus?,
    val nativeAppliedCount: Int,
    val nativeBlockedCount: Int,
    val legacyFallbackCount: Int,
    val safetyTerminalCount: Int,
    val dominantBlocker: String?,
    val demandStats: T3cRuntimeNumericStats?,
    val appliedRateStats: T3cRuntimeNumericStats?,
    val transitionCount: Int,
    val dominantTransition: T3cOwnershipTransition?,
    val familyObservations: List<T3cAdvisorObservation>,
)

internal object T3cRuntimeHistoryReader {

    private const val WINDOW_24H_MS = 24L * 60L * 60L * 1000L
  /** Capped for Advisor launch on 256MB heaps; 24h of ticks fits well below this. */
    private const val MAX_HISTORY_LINES = 400
    private const val MAX_LATEST_LINES = 120
    private const val MIN_HISTORY_TICKS = 6

    fun aimiDecisionsJsonlFile(): File {
        val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(externalDir, "AAPS/AIMI_Decisions.jsonl")
    }

    fun readLatestTick(
        file: File = aimiDecisionsJsonlFile(),
    ): T3cRuntimeTickRecord? {
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
        file: File = aimiDecisionsJsonlFile(),
        nowMs: Long = System.currentTimeMillis(),
    ): T3cRuntimeHistorySummary? {
        if (!file.exists() || !file.canRead()) return null
        val cutoffMs = nowMs - WINDOW_24H_MS
        val tail = JsonlTailReader.readTailLines(file, maxLines = MAX_HISTORY_LINES)
        val records = mutableListOf<T3cRuntimeTickRecord>()

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
            return T3cRuntimeHistorySummary(
                windowStartMs = cutoffMs,
                windowEndMs = nowMs,
                tickCount = 0,
                notEnoughData = true,
                dominantStatus = null,
                nativeAppliedCount = 0,
                nativeBlockedCount = 0,
                legacyFallbackCount = 0,
                safetyTerminalCount = 0,
                dominantBlocker = null,
                demandStats = null,
                appliedRateStats = null,
                transitionCount = 0,
                dominantTransition = null,
                familyObservations = emptyList(),
            )
        }

        val chronological = records.asReversed()
        val statusCounts = T3cRuntimeTickStatus.values().associateWith { status ->
            records.count { it.status == status }
        }
        val dominantStatus = T3cRuntimeTickStatus.values().maxByOrNull { statusCounts[it] ?: 0 }
        val dominantBlocker = records
            .mapNotNull { it.blocker ?: it.ownershipReason }
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
        val transitions = buildOwnershipTransitions(chronological)
        val transitionCount = transitions.sumOf { it.count }
        val dominantTransition = transitions.maxByOrNull { it.count }
        val familyObservations = buildAdvisorFamilyObservations(
            records = records,
            nativeAppliedCount = statusCounts[T3cRuntimeTickStatus.NATIVE_APPLIED] ?: 0,
            nativeBlockedCount = statusCounts[T3cRuntimeTickStatus.NATIVE_BLOCKED] ?: 0,
            legacyFallbackCount = statusCounts[T3cRuntimeTickStatus.LEGACY_FALLBACK] ?: 0,
            safetyTerminalCount = statusCounts[T3cRuntimeTickStatus.SAFETY_TERMINAL] ?: 0,
        )

        return T3cRuntimeHistorySummary(
            windowStartMs = cutoffMs,
            windowEndMs = nowMs,
            tickCount = records.size,
            notEnoughData = records.size < MIN_HISTORY_TICKS,
            dominantStatus = dominantStatus,
            nativeAppliedCount = statusCounts[T3cRuntimeTickStatus.NATIVE_APPLIED] ?: 0,
            nativeBlockedCount = statusCounts[T3cRuntimeTickStatus.NATIVE_BLOCKED] ?: 0,
            legacyFallbackCount = statusCounts[T3cRuntimeTickStatus.LEGACY_FALLBACK] ?: 0,
            safetyTerminalCount = statusCounts[T3cRuntimeTickStatus.SAFETY_TERMINAL] ?: 0,
            dominantBlocker = dominantBlocker,
            demandStats = demandStats,
            appliedRateStats = appliedRateStats,
            transitionCount = transitionCount,
            dominantTransition = dominantTransition,
            familyObservations = familyObservations,
        )
    }

    private fun parseTick(root: JSONObject): T3cRuntimeTickRecord? {
        val adjustments = root.optJSONObject("adjustments") ?: return null
        val recursiveBelief = adjustments.optJSONObject("recursive_belief")
        val ownership = adjustments.optJSONObject("t3c_runtime_ownership")
        val resolution = recursiveBelief?.optJSONObject("resolution")
        val t3c = resolution?.optJSONObject("t3c_basal_first")
        if (ownership == null && t3c == null) return null

        val authorityApplied = recursiveBelief?.optBoolean("authority_applied", false) ?: false
        val shadowOnly = recursiveBelief?.optBoolean("shadow_only", true) ?: true
        val active = t3c?.optBoolean("active", false) ?: false
        val eligible = t3c?.optBoolean("eligible", false) ?: false
        val mealConflict = t3c?.optBoolean("meal_conflict", false) ?: false
        val postHypoBlock = t3c?.optBoolean("post_hypo_block", false) ?: false
        val exerciseBlock = t3c?.optBoolean("exercise_block", false) ?: false
        val hardSafetyBlock = t3c?.optBoolean("hard_safety_block", false) ?: false
        val nativeOwnerActive = ownership?.optBoolean("native_owner_active", false) ?: false
        val legacyFallbackAllowed = ownership?.optBoolean("legacy_fallback_allowed", false) ?: false
        val mode = ownership.optStringOrDefault(
            "mode",
            deriveFallbackMode(authorityApplied = authorityApplied, active = active, eligible = eligible),
        )
        val status = deriveStatus(
            mode = mode,
            authorityApplied = authorityApplied,
            active = active,
            eligible = eligible,
        )
        val ownershipCategory = deriveOwnershipCategory(
            status = status,
            nativeOwnerActive = nativeOwnerActive,
            legacyFallbackAllowed = legacyFallbackAllowed,
        )
        val ownershipReason = ownership?.optStringOrNull("reason")
        val blocker = when (status) {
            T3cRuntimeTickStatus.NATIVE_BLOCKED ->
                t3c?.optStringOrNull("runtime_blocker")
                    ?: t3c?.optStringOrNull("dominant_blocker")
                    ?: ownershipReason
            T3cRuntimeTickStatus.LEGACY_FALLBACK,
            T3cRuntimeTickStatus.SAFETY_TERMINAL,
            -> ownershipReason ?: t3c?.optStringOrNull("runtime_blocker") ?: t3c?.optStringOrNull("dominant_blocker")
            else -> null
        }

        return T3cRuntimeTickRecord(
            timestampMs = root.optLong("timestamp", 0L),
            mode = mode,
            status = status,
            ownershipCategory = ownershipCategory,
            nativeOwnerActive = nativeOwnerActive,
            legacyFallbackAllowed = legacyFallbackAllowed,
            ownershipReason = ownershipReason,
            authorityApplied = authorityApplied,
            shadowOnly = shadowOnly,
            active = active,
            eligible = eligible,
            mealConflict = mealConflict,
            postHypoBlock = postHypoBlock,
            exerciseBlock = exerciseBlock,
            hardSafetyBlock = hardSafetyBlock,
            basalDemandRateUph = t3c?.optDoubleOrNull("basal_demand_rate_uph"),
            boundedRateUph = t3c?.optDoubleOrNull("bounded_rate_uph"),
            appliedRateUph = t3c?.optDoubleOrNull("applied_rate_uph"),
            appliedDurationMin = t3c?.optIntOrNull("applied_duration_min"),
            blocker = blocker,
            selectedForProduction = t3c?.optBoolean("selected_for_production", false) ?: false,
            historicalBypassNeutralized = t3c?.optBoolean("historical_bypass_neutralized", false) ?: false,
        )
    }

    private fun deriveFallbackMode(
        authorityApplied: Boolean,
        active: Boolean,
        eligible: Boolean,
    ): String =
        when {
            authorityApplied -> "NATIVE_APPLIED"
            eligible -> "NATIVE_READY"
            active -> "NATIVE_BLOCKED"
            else -> "UNAVAILABLE"
        }

    private fun deriveStatus(
        mode: String,
        authorityApplied: Boolean,
        active: Boolean,
        eligible: Boolean,
    ): T3cRuntimeTickStatus =
        when (mode) {
            "NATIVE_APPLIED" -> T3cRuntimeTickStatus.NATIVE_APPLIED
            "NATIVE_READY" -> T3cRuntimeTickStatus.NATIVE_READY
            "NATIVE_BLOCKED" -> T3cRuntimeTickStatus.NATIVE_BLOCKED
            "LEGACY_FALLBACK" -> T3cRuntimeTickStatus.LEGACY_FALLBACK
            "SAFETY_TERMINAL" -> T3cRuntimeTickStatus.SAFETY_TERMINAL
            "LEGACY_SKIPPED" -> when {
                authorityApplied -> T3cRuntimeTickStatus.NATIVE_APPLIED
                eligible || active -> T3cRuntimeTickStatus.NATIVE_READY
                else -> T3cRuntimeTickStatus.UNAVAILABLE
            }
            else -> when {
                authorityApplied -> T3cRuntimeTickStatus.NATIVE_APPLIED
                eligible -> T3cRuntimeTickStatus.NATIVE_READY
                active -> T3cRuntimeTickStatus.NATIVE_BLOCKED
                else -> T3cRuntimeTickStatus.UNAVAILABLE
            }
        }

    private fun deriveOwnershipCategory(
        status: T3cRuntimeTickStatus,
        nativeOwnerActive: Boolean,
        legacyFallbackAllowed: Boolean,
    ): T3cRuntimeOwnershipCategory =
        when (status) {
            T3cRuntimeTickStatus.NATIVE_APPLIED,
            T3cRuntimeTickStatus.NATIVE_READY,
            T3cRuntimeTickStatus.NATIVE_BLOCKED
            -> T3cRuntimeOwnershipCategory.NATIVE
            T3cRuntimeTickStatus.LEGACY_FALLBACK -> T3cRuntimeOwnershipCategory.LEGACY
            T3cRuntimeTickStatus.SAFETY_TERMINAL -> T3cRuntimeOwnershipCategory.SAFETY
            T3cRuntimeTickStatus.UNAVAILABLE -> when {
                nativeOwnerActive -> T3cRuntimeOwnershipCategory.NATIVE
                legacyFallbackAllowed -> T3cRuntimeOwnershipCategory.LEGACY
                else -> T3cRuntimeOwnershipCategory.UNAVAILABLE
            }
        }

    private fun buildNumericStats(values: List<Double>): T3cRuntimeNumericStats? {
        if (values.isEmpty()) return null
        return T3cRuntimeNumericStats(
            count = values.size,
            average = values.average(),
            min = values.minOrNull() ?: 0.0,
            max = values.maxOrNull() ?: 0.0,
        )
    }

    private fun buildOwnershipTransitions(
        chronological: List<T3cRuntimeTickRecord>,
    ): List<T3cOwnershipTransition> {
        val counts = linkedMapOf<Pair<T3cRuntimeOwnershipCategory, T3cRuntimeOwnershipCategory>, Int>()
        for (index in 1 until chronological.size) {
            val previous = chronological[index - 1].ownershipCategory
            val current = chronological[index].ownershipCategory
            if (previous == current) continue
            val key = previous to current
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.map { (pair, count) ->
            T3cOwnershipTransition(
                from = pair.first,
                to = pair.second,
                count = count,
            )
        }
    }

    private fun buildAdvisorFamilyObservations(
        records: List<T3cRuntimeTickRecord>,
        nativeAppliedCount: Int,
        nativeBlockedCount: Int,
        legacyFallbackCount: Int,
        safetyTerminalCount: Int,
    ): List<T3cAdvisorObservation> {
        if (records.size < MIN_HISTORY_TICKS) return emptyList()

        val totalTicks = records.size
        val blockedRecords = records.filter { it.status == T3cRuntimeTickStatus.NATIVE_BLOCKED }
        val blockedCount = blockedRecords.size.coerceAtLeast(1)
        val mealConflictCount = blockedRecords.count {
            it.mealConflict || it.blocker.containsAny("meal_conflict", "meal")
        }
        val postHypoCount = blockedRecords.count {
            it.postHypoBlock || it.blocker.containsAny("post_hypo", "hypo")
        }
        val activityCount = records.count {
            it.exerciseBlock || it.blocker.containsAny("exercise", "activity", "sport")
        }
        val hardSafetyCount = records.count {
            it.status == T3cRuntimeTickStatus.SAFETY_TERMINAL ||
                it.hardSafetyBlock ||
                it.blocker.containsAny("hard_safety", "stack", "max_iob", "final_settemp_block")
        }
        val blockedShares = buildList {
            if (mealConflictCount > 0) add(mealConflictCount.toDouble() / blockedCount)
            if (postHypoCount > 0) add(postHypoCount.toDouble() / blockedCount)
            if (activityCount > 0) add(activityCount.toDouble() / totalTicks)
            if (hardSafetyCount > 0) add(hardSafetyCount.toDouble() / totalTicks)
        }
        val dominantBlockedShare = blockedShares.maxOrNull() ?: 0.0

        val candidates = mutableListOf<T3cAdvisorObservation>()
        candidateSafetyObservation(hardSafetyCount, totalTicks)?.let { candidates += it }
        candidateMealObservation(mealConflictCount, blockedCount)?.let { candidates += it }
        candidatePostHypoObservation(postHypoCount, blockedCount)?.let { candidates += it }
        candidateActivityObservation(activityCount, totalTicks)?.let { candidates += it }
        candidateLegacyObservation(legacyFallbackCount, totalTicks)?.let { candidates += it }
        candidateNativeObservation(
            nativeAppliedCount = nativeAppliedCount,
            nativeBlockedCount = nativeBlockedCount,
            legacyFallbackCount = legacyFallbackCount,
            safetyTerminalCount = safetyTerminalCount,
            totalTicks = totalTicks,
        )?.let { candidates += it }
        candidatePhysioAmbiguityObservation(
            blockedRecords = blockedRecords,
            blockedCount = blockedCount,
            dominantBlockedShare = dominantBlockedShare,
            nativeAppliedCount = nativeAppliedCount,
            legacyFallbackCount = legacyFallbackCount,
        )?.let { candidates += it }

        return candidates
            .sortedByDescending { it.weight }
            .distinctBy { it.family to it.signal }
            .take(5)
    }

    private fun candidateSafetyObservation(
        safetyCount: Int,
        totalTicks: Int,
    ): T3cAdvisorObservation? {
        if (safetyCount < 2) return null
        val share = safetyCount.toDouble() / totalTicks
        if (share < 0.15) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.STABILITY,
            level = levelFromShare(share),
            signal = T3cAdvisorObservationSignal.SAFETY_GATES_OFTEN_BLOCK,
            weight = (share * 100).toInt() + 20,
        )
    }

    private fun candidateMealObservation(
        mealConflictCount: Int,
        blockedCount: Int,
    ): T3cAdvisorObservation? {
        if (mealConflictCount < 2 || blockedCount <= 0) return null
        val share = mealConflictCount.toDouble() / blockedCount
        if (share < 0.20) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.MEAL_CAPTURE,
            level = levelFromShare(share),
            signal = T3cAdvisorObservationSignal.MEAL_CONFLICTS_APPEAR,
            weight = (share * 100).toInt() + 10,
        )
    }

    private fun candidatePostHypoObservation(
        postHypoCount: Int,
        blockedCount: Int,
    ): T3cAdvisorObservation? {
        if (postHypoCount < 2 || blockedCount <= 0) return null
        val share = postHypoCount.toDouble() / blockedCount
        if (share < 0.20) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.POST_HYPO_RECOVERY,
            level = levelFromShare(share),
            signal = T3cAdvisorObservationSignal.POST_HYPO_GUARD_DOMINATES,
            weight = (share * 100).toInt() + 8,
        )
    }

    private fun candidateActivityObservation(
        activityCount: Int,
        totalTicks: Int,
    ): T3cAdvisorObservation? {
        if (activityCount < 2) return null
        val share = activityCount.toDouble() / totalTicks
        if (share < 0.12) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.ACTIVITY,
            level = levelFromShare(share),
            signal = T3cAdvisorObservationSignal.ACTIVITY_LOCKOUT_VISIBLE,
            weight = (share * 100).toInt() + 6,
        )
    }

    private fun candidateLegacyObservation(
        legacyFallbackCount: Int,
        totalTicks: Int,
    ): T3cAdvisorObservation? {
        if (legacyFallbackCount < 2) return null
        val share = legacyFallbackCount.toDouble() / totalTicks
        if (share < 0.10) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.AUTONOMY,
            level = levelFromShare(share),
            signal = T3cAdvisorObservationSignal.LEGACY_FALLBACK_VISIBLE,
            weight = (share * 100).toInt() + 5,
        )
    }

    private fun candidateNativeObservation(
        nativeAppliedCount: Int,
        nativeBlockedCount: Int,
        legacyFallbackCount: Int,
        safetyTerminalCount: Int,
        totalTicks: Int,
    ): T3cAdvisorObservation? {
        if (nativeAppliedCount < 3) return null
        val share = nativeAppliedCount.toDouble() / totalTicks
        if (share < 0.35 || nativeAppliedCount < nativeBlockedCount) return null
        val level = if (
            share >= 0.50 &&
            safetyTerminalCount.toDouble() / totalTicks < 0.25 &&
            legacyFallbackCount.toDouble() / totalTicks < 0.20
        ) {
            T3cAdvisorObservationLevel.STABLE
        } else {
            T3cAdvisorObservationLevel.MEDIUM
        }
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.NATIVE_RBT,
            level = level,
            signal = T3cAdvisorObservationSignal.NATIVE_APPLIES_WHEN_CLEAR,
            weight = (share * 100).toInt() + 4,
        )
    }

    private fun candidatePhysioAmbiguityObservation(
        blockedRecords: List<T3cRuntimeTickRecord>,
        blockedCount: Int,
        dominantBlockedShare: Double,
        nativeAppliedCount: Int,
        legacyFallbackCount: Int,
    ): T3cAdvisorObservation? {
        if (blockedRecords.size < 3) return null
        if (dominantBlockedShare >= 0.45) return null
        if (nativeAppliedCount > blockedRecords.size && legacyFallbackCount <= 1) return null
        return T3cAdvisorObservation(
            family = T3cAdvisorObservationFamily.PHYSIO_AMBIGUITY,
            level = if (blockedCount >= 5) T3cAdvisorObservationLevel.MEDIUM else T3cAdvisorObservationLevel.LOW,
            signal = T3cAdvisorObservationSignal.BLOCKERS_STAY_MIXED,
            weight = 30 + blockedCount,
        )
    }

    private fun levelFromShare(share: Double): T3cAdvisorObservationLevel =
        when {
            share >= 0.35 -> T3cAdvisorObservationLevel.HIGH
            share >= 0.20 -> T3cAdvisorObservationLevel.MEDIUM
            else -> T3cAdvisorObservationLevel.LOW
        }

    private fun String?.containsAny(vararg tokens: String): Boolean {
        val value = this?.lowercase() ?: return false
        return tokens.any { token -> value.contains(token.lowercase()) }
    }

    private fun JSONObject?.optStringOrDefault(
        key: String,
        defaultValue: String,
    ): String =
        this?.optString(key)?.takeIf { it.isNotBlank() } ?: defaultValue

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)
}
