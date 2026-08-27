package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefAnalysisReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefDataSufficiency
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefGlycemicPriority
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefPersonalSignalGate
import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.min
import kotlin.math.max

/**
 * =============================================================================
 * PKPD ADVISOR ENGINE
 * =============================================================================
 *
 * Deterministic analysis of PKPD settings based on 7-day metrics.
 * Produces suggestions for tuning DIA, Peak time, Damping, etc.
 *
 * Fix #1a: Raised trigger thresholds (hyper 25%→40%, hypo 4%→7%) to avoid
 *           systematic/empirical recommendations on well-managed users.
 * Fix #1c: Added Trend Guard — suppresses all recs if today's TIR >= 7-day avg.
 * =============================================================================
 */
class PkpdAdvisor {

    fun analysePkpd(
        metrics: AdvisorMetrics,
        pkpd: PkpdPrefsSnapshot,
        rh: ResourceHelper,
        oref: OrefAnalysisReport? = null,
    ): List<AimiRecommendation> {
        val suggestions = mutableListOf<AimiRecommendation>()

        // 1. Check if Enabled
        if (!pkpd.pkpdEnabled) {
            // Only suggest enabling if control is significantly poor
            if (metrics.tir70_180 < 0.70) { // Raised threshold: was 80%
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.BooleanKey.OApsAIMIPkpdEnabled,
                    newValue = true,
                    reason = rh.gs(R.string.aimi_pkpd_advisor_disabled),
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.High
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_enable,
                    descriptionResId = R.string.aimi_pkpd_advisor_disabled,
                    priority = AimiPriority.High,
                    domain = AimiDomain.Pkpd,
                    action = action
                )
            }
            return suggestions
        }

        // ── Fix #1c: Trend Guard (OREF-aware) ─────────────────────────────────────
        // If today's TIR >= 7-day average, skip PKPD tuning unless on-device OREF/ML still flags risk.
        val isImproving = metrics.todayTir != null && metrics.todayTir >= metrics.tir70_180
        val orefStillRisky = orefContradictsQuietPeriod(oref)
        if (isImproving && !orefStillRisky) return suggestions
        // ─────────────────────────────────────────────────────────────────────────

        val hypoTrigger = hypoPkpdTrigger(metrics, oref)
        val hyperTrigger = hyperPkpdTrigger(metrics, oref)
        val mode = resolvePkpdMode(hypoTrigger, hyperTrigger, metrics, oref)

        // 2. HYPERS Analysis
        // Default threshold 40% >180 (conservative). When OREF marks hyper priority with exposure, allow earlier PKPD hints.
        // Never run hypo + hyper blocks together: same knob would get contradictory +0.5 / -0.5 DIA recommendations.
        if (mode == PkpdMode.HYPER_DOMINANT) {

            // A) DIA too long? Only suggest if well above the lower bound.
            if (pkpd.initialDiaH > pkpd.boundsDiaMinH + 1.0) { // was +0.5
                val newDia = max(pkpd.boundsDiaMinH, pkpd.initialDiaH - 0.5)
                val explanation = rh.gs(R.string.aimi_pkpd_hyper_dia, pkpd.initialDiaH.toString(), newDia.toString())
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.DoubleKey.OApsAIMIPkpdInitialDiaH,
                    newValue = newDia,
                    reason = explanation,
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.Medium
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_dia,
                    descriptionResId = R.string.aimi_pkpd_hyper_dia,
                    priority = AimiPriority.Medium,
                    domain = AimiDomain.Pkpd,
                    action = action,
                    descriptionArgs = listOf(pkpd.initialDiaH.toString(), newDia.toString())
                )
            }

