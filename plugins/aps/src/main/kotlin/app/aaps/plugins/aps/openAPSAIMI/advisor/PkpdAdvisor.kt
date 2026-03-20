package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.model.*
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
        rh: ResourceHelper
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

        // ── Fix #1c: Trend Guard ─────────────────────────────────────────────────
        // If today's TIR >= 7-day average, the situation is actively improving.
        // Do not bombard the user with PKPD change suggestions when trending upward.
        val isImproving = metrics.todayTir != null && metrics.todayTir >= metrics.tir70_180
        if (isImproving) return suggestions
        // ─────────────────────────────────────────────────────────────────────────

        // 2. HYPERS Analysis
        // Fix #1a: Raised threshold from 25% → 40%.
        // A patient at 30-35% above 180 may need basal/ISF work, not DIA tuning.
        if (metrics.timeAbove180 > 0.40 && metrics.timeBelow70 < 0.02) {

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
        }

        // 3. HYPOS Analysis
        // Fix #1a: Raised threshold from 4% → 7%.
        // <4% hypos is within ADA acceptable range; only intervene when clinically significant.
        if (metrics.timeBelow70 > 0.07) {

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

            // C) SMB Damping too weak?
            if (pkpd.smbTailDamping < 0.8) {
                val newDamping = pkpd.smbTailDamping + 0.1
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

        return suggestions
    }
}
