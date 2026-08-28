package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cascade D4 / C1 — single dose-facing eventual + minPred for the tick.
 *
 * Built once after Prediction Authority apply (+ thin Clamp). `SafetyNet`, stacking, Tube
 * refresh and SMB gates must read this instead of raw PKPD / curve-min floors.
 */
data class DoseTerminalSnapshot(
    val eventualMgdl: Double,
    val minPredMgdl: Double,
    val source: String,
    val authorityApplied: Boolean,
    val clampReconciled: Boolean,
    val clampReason: String?,
    val predBGsRemapped: Boolean,
    /** Wave1 H0/H1: true when high flat BG + numeric-floor artefact lifted minPred/eventual. */
    val plateauFloorLifted: Boolean = false,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("eventual_mgdl", eventualMgdl)
            put("min_pred_mgdl", minPredMgdl)
            put("source", source)
            put("authority_applied", authorityApplied)
            put("clamp_reconciled", clampReconciled)
            clampReason?.let { put("clamp_reason", it) }
            put("pred_bgs_remapped", predBGsRemapped)
            put("plateau_floor_lifted", plateauFloorLifted)
        }

    companion object {
        const val LOG_PREFIX = "DOSE_TERMINAL_SNAPSHOT"

        /** PKPD curve absorbing floor (AdvancedPredictionEngine.NUMERIC_FLOOR). */
        const val NUMERIC_FLOOR_MGDL = 39.0

        /** Treat path-min ≤ this as floor-artefact candidate. */
        const val FLOOR_ARTEFACT_NEAR_MGDL = 45.0

        /** High-BG plateau band for floor-artefact lift. */
        const val PLATEAU_BG_MGDL = 160.0

        /** Flat |Δ5| threshold (mg/dL/5min). */
        const val PLATEAU_FLAT_DELTA_ABS_MGDL = 2.5

        fun formatLogLine(snapshot: DoseTerminalSnapshot): String =
            "$LOG_PREFIX: ev=${snapshot.eventualMgdl.toInt()} " +
                "minPred=${snapshot.minPredMgdl.toInt()} " +
                "src=${snapshot.source} " +
                "auth=${snapshot.authorityApplied} " +
                "clamp=${snapshot.clampReconciled}" +
                (snapshot.clampReason?.let { "($it)" } ?: "") +
                " plateauLift=${snapshot.plateauFloorLifted}" +
                " curves=${snapshot.predBGsRemapped}"
    }
}

object DoseTerminalSnapshotBuilder {