            // B) Peak too late? Only suggest if well above the lower bound.
            if (pkpd.initialPeakMin > pkpd.boundsPeakMinMin + 10) { // was +5
                val newPeak = max(pkpd.boundsPeakMinMin, pkpd.initialPeakMin - 5.0)
                val explanation = rh.gs(R.string.aimi_pkpd_hyper_peak, newPeak.toString())
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.DoubleKey.OApsAIMIPkpdInitialPeakMin,
                    newValue = newPeak,
                    reason = explanation,
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.Medium
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_peak,
                    descriptionResId = R.string.aimi_pkpd_hyper_peak,
                    priority = AimiPriority.Medium,
                    domain = AimiDomain.Pkpd,
                    action = action,
                    descriptionArgs = listOf(newPeak.toString())
                )
            }

            // C) ISF Fusion too restrictive?
            if (pkpd.isfFusionMaxFactor < 1.4) {
                val newFactor = min(2.0, pkpd.isfFusionMaxFactor + 0.1)
                val explanation = rh.gs(R.string.aimi_pkpd_hyper_isf)
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.DoubleKey.OApsAIMIIsfFusionMaxFactor,
                    newValue = newFactor,
                    reason = explanation,
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.Medium
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_isf,
                    descriptionResId = R.string.aimi_pkpd_hyper_isf,
                    priority = AimiPriority.Medium,
                    domain = AimiDomain.Pkpd,
                    action = action,
                    descriptionArgs = listOf(newFactor.toString())
                )
            }

            // D) Tail damping very strong while hypers dominate and hypos are rare — allow slightly more tail delivery.
            // Stored pref is a multiplicative FLOOR (lower = stronger damping): weakening the guard means RAISING the floor.
            val effectiveDampingHyper = PkpdSmbTailDamping.effectiveStoredValue(pkpd.smbTailDamping)
            if (effectiveDampingHyper < PkpdSmbTailDamping.DAMPING_LIGHT - 0.02 && metrics.timeBelow70 < 0.035) {
                val newDamping = PkpdSmbTailDamping.clampForAdvisor(effectiveDampingHyper + 0.08)
                if (newDamping > effectiveDampingHyper + 0.01) {
                    val explanation = rh.gs(R.string.aimi_pkpd_hyper_damping_reduce, effectiveDampingHyper.toString(), newDamping.toString())
                    val action = AimiAction.PreferenceUpdate(
                        key = app.aaps.core.keys.DoubleKey.OApsAIMISmbTailDamping,
                        newValue = newDamping,
                        reason = explanation,
                        domain = AimiDomain.Pkpd,
                        priority = AimiPriority.Medium
                    )
                    suggestions += AimiRecommendation(
                        titleResId = R.string.aimi_pkpd_title_damping,
                        descriptionResId = R.string.aimi_pkpd_hyper_damping_reduce,
                        priority = AimiPriority.Medium,
                        domain = AimiDomain.Pkpd,
                        action = action,
                        descriptionArgs = listOf(effectiveDampingHyper.toString(), newDamping.toString())
                    )
                }
            }
        }

        // 3. HYPOS Analysis
        // Default threshold 7% <70. When OREF marks hypo priority and data are usable, allow ~5.5% + corroboration.
        if (mode == PkpdMode.HYPO_DOMINANT) {

            // A) DIA too short? Only suggest if well below the upper bound.
            if (pkpd.initialDiaH < pkpd.boundsDiaMaxH - 1.0) { // was -0.5
                val newDia = min(pkpd.boundsDiaMaxH, pkpd.initialDiaH + 0.5)
                val explanation = rh.gs(R.string.aimi_pkpd_hypo_dia, pkpd.initialDiaH.toString(), newDia.toString())
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.DoubleKey.OApsAIMIPkpdInitialDiaH,
                    newValue = newDia,
                    reason = explanation,
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.Critical
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_dia,
                    descriptionResId = R.string.aimi_pkpd_hypo_dia,
                    priority = AimiPriority.Critical,
                    domain = AimiDomain.Pkpd,
                    action = action,
                    descriptionArgs = listOf(pkpd.initialDiaH.toString(), newDia.toString())
                )
            }

            // B) Peak too early? Only suggest if well below the upper bound.
            if (pkpd.initialPeakMin < pkpd.boundsPeakMinMax - 10) { // was -5
                val newPeak = min(pkpd.boundsPeakMinMax, pkpd.initialPeakMin + 5.0)
                val explanation = rh.gs(R.string.aimi_pkpd_hypo_peak, newPeak.toString())
                val action = AimiAction.PreferenceUpdate(
                    key = app.aaps.core.keys.DoubleKey.OApsAIMIPkpdInitialPeakMin,
                    newValue = newPeak,
                    reason = explanation,
                    domain = AimiDomain.Pkpd,
                    priority = AimiPriority.Critical
                )
                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_peak,
                    descriptionResId = R.string.aimi_pkpd_hypo_peak,
                    priority = AimiPriority.Critical,
                    domain = AimiDomain.Pkpd,
                    action = action,
                    descriptionArgs = listOf(newPeak.toString())
                )
            }

            // C) Tail damping too weak for the hypo pattern?
            // Stored pref is a multiplicative FLOOR (lower = stronger damping): strengthening the guard means LOWERING the floor.
            val effectiveDampingHypo = PkpdSmbTailDamping.effectiveStoredValue(pkpd.smbTailDamping)
            if (effectiveDampingHypo > PkpdSmbTailDamping.DAMPING_STRONG + 0.05) {
                val newDamping = PkpdSmbTailDamping.clampForAdvisor(effectiveDampingHypo - 0.1)
                if (newDamping < effectiveDampingHypo - 0.01) {
                    val explanation = rh.gs(R.string.aimi_pkpd_hypo_damping)
                    val action = AimiAction.PreferenceUpdate(
                        key = app.aaps.core.keys.DoubleKey.OApsAIMISmbTailDamping,
                        newValue = newDamping,
                        reason = explanation,
                        domain = AimiDomain.Pkpd,
                        priority = AimiPriority.Critical
                    )
                    suggestions += AimiRecommendation(
                        titleResId = R.string.aimi_pkpd_title_damping,
                        descriptionResId = R.string.aimi_pkpd_hypo_damping,
                        priority = AimiPriority.Critical,
                        domain = AimiDomain.Pkpd,
                        action = action,
                        descriptionArgs = listOf(newDamping.toString())
                    )
                }
            }

            // D) ISF fusion headroom already high — reduce max fusion to cap aggressive corrections during lows
            if (pkpd.isfFusionMaxFactor > 1.35) {
                val newFactor = max(1.0, pkpd.isfFusionMaxFactor - 0.1)
                if (newFactor < pkpd.isfFusionMaxFactor - 0.01) {
                    val explanation = rh.gs(R.string.aimi_pkpd_hypo_isf_reduce, pkpd.isfFusionMaxFactor.toString(), newFactor.toString())
                    val action = AimiAction.PreferenceUpdate(
                        key = app.aaps.core.keys.DoubleKey.OApsAIMIIsfFusionMaxFactor,
                        newValue = newFactor,
                        reason = explanation,
                        domain = AimiDomain.Pkpd,
                        priority = AimiPriority.High
                    )
                    suggestions += AimiRecommendation(
                        titleResId = R.string.aimi_pkpd_title_isf,
                        descriptionResId = R.string.aimi_pkpd_hypo_isf_reduce,
                        priority = AimiPriority.High,
                        domain = AimiDomain.Pkpd,
                        action = action,
                        descriptionArgs = listOf(pkpd.isfFusionMaxFactor.toString(), newFactor.toString())
                    )
                }
            }
        }

        return suggestions
    }

    private enum class PkpdMode { HYPO_DOMINANT, HYPER_DOMINANT, NEITHER }

    /**
     * When hypo and hyper triggers both fire, pick one dominant context so we never recommend opposite steps for the same parameter.
     */
    private fun resolvePkpdMode(
        hypoTrigger: Boolean,
        hyperTrigger: Boolean,
        metrics: AdvisorMetrics,
        oref: OrefAnalysisReport?,
    ): PkpdMode {
        if (!hypoTrigger && !hyperTrigger) return PkpdMode.NEITHER
        if (hypoTrigger && !hyperTrigger) return PkpdMode.HYPO_DOMINANT
        if (hyperTrigger && !hypoTrigger) return PkpdMode.HYPER_DOMINANT
        when (oref?.priority) {
            OrefGlycemicPriority.HYPO -> return PkpdMode.HYPO_DOMINANT
            OrefGlycemicPriority.HYPER -> return PkpdMode.HYPER_DOMINANT
            OrefGlycemicPriority.BOTH -> {
                if (metrics.timeBelow70 >= 0.06) return PkpdMode.HYPO_DOMINANT
                if (metrics.timeAbove180 >= 0.30 && metrics.timeBelow70 < 0.045) return PkpdMode.HYPER_DOMINANT
                return if (metrics.timeBelow70 * 1.3 >= metrics.timeAbove180) {
                    PkpdMode.HYPO_DOMINANT
                } else {
                    PkpdMode.HYPER_DOMINANT
                }
            }
            else -> {
                if (metrics.timeBelow70 >= 0.06) return PkpdMode.HYPO_DOMINANT
                if (metrics.timeAbove180 >= 0.35 && metrics.timeBelow70 < 0.035) return PkpdMode.HYPER_DOMINANT
                return if (metrics.timeBelow70 * 1.2 >= metrics.timeAbove180) {
                    PkpdMode.HYPO_DOMINANT
                } else {
                    PkpdMode.HYPER_DOMINANT
                }
            }
        }
    }

    // The three gates below read three OREF inputs each. The measured outcome history and the calibrated ONNX
    // risk are unchanged. The third one, the personal on-device signal, is read through OrefPersonalSignalGate
    // because it is not calibrated: it reports a compressed sigmoid, never below 50 %, so on its own it would
    // open these gates for nearly every patient. The gate holds it off until it is calibrated; the thresholds
    // themselves are left as they are.

    private fun orefContradictsQuietPeriod(oref: OrefAnalysisReport?): Boolean {
        val o = oref ?: return false
        if (o.dataSufficiency == OrefDataSufficiency.INSUFFICIENT) return false
        val hypoFocus = o.priority == OrefGlycemicPriority.HYPO || o.priority == OrefGlycemicPriority.BOTH
        val hyperFocus = o.priority == OrefGlycemicPriority.HYPER || o.priority == OrefGlycemicPriority.BOTH
        val hypoSignal = hypoFocus &&
            ((o.actualHypo4hPct ?: 0.0) >= 12.0 ||
                (o.meanCalHypoRiskPct ?: 0.0) >= 20.0 ||
                OrefPersonalSignalGate.tripsDecision(o.personalMeanHypoSignalPct, 52.0))
        val hyperSignal = hyperFocus &&
            ((o.actualHyper4hPct ?: 0.0) >= 18.0 ||
                (o.meanCalHyperRiskPct ?: 0.0) >= 25.0 ||
                OrefPersonalSignalGate.tripsDecision(o.personalMeanHyperSignalPct, 52.0))
        return hypoSignal || hyperSignal
    }

    private fun hypoPkpdTrigger(metrics: AdvisorMetrics, oref: OrefAnalysisReport?): Boolean {
        if (metrics.timeBelow70 > 0.07) return true
        if (metrics.timeBelow70 <= 0.055) return false
        val o = oref ?: return false
        if (o.dataSufficiency == OrefDataSufficiency.INSUFFICIENT) return false
        val hypoFocus = o.priority == OrefGlycemicPriority.HYPO || o.priority == OrefGlycemicPriority.BOTH
        if (!hypoFocus) return false
        return (o.actualHypo4hPct ?: 0.0) >= 10.0 ||
            (o.meanCalHypoRiskPct ?: 0.0) >= 18.0 ||
            OrefPersonalSignalGate.tripsDecision(o.personalMeanHypoSignalPct, 48.0)
    }

    private fun hyperPkpdTrigger(metrics: AdvisorMetrics, oref: OrefAnalysisReport?): Boolean {
        val calmHypos = metrics.timeBelow70 < 0.06
        if (metrics.timeAbove180 > 0.40 && metrics.timeBelow70 < 0.02) return true
        if (!calmHypos || metrics.timeAbove180 <= 0.20) return false
        val o = oref ?: return false
        if (o.dataSufficiency == OrefDataSufficiency.INSUFFICIENT) return false
        val hyperFocus = o.priority == OrefGlycemicPriority.HYPER || o.priority == OrefGlycemicPriority.BOTH
        if (!hyperFocus) return false
        return (o.actualHyper4hPct ?: 0.0) >= 18.0 ||
            (o.meanCalHyperRiskPct ?: 0.0) >= 25.0 ||
            OrefPersonalSignalGate.tripsDecision(o.personalMeanHyperSignalPct, 52.0)
    }
}
