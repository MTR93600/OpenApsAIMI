package app.aaps.plugins.aps.openAPSAIMI.control

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
/**
 * **Straight-line tube advisor (MPC-lite)** — short-horizon, discrete-candidate control.
 *
 * Tunables: [DoubleKey.AimiTubeHypoFloorMgdl], [DoubleKey.AimiTubeHyperBandMgdl],
 * [DoubleKey.AimiTubeAggressiveness], [DoubleKey.AimiTubeBasalTrimMax], [DoubleKey.AimiTubeKappaSafetyMargin].
 *
 * **Basal coupling**: when SMB cap scale `s` is below 1, profile basal may be scaled by
 * `1 − trimMax·(1−s)` (floor 0.88). On hypo veto (`!feasible`), uses full `trimMax` against basal.
 */
@SingleIn(AppScope::class)
class StraightLineTubeAdvisor @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    private var lastScale: Double = 1.0

    data class Input(
        val bgMgdl: Double,
        val deltaMgdlPer5m: Double,
        val iobU: Double,
        val cobG: Double,
        val isfMgdlPerU: Double,
        val diaHours: Double,
        val targetMgdl: Double,
        val maxSmbU: Double,
        val minPredictedBg: Double?,
        val eventualBgMgdl: Double,
    )

    /**
     * Why the cap came out the way it did.
     *
     * Reading the cap alone cannot tell these apart: [VETO_HYPO_FLOOR], [ZERO_ONLY_FEASIBLE] and
     * [ZERO_BY_COST] all end at scale `0.0`, and the caller then floors the SMB ceiling at 0.05 U in
     * all three cases. They need different fixes, so they are named and exported.
     */
    enum class Branch {
        /** `minPredictedBg` was null or not finite — advisor did nothing. */
        SKIP_NO_MIN_PRED,

        /** The SMB ceiling handed in was already zero — advisor did nothing. */
        SKIP_NO_MAX_SMB,

        /** `minPredictedBg` is already below the hypo floor, so not even a zero dose clears it. */
        VETO_HYPO_FLOOR,

        /** Above the floor, but the smallest non-zero candidate would cross it. Quantisation. */
        ZERO_ONLY_FEASIBLE,

        /** Non-zero candidates were feasible; the cost function still preferred no dose. */
        ZERO_BY_COST,

        /** A non-zero scale was chosen. */
        GRADED,
    }

    data class Outcome(
        val smbCapScale: Double,
        /** Multiply `profile.current_basal` and `profile.max_daily_basal` (typically ≤ 1). */
        val basalCapScale: Double,
        val feasible: Boolean,
        val chosenCost: Double,
        val reason: String,
        /** Which path produced [smbCapScale]. See [Branch]. */
        val branch: Branch = Branch.GRADED,
        /** The `minPredictedBg` this call actually used (mg/dL); null when it skipped. */
        val minPredUsedMgdl: Double? = null,
        /** Hypo floor in force for this call (mg/dL). */
        val hypoFloorMgdl: Double = 0.0,
        /** Assumed drop of the prediction minimum per 1 U of SMB (mg/dL/U), after DIA and margin. */
        val kappaMgdlPerU: Double = 0.0,
        /** SMB ceiling the scale is applied to (U), as handed in. */
        val maxSmbU: Double = 0.0,
        /**
         * Largest scale whose projected minimum still clears the floor, measured continuously in
         * `0..1`. The candidate ladder can only pick a rung at or below this, so a value under the
         * smallest non-zero rung (0.1) means no dose was reachable however much was wanted.
         */
        val sMaxFeasible: Double = 0.0,
        /** How far the eventual sits above `target + hyperBand` (mg/dL); 0 when not hyper. */
        val hyperExcessMgdl: Double = 0.0,
        /**
         * The sensitivity this call was handed (mg/dL/U) — the dose-facing `variable_sens`, not the
         * cached command value the export elsewhere calls `command_isf_mgdl`.
         *
         * [kappaMgdlPerU] is a function of it, but not an invertible one: the first factor saturates
         * at 45, so every sensitivity at or below about 29.7 mg/dL/U produces the same kappa. That is
         * exactly the band a rising meal sits in, so kappa alone cannot report what the tube believed.
         */
        val isfUsedMgdlPerU: Double = 0.0,
    )

    companion object {
        private const val TAG = "StraightLineTube"
        private const val BASAL_SCALE_FLOOR = 0.88

        /** Marginal drop in *min predicted BG* per 1 U SMB (mg/dL per U), ISF-scaled, clamped. */
        fun kappaMinPredDropPerUnit(isfMgdlPerU: Double): Double {
            val isf = isfMgdlPerU.coerceIn(25.0, 400.0)
            return (8.0 + 22.0 * (50.0 / isf)).coerceIn(8.0, 45.0)
        }

        private val CANDIDATES = doubleArrayOf(1.0, 0.85, 0.7, 0.55, 0.4, 0.25, 0.1, 0.0)

        private const val W_BG = 0.35
        private const val W_EV = 0.55
        private const val W_S_BASE = 6.0
        private const val W_DS_BASE = 4.0
        private const val W_HYPER_BASE = 0.85
        private const val W_IOB_STACK = 0.45
    }

    private fun basalCapScale(smbScale: Double, feasible: Boolean, trimMax: Double): Double {
        if (trimMax <= 1e-9) return 1.0
        val deficit = if (feasible) (1.0 - smbScale).coerceIn(0.0, 1.0) else 1.0
        return (1.0 - trimMax * deficit).coerceIn(BASAL_SCALE_FLOOR, 1.0)
    }

    fun advise(input: Input): Outcome {
        val hypoFloor = preferences.get(DoubleKey.AimiTubeHypoFloorMgdl)
        val hyperBand = preferences.get(DoubleKey.AimiTubeHyperBandMgdl)
        val agg = preferences.get(DoubleKey.AimiTubeAggressiveness).coerceIn(0.5, 2.0)
        val basalTrimMax = preferences.get(DoubleKey.AimiTubeBasalTrimMax).coerceIn(0.0, 0.25)
        val kappaMargin = preferences.get(DoubleKey.AimiTubeKappaSafetyMargin).coerceIn(0.0, 0.35)

        val wS = W_S_BASE / agg
        val wDs = W_DS_BASE / agg
        val wHyper = W_HYPER_BASE * agg

        val minPred = input.minPredictedBg
        if (minPred == null || !minPred.isFinite()) {
            return Outcome(1.0, 1.0, true, 0.0, "SKIP(no_minPred)", branch = Branch.SKIP_NO_MIN_PRED)
        }
        val dia = input.diaHours.coerceIn(3.0, 9.0)
        val kappa = kappaMinPredDropPerUnit(input.isfMgdlPerU) *
            (6.0 / dia).coerceIn(0.82, 1.18) *
            (1.0 + kappaMargin)
        val smbMax = input.maxSmbU.coerceAtLeast(0.0)
        if (smbMax <= 1e-6) {
            return Outcome(
                1.0, 1.0, true, 0.0, "SKIP(no_maxSmb)",
                branch = Branch.SKIP_NO_MAX_SMB,
                minPredUsedMgdl = minPred,
                hypoFloorMgdl = hypoFloor,
                kappaMgdlPerU = kappa,
                isfUsedMgdlPerU = input.isfMgdlPerU,
            )
        }

        val T = input.targetMgdl
        val bgErr = input.bgMgdl - T
        val evErr = input.eventualBgMgdl - T
        val hyperExcess = (input.eventualBgMgdl - (T + hyperBand)).coerceAtLeast(0.0)
        // Continuous version of the feasibility test below, kept for the export only: the largest
        // scale whose projected minimum still clears the floor. The ladder can only reach a rung at
        // or under it, so `sMaxFeasible < 0.1` means the ladder had no non-zero rung to offer.
        val sMaxFeasible = ((minPred - hypoFloor) / (smbMax * kappa)).coerceIn(0.0, 1.0)

        var bestS = 0.0
        var bestCost = Double.POSITIVE_INFINITY
        var anyFeasible = false

        var anyPositiveFeasible = false

        for (s in CANDIDATES) {
            val minAfter = minPred - s * smbMax * kappa
            if (minAfter < hypoFloor) continue
            anyFeasible = true
            if (s > 0.0) anyPositiveFeasible = true

            val iobStack = (input.iobU - 1.5).coerceAtLeast(0.0)
            val j = W_BG * bgErr * bgErr +
                W_EV * evErr * evErr +
                wS * s * s +
                wDs * (s - lastScale) * (s - lastScale) +
                wHyper * hyperExcess * hyperExcess * (1.0 - s) +
                W_IOB_STACK * iobStack * s

            if (j < bestCost) {
                bestCost = j
                bestS = s
            }
        }

        if (!anyFeasible) {
            lastScale = 0.0
            val bScale = basalCapScale(0.0, feasible = false, trimMax = basalTrimMax)
            val msg =
                "VETO hypoTube minPred=${"%.0f".format(minPred)} floor=${"%.0f".format(hypoFloor)} κ=${"%.1f".format(kappa)} maxSmb=${"%.2f".format(smbMax)} basal×${"%.2f".format(bScale)}"
            aapsLogger.warn(LTag.APS, "[$TAG] $msg")
            return Outcome(
                smbCapScale = 0.0,
                basalCapScale = bScale,
                feasible = false,
                chosenCost = Double.POSITIVE_INFINITY,
                reason = msg,
                branch = Branch.VETO_HYPO_FLOOR,
                minPredUsedMgdl = minPred,
                hypoFloorMgdl = hypoFloor,
                kappaMgdlPerU = kappa,
                maxSmbU = smbMax,
                sMaxFeasible = sMaxFeasible,
                hyperExcessMgdl = hyperExcess,
                isfUsedMgdlPerU = input.isfMgdlPerU,
            )
        }

        lastScale = bestS
        val minAfterBest = minPred - bestS * smbMax * kappa
        val bScale = basalCapScale(bestS, feasible = true, trimMax = basalTrimMax)
        val reason =
            "s=${"%.2f".format(bestS)} basal×${"%.2f".format(bScale)} J=${"%.1f".format(bestCost)} " +
                "minPred=${"%.0f".format(minPred)}→${"%.0f".format(minAfterBest)} ev=${"%.0f".format(input.eventualBgMgdl)} " +
                "κ=${"%.1f".format(kappa)} COB=${"%.1f".format(input.cobG)} IOB=${"%.2f".format(input.iobU)}"
        aapsLogger.info(LTag.APS, "[$TAG] $reason")
        return Outcome(
            smbCapScale = bestS,
            basalCapScale = bScale,
            feasible = true,
            chosenCost = bestCost,
            reason = reason,
            branch = when {
                bestS > 0.0 -> Branch.GRADED
                anyPositiveFeasible -> Branch.ZERO_BY_COST
                else -> Branch.ZERO_ONLY_FEASIBLE
            },
            minPredUsedMgdl = minPred,
            hypoFloorMgdl = hypoFloor,
            kappaMgdlPerU = kappa,
            maxSmbU = smbMax,
            sMaxFeasible = sMaxFeasible,
            hyperExcessMgdl = hyperExcess,
            isfUsedMgdlPerU = input.isfMgdlPerU,
        )
    }
}