    /**
     * Compose Authority apply result with thin Clamp (D4.3: no-op when eventual already clear of
     * false PKPD floor; still lifts when Authority retained a low PKPD eventual).
     *
     * Wave1 H1: also lifts dose-facing terminals on high flat BG when minPred sits on the
     * numeric floor artefact (does not disable real falling / sport / post-hypo guards).
     */
    fun build(
        authority: DecisionPredictionAuthority?,
        applyResult: PredictionAuthorityApplyResult?,
        authorityEnabled: Boolean,
        fallbackEventualMgdl: Double,
        fallbackMinPredMgdl: Double,
        clampInput: ClampPkpdScenarioReconcile.Input,
    ): DoseTerminalSnapshot {
        val authorityApplied = authorityEnabled && applyResult?.applied == true
        val baseEventual = when {
            authorityApplied -> applyResult!!.eventualMgdl
            authorityEnabled -> authority?.eventualTerminalMgdl?.takeIf { it.isFinite() }
                ?: fallbackEventualMgdl
            else -> fallbackEventualMgdl
        }
        val baseMinPred = when {
            authorityEnabled ->
                authority?.predTerminalMgdl?.takeIf { it.isFinite() }
                    ?: applyResult?.predTerminalMgdl?.takeIf { it.isFinite() }
                    ?: fallbackMinPredMgdl
            else -> fallbackMinPredMgdl
        }
        val clamp = ClampPkpdScenarioReconcile.reconcile(
            clampInput.copy(pkpdEventualMgdl = baseEventual),
        )
        var eventual = if (clamp.reconciled) max(baseEventual, clamp.eventualMgdl) else baseEventual
        // When eventual is released from a false PKPD floor, minPred must not stay at NUMERIC_FLOOR
        // or stacking/Tube still crush on path-min poison.
        val pkpdBaseline = authority?.pkpdEventualMgdl?.takeIf { it.isFinite() } ?: fallbackEventualMgdl
        val eventualLifted = eventual > pkpdBaseline + 0.5
        val safePathMin = clampInput.scenarioPathMinMgdl?.takeIf {
            it.isFinite() &&
                !clampInput.scenarioPathMinHitFloor &&
                it >= ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL
        }
        var minPred = when {
            (clamp.reconciled || (authorityApplied && eventualLifted)) && safePathMin != null ->
                min(max(baseMinPred, safePathMin), eventual)
            clamp.reconciled ->
                min(max(baseMinPred, ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL), eventual)
            else -> baseMinPred
        }
        var source = when {
            clamp.reconciled && authorityApplied -> "${applyResult!!.source}+CLAMP_${clamp.reason}"
            clamp.reconciled -> "CLAMP_${clamp.reason}"
            authorityApplied -> applyResult!!.source
            authorityEnabled -> authority?.source?.name ?: "PKPD_FALLBACK"
            else -> "PKPD_RAW"
        }
        var plateauFloorLifted = false
        if (shouldLiftPlateauFloorArtefact(
                clampInput = clampInput,
                baseMinPred = baseMinPred,
                minPredAfterClamp = minPred,
                clampReconciled = clamp.reconciled,
            )
        ) {
            val liftFloor = safePathMin
                ?: ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL
            val releaseCap = max(liftFloor, clampInput.bgMgdl - 5.0)
            val liftedEventual = max(eventual, min(releaseCap, max(liftFloor, clampInput.bgMgdl - 10.0)))
            eventual = liftedEventual
            minPred = min(max(baseMinPred, liftFloor), eventual)
            plateauFloorLifted = true
            source = if (clamp.reconciled || authorityApplied) {
                "$source+PLATEAU_FLOOR_LIFT"
            } else {
                "PLATEAU_FLOOR_LIFT"
            }
        }
        return DoseTerminalSnapshot(
            eventualMgdl = eventual,
            minPredMgdl = minPred,
            source = source,
            authorityApplied = authorityApplied,
            clampReconciled = clamp.reconciled,
            clampReason = clamp.reason,
            predBGsRemapped = applyResult?.predBGsRemapped == true,
            plateauFloorLifted = plateauFloorLifted,
        )
    }

    /**
     * High flat BG with dose minPred still on the absorbing numeric floor — classic under-correction
     * plateau. Require not falling hard / sport / post-hypo.
     */
    internal fun shouldLiftPlateauFloorArtefact(
        clampInput: ClampPkpdScenarioReconcile.Input,
        baseMinPred: Double,
        minPredAfterClamp: Double,
        clampReconciled: Boolean,
    ): Boolean {
        if (clampInput.sportTime || clampInput.postHypoDeliveryActive) return false
        if (!clampInput.bgMgdl.isFinite() || !clampInput.deltaMgdl5m.isFinite()) return false
        if (clampInput.bgMgdl < DoseTerminalSnapshot.PLATEAU_BG_MGDL) return false
        if (abs(clampInput.deltaMgdl5m) > DoseTerminalSnapshot.PLATEAU_FLAT_DELTA_ABS_MGDL) return false
        if (clampInput.deltaMgdl5m <= ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL) return false
        val floorPoisoned =
            baseMinPred <= DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL ||
                minPredAfterClamp <= DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL
        if (!floorPoisoned) return false
        // If clamp already lifted minPred well above the floor, no extra plateau arm needed.
        if (clampReconciled && minPredAfterClamp > DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL + 10.0) {
            return false
        }
        return true
    }
}
