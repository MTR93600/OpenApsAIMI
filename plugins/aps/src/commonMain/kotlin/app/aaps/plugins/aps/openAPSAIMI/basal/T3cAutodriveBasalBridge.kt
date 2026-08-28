package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.max
import kotlin.math.min

/**
 * T3C Basal Authority Fusion — pure decision helpers (no I/O).
 *
 * Contract:
 * - SMB is never applied; any Autodrive SMB is stripped (optionally converted to a bounded TBR boost).
 * - Autodrive may only **raise** basal demand vs the T3C PI (`max`).
 * - The physiological tree unlocks the **ceiling + ramp**, not a cosmetic aggressiveness bump alone.
 */
object T3cAutodriveBasalBridge {

    /** Spread stripped SMB over this horizon (hours) when converting to TBR-equivalent boost. */
    private const val SMB_TO_TBR_HORIZON_H = 0.25

    /** Hard cap on SMB→TBR conversion so a large model SMB cannot explode the basal. */
    private const val SMB_TO_TBR_BOOST_CAP_UPH = 3.0

    data class UnlockDecision(
        val unlock: Boolean,
        val reason: String,
    )

    data class FusionResult(
        val fusedTargetUph: Double,
        val maxBasalCapUph: Double,
        val maxStepUpUph: Double,
        val piUph: Double,
        val adEffectiveUph: Double?,
        val strippedSmbU: Double,
        val unlock: Boolean,
        val unlockReason: String,
    ) {
        fun toLogLine(): String =
            "T3C_AD_BASAL: pi=${aimiFmt2(piUph)} " +
                "ad=${adEffectiveUph?.let { aimiFmt2(it) } ?: "—"} " +
                "fused=${aimiFmt2(fusedTargetUph)} " +
                "unlock=$unlock ($unlockReason) " +
                "cap=${aimiFmt2(maxBasalCapUph)} " +
                "step=${aimiFmt2(maxStepUpUph)} " +
                "smbStripped=${aimiFmt2(strippedSmbU)}"
    }

    /**
     * Unlocks high-basal authority (rise ceiling + faster ramp) on **glycemic reality first**.
     *
     * T3C brittle mode is the reliable floor for a patient whose safety depends on it, so its basal
     * authority must NOT depend on the physiological tree — which is frequently inert in production
     * (`rbt_authority_off` → blank branches that never classify resistance/meal/hyper even during a
     * sustained hyper). The **primary** unlock is therefore a confirmed rising hyper on glycemic
     * evidence alone (projected BG + rising + not projected below target). The tree contributes only the
     * hypo/critical **safety** gates (which fire only when it is actually alive). Post-hypo (tree-
     * independent) and computeT3c's own hypo brakes remain in force, so decoupling does not remove any
     * hypo protection — it removes an artificial *upward* throttle.
     */
    fun evaluateTreeUnlock(
        tree: PhysiologicalTreeSnapshot?,
        bg: Double,
        delta: Float,
        activationThreshold: Double,
        postHypoActive: Boolean,
        eventualBg: Double?,
        targetBg: Double,
        projectedBg: Double? = null,
    ): UnlockDecision {
        if (postHypoActive) return UnlockDecision(false, "post_hypo")
        // Anticipatory + tree-independent: engage on where BG is heading (projected), not current BG.
        // Fail-safe: null/invalid projection falls back to current BG.
        val proj = projectedBg?.takeIf { it.isFinite() && it > 0.0 } ?: bg
        val glycemicRise = proj > activationThreshold &&
            delta > 0f &&
            (eventualBg == null || eventualBg <= 0.0 || eventualBg > targetBg)
        // No tree → unlock on the glycemic rise alone (fail-open, self-sufficient).
        if (tree == null) {
            return UnlockDecision(glycemicRise, if (glycemicRise) "rise_no_tree" else "no_tree")
        }
        // Tree-based SAFETY gates only (effective when the tree is actually alive).
        if (tree.trunk.riskLevel == PhysiologicalRiskLevel.CRITICAL) return UnlockDecision(false, "tree_critical")
        if (tree.trunk.globalState == GlobalPhysiologicalState.HYPO_RISK) {
            return UnlockDecision(false, "tree_hypo_risk")
        }
        // PRIMARY unlock: glycemic evidence, independent of the (often inert) tree classification and of
        // a frequently false-positive activity veto. computeT3c's PI + hypo brakes still bound the rate.
        return UnlockDecision(glycemicRise, if (glycemicRise) "glycemic_override" else "no_confirmed_rise")
    }

    /**
     * Strip Autodrive SMB and optionally convert it into a bounded TBR boost (basal-only contract).
     */
    fun effectiveAutodriveTbrUph(
        rawTbrUph: Double,
        strippedSmbU: Double,
        profileBasalUph: Double,
    ): Double {
        val base = rawTbrUph.coerceAtLeast(0.0)
        val boost = if (strippedSmbU > 0.0) {
            (strippedSmbU / SMB_TO_TBR_HORIZON_H).coerceIn(0.0, SMB_TO_TBR_BOOST_CAP_UPH)
        } else {
            0.0
        }
        // Prefer the stronger of (raw TBR) vs (profile + SMB-as-TBR); never below profile when AD asked for SMB.
        return if (boost > 0.0) {
            max(base, profileBasalUph + boost)
        } else {
            base
        }
    }

    fun fuse(
        piUph: Double,
        adTbrUph: Double?,
        strippedSmbU: Double,
        profileBasalUph: Double,
        steadyCapUph: Double,
        riseCapUph: Double,
        previousRateUph: Double,
        unlock: UnlockDecision,
    ): FusionResult {
        val adEffective = adTbrUph?.let {
            effectiveAutodriveTbrUph(it, strippedSmbU, profileBasalUph)
        }
        val demand = when {
            adEffective == null -> piUph
            else -> max(piUph, adEffective)
        }
        val cap = if (unlock.unlock) riseCapUph else steadyCapUph
        val fusedTarget = demand.coerceIn(0.0, cap.coerceAtLeast(profileBasalUph))
        val maxStepUp = if (unlock.unlock) {
            max(1.0, previousRateUph * 0.50)
        } else {
            max(0.30, previousRateUph * 0.20)
        }
        return FusionResult(
            fusedTargetUph = fusedTarget,
            maxBasalCapUph = cap,
            maxStepUpUph = maxStepUp,
            piUph = piUph,
            adEffectiveUph = adEffective,
            strippedSmbU = strippedSmbU.coerceAtLeast(0.0),
            unlock = unlock.unlock,
            unlockReason = unlock.reason,
        )
    }

    fun applyRamp(previousRateUph: Double, targetUph: Double, maxStepUpUph: Double): Double =
        if (targetUph > previousRateUph) {
            min(targetUph, previousRateUph + maxStepUpUph)
        } else {
            targetUph
        }
}
