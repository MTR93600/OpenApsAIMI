package app.aaps.plugins.aps.openAPSAIMI.quality

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import kotlin.math.min

internal data class SmbBindingStage(
    val name: String,
    val beforeU: Double,
    val afterU: Double,
    val referenceU: Double? = null,
    val phase: String = "SEQUENTIAL",
    val kind: String = "TRANSFORM",
)

internal data class SmbBeforeTerminalProtectionsReplay(
    val timestampMs: Long,
    val autodriveRequestU: Double,
    val patternCapU: Double?,
    val afterPatternCapU: Double,
    val bindingStage: String?,
    val stages: List<SmbBindingStage>,
)

internal data class SmbBindingTrace(
    val timestampMs: Long,
    val originOwner: String,
    val finalOwner: String,
    val modelOutputU: Double?,
    val mpcOutputU: Double?,
    val tier: String,
    val smallPrebolusPrefU: Double?,
    val largePrebolusPrefU: Double?,
    val autodriveFloorU: Double?,
    val maxSmbU: Double?,
    val maxSmbHighBgU: Double?,
    /**
     * Which branch of the maxSMB ladder chose [maxSmbU], and the slope that branch read.
     *
     * The rise floor stops at [maxSmbU], so these two say whether the ladder we now obey saw the rise
     * or missed it. A `STANDARD` tag on a tick where BG is climbing fast means the ladder saw nothing.
     */
    val maxSmbLadderBranch: String?,
    val slopeFromMinDeviation: Double?,
    val iobHeadroomU: Double?,
    val rbtBeforeU: Double?,
    val rbtAfterU: Double?,
    val htrBeforeU: Double?,
    val htrAfterU: Double?,
    val preTerminalAfterCapsU: Double?,
    val patternActive: String?,
    val patternCapU: Double?,
    val safetyNetBaseLimitU: Double?,
    val pkpdBeforeU: Double?,
    val pkpdAfterU: Double?,
    val throttleBeforeU: Double?,
    val throttleAfterU: Double?,
    val redCarpetBeforeU: Double?,
    val redCarpetAfterU: Double?,
    val finalU: Double,
    val bindingStage: String?,
    val stages: List<SmbBindingStage>,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("timestamp_ms", timestampMs)
            put("owner", finalOwner)
            put("origin_owner", originOwner)
            put("final_owner", finalOwner)
            put("stage_structure", "ORDERED_EVENTS_NOT_STRICTLY_CONTIGUOUS")
            putNullable("model_output_u", modelOutputU)
            putNullable("mpc_output_u", mpcOutputU)
            put("tier", tier)
            putNullable("small_prebolus_pref_u", smallPrebolusPrefU)
            putNullable("large_prebolus_pref_u", largePrebolusPrefU)
            putNullable("autodrive_floor_u", autodriveFloorU)
            putNullable("max_smb_u", maxSmbU)
            putNullable("max_smb_high_bg_u", maxSmbHighBgU)
            putNullable("max_smb_ladder_branch", maxSmbLadderBranch)
            putNullable("slope_from_min_deviation", slopeFromMinDeviation)
            putNullable("iob_headroom_u", iobHeadroomU)
            putNullable("rbt_before_u", rbtBeforeU)
            putNullable("rbt_after_u", rbtAfterU)
            putNullable("htr_before_u", htrBeforeU)
            putNullable("htr_after_u", htrAfterU)
            putNullable("pre_terminal_after_caps_u", preTerminalAfterCapsU)
            putNullable("pattern_active", patternActive)
            putNullable("pattern_cap_u", patternCapU)
            putNullable("safety_net_base_limit_u", safetyNetBaseLimitU)
            putNullable("pkpd_before_u", pkpdBeforeU)
            putNullable("pkpd_after_u", pkpdAfterU)
            putNullable("throttle_before_u", throttleBeforeU)
            putNullable("throttle_after_u", throttleAfterU)
            putNullable("red_carpet_before_u", redCarpetBeforeU)
            putNullable("red_carpet_after_u", redCarpetAfterU)
            put("final_u", finalU)
            putNullable("binding_stage", bindingStage)
            put(
                "stages",
                buildJsonArray {
                    stages.forEach { stage ->
                        add(
                            buildJsonObject {
                                put("name", stage.name)
                                put("phase", stage.phase)
                                put("kind", stage.kind)
                                put("before_u", stage.beforeU)
                                put("after_u", stage.afterU)
                                putNullable("reference_u", stage.referenceU)
                                put("reduced", stage.beforeU - stage.afterU > REDUCTION_TOLERANCE_U)
                            },
                        )
                    }
                },
            )
        }

    internal data class Draft(
        val timestampMs: Long = 0L,
        val originOwner: String = "NONE",
        val finalOwner: String = "NONE",
        val modelOutputU: Double? = null,
        val mpcOutputU: Double? = null,
        val tier: String = "OFF",
        val smallPrebolusPrefU: Double? = null,
        val largePrebolusPrefU: Double? = null,
        val autodriveFloorU: Double? = null,
        val maxSmbU: Double? = null,
        val maxSmbHighBgU: Double? = null,
        val maxSmbLadderBranch: String? = null,
        val slopeFromMinDeviation: Double? = null,
        val iobHeadroomU: Double? = null,
        val rbtBeforeU: Double? = null,
        val rbtAfterU: Double? = null,
        val htrBeforeU: Double? = null,
        val htrAfterU: Double? = null,
        val preTerminalAfterCapsU: Double? = null,
        val patternActive: String? = null,
        val patternCapU: Double? = null,
        val safetyNetBaseLimitU: Double? = null,
        val pkpdBeforeU: Double? = null,
        val pkpdAfterU: Double? = null,
        val throttleBeforeU: Double? = null,
        val throttleAfterU: Double? = null,
        val redCarpetBeforeU: Double? = null,
        val redCarpetAfterU: Double? = null,
        val stages: List<SmbBindingStage> = emptyList(),
    ) {
        fun appendStage(
            name: String,
            beforeU: Double,
            afterU: Double,
            referenceU: Double? = null,
            phase: String = "SEQUENTIAL",
            kind: String = "TRANSFORM",
        ): Draft =
            copy(stages = stages + SmbBindingStage(name, beforeU, afterU, referenceU, phase, kind))

        fun build(finalU: Double): SmbBindingTrace {
            val finiteStages = stages.filter { it.beforeU.isFinite() && it.afterU.isFinite() }
            return SmbBindingTrace(
                timestampMs = timestampMs,
                originOwner = originOwner,
                finalOwner = finalOwner,
                modelOutputU = modelOutputU.finiteOrNull(),
                mpcOutputU = mpcOutputU.finiteOrNull(),
                tier = tier,
                smallPrebolusPrefU = smallPrebolusPrefU.finiteOrNull(),
                largePrebolusPrefU = largePrebolusPrefU.finiteOrNull(),
                autodriveFloorU = autodriveFloorU.finiteOrNull(),
                maxSmbU = maxSmbU.finiteOrNull(),
                maxSmbHighBgU = maxSmbHighBgU.finiteOrNull(),
                maxSmbLadderBranch = maxSmbLadderBranch,
                // NaN or Inf must never reach org.json — export null instead.
                slopeFromMinDeviation = slopeFromMinDeviation.finiteOrNull(),
                iobHeadroomU = iobHeadroomU.finiteOrNull(),
                rbtBeforeU = rbtBeforeU.finiteOrNull(),
                rbtAfterU = rbtAfterU.finiteOrNull(),
                htrBeforeU = htrBeforeU.finiteOrNull(),
                htrAfterU = htrAfterU.finiteOrNull(),
                preTerminalAfterCapsU = preTerminalAfterCapsU.finiteOrNull(),
                patternActive = patternActive,
                patternCapU = patternCapU.finiteOrNull(),
                safetyNetBaseLimitU = safetyNetBaseLimitU.finiteOrNull(),
                pkpdBeforeU = pkpdBeforeU.finiteOrNull(),
                pkpdAfterU = pkpdAfterU.finiteOrNull(),
                throttleBeforeU = throttleBeforeU.finiteOrNull(),
                throttleAfterU = throttleAfterU.finiteOrNull(),
                redCarpetBeforeU = redCarpetBeforeU.finiteOrNull(),
                redCarpetAfterU = redCarpetAfterU.finiteOrNull(),
                finalU = finalU.takeIf { it.isFinite() } ?: 0.0,
                bindingStage = finiteStages.firstOrNull {
                    it.beforeU - it.afterU > REDUCTION_TOLERANCE_U
                }?.name,
                stages = finiteStages,
            )
        }
    }

    companion object {
        const val REDUCTION_TOLERANCE_U = 0.001

        fun terminalAmountU(finalResultUnits: Double?): Double =
            finalResultUnits?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

        /**
         * Isolates only the pattern-cap counterfactual. The returned value is deliberately not a
         * terminal dose prediction: SafetyNet, PKPD, refractory, throttle and Red Carpet are absent.
         *
         * Soft meal proposals do not bind — only [PatternCapKind.HARD] applies min().
         */
        fun replayCapsBeforeTerminalProtections(
            timestampMs: Long,
            autodriveRequestU: Double,
            patternCapU: Double?,
            patternCapKind: PatternCapKind? = PatternCapKind.HARD,
        ): SmbBeforeTerminalProtectionsReplay {
            val request = autodriveRequestU.coerceAtLeast(0.0)
            val binds = patternCapU != null && patternCapKind == PatternCapKind.HARD
            val afterPatternCap = if (binds) {
                min(request, patternCapU!!.coerceAtLeast(0.0))
            } else {
                request
            }
            val stageName = if (patternCapKind == PatternCapKind.SOFT) "PATTERN_SOFT_PROPOSAL" else "PATTERN_CAP"
            val stageKind = if (binds) "CAP" else "PROPOSAL"
            val stage = SmbBindingStage(
                name = stageName,
                beforeU = request,
                afterU = afterPatternCap,
                referenceU = patternCapU,
                phase = "AUTODRIVE_PRE_TERMINAL",
                kind = stageKind,
            )
            return SmbBeforeTerminalProtectionsReplay(
                timestampMs = timestampMs,
                autodriveRequestU = request,
                patternCapU = patternCapU,
                afterPatternCapU = afterPatternCap,
                bindingStage = stage.takeIf {
                    it.beforeU - it.afterU > REDUCTION_TOLERANCE_U
                }?.name,
                stages = listOf(stage),
            )
        }
    }
}

private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

private fun JsonObjectBuilder.putNullable(key: String, value: Any?) {
    when (value) {
        null -> put(key, JsonNull)
        is Boolean -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Float -> put(key, value)
        is Double -> put(key, value)
        is String -> put(key, value)
        is JsonElement -> put(key, value)
        else -> put(key, value.toString())
    }
}
