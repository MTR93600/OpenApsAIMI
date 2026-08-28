package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import kotlin.math.max
import kotlin.math.min

/**
 * Wave4 H3 — production soft-floor / EGP path-min telemetry (study JSON + decision alignment).
 *
 * Physics live in `AdvancedPredictionEngine` (EGP on IOB/COB/UAM/ZT + hybrid). This object
 * packages raw vs soft path-min for JSONL. Soft for dose/graph remap uses hybrid EGP anchor when
 * insulin curves were endo-lifted but an early absorbing-floor point still poisons series-min.
 */
data class PkpdSoftFloorTelemetry(
    val rawPathMinMgdl: Double?,
    val softPathMinMgdl: Double?,
    val hybridTerminalMgdl: Double?,
    val hitNumericFloor: Boolean,
    val applied: Boolean,
    val endogenousReversionEnabled: Boolean,
    /** Guard B — EGP suspended this tick because BG was falling hard. */
    val suppressedByFallingTrend: Boolean,
    val reason: String,
    /**
     * The insulin-only path minimum with the numeric floor removed, the count of steps the floor
     * clipped, and the sensitivity the engine integrated.
     *
     * These three make the published minimum interpretable. `raw_path_min_mgdl` = 39 with
     * `unclipped_path_min_mgdl` = -274 and 40 clipped steps is a saturated arithmetic result, not a
     * forecast of hypoglycaemia; the same 39 with 1 clipped step and an unclipped value of 38 is a
     * real one. Everything downstream of the path minimum — the tube advisor's veto above all —
     * currently sees only the 39 and cannot tell the two apart.
     */
    val unclippedPathMinMgdl: Double? = null,
    val numericFloorClippedSteps: Int = 0,
    val sensitivityUsedMgdlPerU: Double? = null,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("applied", applied)
            put("shadow_only", false)
            rawPathMinMgdl?.let { put("raw_path_min_mgdl", it) }
            softPathMinMgdl?.let { put("soft_path_min_mgdl", it) }
            hybridTerminalMgdl?.let { put("hybrid_terminal_mgdl", it) }
            unclippedPathMinMgdl?.let { put("unclipped_path_min_mgdl", it) }
            put("numeric_floor_clipped_steps", numericFloorClippedSteps)
            sensitivityUsedMgdlPerU?.let { put("sensitivity_used_mgdl_per_u", it) }
            rawPathMinMgdl?.let { raw ->
                unclippedPathMinMgdl?.let { unclipped ->
                    put("floor_saturation_mgdl", raw - unclipped)
                }
            }
            put("hit_numeric_floor", hitNumericFloor)
            put("endogenous_reversion_enabled", endogenousReversionEnabled)
            put("suppressed_by_falling_trend", suppressedByFallingTrend)
            put("reason", reason)
            softPathMinMgdl?.let { soft ->
                rawPathMinMgdl?.let { raw ->
                    put("delta_soft_minus_raw_mgdl", soft - raw)
                }
            }
        }
}

object PkpdSoftFloorPathMin {

    const val LOG_PREFIX = "PKPD_SOFT_FLOOR"

    /** Must match `AdvancedPredictionEngine` NUMERIC_FLOOR. */
    const val NUMERIC_FLOOR_MGDL = 39.0

    /** Absolute reversion ceiling — must match `AdvancedPredictionEngine` ENDO_REVERSION_BASELINE_MGDL.
     * The effective cap is dynamic (Guard A: `min(this, max(currentBG, floor))`), computed in
     * [resolveSoftPathMin] so the soft path-min never exceeds the current BG on a low plateau. */
    const val ENDO_REVERSION_BASELINE_MGDL = 80.0

    /**
     * Near-floor band shared with [DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL].
     */
    const val FLOOR_ARTEFACT_NEAR_MGDL = DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL

    fun fromCurves(
        curves: AdvancedPredictionCurves,
        endogenousReversionEnabled: Boolean,
    ): PkpdSoftFloorTelemetry {
        val publishedMin = insulinOnlyPathMin(curves)
        val raw = curves.insulinPathMinRawMgdl ?: publishedMin
        val hybridTerminal = curves.hybridTerminal
        val hitFloor = raw != null && raw <= FLOOR_ARTEFACT_NEAR_MGDL
        val endoOnInsulin = curves.endogenousReversionOnInsulinCurves
        val suppressedByTrend = curves.endogenousReversionSuppressedByTrend

        val soft = resolveSoftPathMin(
            raw = raw,
            publishedMin = publishedMin,
            hybridTerminal = hybridTerminal,
            currentBgMgdl = curves.iob.firstOrNull()?.takeIf { it.isFinite() },
            endogenousReversionEnabled = endogenousReversionEnabled,
            endoOnInsulin = endoOnInsulin,
        )
        val applied = endogenousReversionEnabled &&
            endoOnInsulin &&
            raw != null &&
            soft != null &&
            soft > raw + 0.5

        val reason = when {
            publishedMin == null -> "no_insulin_curves"
            !endogenousReversionEnabled -> "endo_reversion_disabled"
            applied -> "egp_applied_on_insulin_curves"
            suppressedByTrend && hitFloor -> "endo_suppressed_falling_trend"
            hitFloor && !endoOnInsulin -> "floor_but_insulin_still_active"
            hitFloor -> "floor_no_material_lift"
            else -> "raw_above_floor_band"
        }

        return PkpdSoftFloorTelemetry(
            rawPathMinMgdl = raw,
            softPathMinMgdl = soft,
            hybridTerminalMgdl = hybridTerminal,
            hitNumericFloor = hitFloor,
            applied = applied,
            endogenousReversionEnabled = endogenousReversionEnabled,
            suppressedByFallingTrend = suppressedByTrend,
            reason = reason,
            unclippedPathMinMgdl = curves.insulinPathMinUnclippedMgdl,
            numericFloorClippedSteps = curves.numericFloorClippedSteps,
            sensitivityUsedMgdlPerU = curves.effectiveSensitivityUsedMgdlPerU,
        )
    }

    fun formatLogLine(telemetry: PkpdSoftFloorTelemetry): String {
        val raw = telemetry.rawPathMinMgdl?.toInt()?.toString() ?: "n/a"
        val soft = telemetry.softPathMinMgdl?.toInt()?.toString() ?: "n/a"
        val hyb = telemetry.hybridTerminalMgdl?.toInt()?.toString() ?: "n/a"
        return "$LOG_PREFIX: raw=$raw soft=$soft hybT=$hyb " +
            "hitFloor=${telemetry.hitNumericFloor} applied=${telemetry.applied} " +
            "endo=${telemetry.endogenousReversionEnabled} fallSuppressed=${telemetry.suppressedByFallingTrend} " +
            "reason=${telemetry.reason}"
    }

    /**
     * Lift absorbing-floor points in a published integer prediction series up to [softFloorMgdl]
     * so graph + `minPredictedAcrossCurves` share production EGP physics.
     * Only touches values in the floor artefact band (≤ [FLOOR_ARTEFACT_NEAR_MGDL]).
     */
    fun liftFloorBandPoints(series: List<Int>, softFloorMgdl: Double): List<Int> {
        if (!softFloorMgdl.isFinite()) return series
        val softInt = softFloorMgdl.toInt().coerceAtLeast(NUMERIC_FLOOR_MGDL.toInt())
        return series.map { point ->
            if (point <= FLOOR_ARTEFACT_NEAR_MGDL.toInt()) {
                maxOf(point, softInt)
            } else {
                point
            }
        }
    }

    private fun resolveSoftPathMin(
        raw: Double?,
        publishedMin: Double?,
        hybridTerminal: Double?,
        currentBgMgdl: Double?,
        endogenousReversionEnabled: Boolean,
        endoOnInsulin: Boolean,
    ): Double? {
        if (!endogenousReversionEnabled || !endoOnInsulin) return publishedMin
        // Guard A made explicit here (do not rely solely on the engine capping hybridTerminal):
        // dynamic cap = min(ceiling, max(currentBG, floor)) so the soft path-min never exceeds the
        // current BG on a low plateau. Falls back to the flat ceiling when BG is unknown.
        val dynamicCap = currentBgMgdl
            ?.let { min(ENDO_REVERSION_BASELINE_MGDL, max(it, NUMERIC_FLOOR_MGDL)) }
            ?: ENDO_REVERSION_BASELINE_MGDL
        val anchor = hybridTerminal
            ?.takeIf { it.isFinite() && it > FLOOR_ARTEFACT_NEAR_MGDL }
            ?.let { min(it, dynamicCap) }
        if (anchor == null || raw == null) return publishedMin
        return max(raw, anchor)
    }

    private fun insulinOnlyPathMin(curves: AdvancedPredictionCurves): Double? {
        val mins = listOf(curves.iob, curves.cob, curves.uam, curves.zt)
            .mapNotNull { series ->
                series.filter { it.isFinite() }.minOrNull()
            }
        if (mins.isEmpty()) return null
        return mins.minOrNull()?.takeIf { it.isFinite() }
    }
}
