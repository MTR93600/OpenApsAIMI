package app.aaps.plugins.aps.openAPSAIMI.prediction

import kotlin.math.max
import kotlin.math.min

/**
 * Evidence-gated lift of a false-low PKPD eventual before SMB SafetyNet / IOB stacking.
 *
 * Cascade D4.3: thin filet inside `app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshotBuilder`
 * — no-op when the candidate eventual already clears zone-1 (Authority uplift); still lifts when
 * Authority retained a false PKPD floor. Consumers must read the dose snapshot, not call this twice.
 *
 * Field evidence (support packages): ~28% of ticks carry eventual ≤45 while realized BG+30m never
 * reaches hypo; during [DIGESTION_ACTIVE] basal collapses (≈0.45 vs ≈4 U/h) when PKPD floors while
 * scenarioBest stays high.
 *
 * Safety invariants (must all hold to release):
 * - scenario path stays ≥ [SCN_PATHMIN_MGDL] and did not hit the numeric floor
 * - scenario terminal leads candidate eventual by ≥ [MIN_DIVERGENCE_MGDL]
 * - BG not falling hard (delta > [MAX_NEG_DELTA_MGDL])
 * - not sport / post-hypo delivery lock
 *
 * Release arms:
 * 1. Classic zone-2: BG in [zone1, zone2) and candidate eventual < zone1
 * 2. Digestion / meal-active + BG ≥ zone1 and candidate eventual < zone1 (covers zone-2 **and** zone-3
 *    stacking crush where SafetyNet already allows high SMB but `ev ≪ bg` still dampens)
 */
object ClampPkpdScenarioReconcile {

    const val SCN_PATHMIN_MGDL = 80.0
    const val MIN_DIVERGENCE_MGDL = 25.0
    const val MAX_NEG_DELTA_MGDL = -3.0

    /** Mirror [app.aaps.plugins.aps.openAPSAIMI.safety.SafetyNet] zone floors/offsets. */
    const val ZONE1_FLOOR_MGDL = 115.0
    const val ZONE1_OFFSET_MGDL = 20.0
    const val ZONE2_FLOOR_MGDL = 160.0
    const val ZONE2_OFFSET_MGDL = 70.0

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val deltaMgdl5m: Double,
        val pkpdEventualMgdl: Double,
        val scenarioTerminalMgdl: Double?,
        val scenarioPathMinMgdl: Double?,
        val scenarioPathMinHitFloor: Boolean,
        val digestionOrMealActive: Boolean,
        val sportTime: Boolean,
        val postHypoDeliveryActive: Boolean,
    )

    data class Result(
        val eventualMgdl: Double,
        val reconciled: Boolean,
        val reason: String?,
    )

    fun reconcile(input: Input): Result {
        val pkpd = input.pkpdEventualMgdl
        if (!pkpd.isFinite() || !input.bgMgdl.isFinite() || !input.targetBgMgdl.isFinite()) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        val scnTerminal = input.scenarioTerminalMgdl
        val scnPathMin = input.scenarioPathMinMgdl
        if (scnTerminal == null || scnPathMin == null || !scnTerminal.isFinite() || !scnPathMin.isFinite()) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        if (input.sportTime || input.postHypoDeliveryActive) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        if (!input.deltaMgdl5m.isFinite() || input.deltaMgdl5m <= MAX_NEG_DELTA_MGDL) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        val scenarioSafe = scnPathMin >= SCN_PATHMIN_MGDL && !input.scenarioPathMinHitFloor
        if (!scenarioSafe) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        if (scnTerminal - pkpd < MIN_DIVERGENCE_MGDL) {
            return Result(pkpd, reconciled = false, reason = null)
        }

        val zone1Upper = max(ZONE1_FLOOR_MGDL, input.targetBgMgdl + ZONE1_OFFSET_MGDL)
        val zone2Upper = max(ZONE2_FLOOR_MGDL, input.targetBgMgdl + ZONE2_OFFSET_MGDL)
        val pkpdBelowZone1 = pkpd < zone1Upper
        val classicZone2Clamp =
            input.bgMgdl >= zone1Upper && input.bgMgdl < zone2Upper && pkpdBelowZone1
        val digestionHighBg =
            input.digestionOrMealActive && input.bgMgdl >= zone1Upper && pkpdBelowZone1
        if (!classicZone2Clamp && !digestionHighBg) {
            return Result(pkpd, reconciled = false, reason = null)
        }

        // Cap: zone-2 ramp ceiling; in zone-3 also keep eventual near current BG so stacking
        // (`ev < bg - 6`) cannot crush solely on a false floor while scenario is high.
        val releaseCap = max(zone2Upper - 1.0, input.bgMgdl - 5.0)
        val reconciled = min(scnTerminal, releaseCap)
        if (reconciled <= pkpd + 0.5) {
            return Result(pkpd, reconciled = false, reason = null)
        }
        val reason = if (digestionHighBg && !classicZone2Clamp) {
            "DIGESTION_HIGH_BG"
        } else if (digestionHighBg) {
            "ZONE2+DIGESTION"
        } else {
            "ZONE2"
        }
        return Result(eventualMgdl = reconciled, reconciled = true, reason = reason)
    }
}
