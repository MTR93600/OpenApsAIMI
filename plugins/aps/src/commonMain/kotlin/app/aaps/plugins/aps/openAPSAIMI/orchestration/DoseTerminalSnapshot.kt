package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cascade D4 / C1 — single dose-facing eventual + minPred for the tick.
 *
 * Built once after Prediction Authority apply (+ thin Clamp). `SafetyNet`, stacking, Tube
 * refresh and SMB gates must read this instead of raw PKPD / curve-min floors.
 *
 * `DoseTerminalSnapshotBuilder` stays dump until `DecisionPredictionAuthority` is T1-clean.
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
