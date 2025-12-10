package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.aps.R
import kotlin.math.min
import kotlin.math.max

/**
 * =============================================================================
 * PKPD ADVISOR ENGINE
 * =============================================================================
 * 
 * Deterministic analysis of PKPD settings based on 7-day metrics.
 * Produces suggestions for tuning DIA, Peak time, Damping, etc.
 * =============================================================================
 */
class PkpdAdvisor {

    fun analysePkpd(
        metrics: AdvisorMetrics,
        pkpd: PkpdPrefsSnapshot,
        rh: ResourceHelper
    ): List<PkpdTuningSuggestion> {
        val suggestions = mutableListOf<PkpdTuningSuggestion>()

        // 1. Check if Enabled
        if (!pkpd.pkpdEnabled) {
            // If control is poor, suggest enabling
            // TIR < 80%?
            if (metrics.tir70_180 < 0.80) {
                suggestions += PkpdTuningSuggestion(
                    technicalKey = BooleanKey.OApsAIMIPkpdEnabled.key,
                    fromValue = 0.0, // False
                    toValue = 1.0,   // True
                    explanation = rh.gs(R.string.aimi_pkpd_advisor_disabled)
                )
            }
            // If disabled, we probably shouldn't suggest tuning other params yet?
            return suggestions
        }

        // 2. HYPERS Analysis (>180 dominant, low hypos)
        // Criteria: > 25% Time > 180 AND < 2% Time < 70
        if (metrics.timeAbove180 > 0.25 && metrics.timeBelow70 < 0.02) {
            
            // A) DIA too long? Suggest shortening slightly if above min
            if (pkpd.initialDiaH > pkpd.boundsDiaMinH + 0.5) {
                val newDia = max(pkpd.boundsDiaMinH, pkpd.initialDiaH - 0.5)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialDiaH.key,
                    fromValue = pkpd.initialDiaH,
                    toValue = newDia,
                    explanation = rh.gs(R.string.aimi_pkpd_hyper_dia, pkpd.initialDiaH.toString(), newDia.toString())
                )
            }

            // B) Peak too late? Suggest earlier peak
            if (pkpd.initialPeakMin > pkpd.boundsPeakMinMin + 5) {
                val newPeak = max(pkpd.boundsPeakMinMin, pkpd.initialPeakMin - 5.0)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialPeakMin.key,
                    fromValue = pkpd.initialPeakMin,
                    toValue = newPeak,
                    explanation = rh.gs(R.string.aimi_pkpd_hyper_peak, newPeak.toString())
                )
            }

            // C) ISF Fusion too restrictive?
            if (pkpd.isfFusionMaxFactor < 1.4) {
                 val newFactor = min(2.0, pkpd.isfFusionMaxFactor + 0.1)
                 suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIIsfFusionMaxFactor.key,
                    fromValue = pkpd.isfFusionMaxFactor,
                    toValue = newFactor,
                    explanation = rh.gs(R.string.aimi_pkpd_hyper_isf)
                 )
            }
        }

        // 3. HYPOS Analysis (> 4% Time < 70)
        if (metrics.timeBelow70 > 0.04) {
            
            // A) DIA too short? Suggest increasing
            if (pkpd.initialDiaH < pkpd.boundsDiaMaxH - 0.5) {
                val newDia = min(pkpd.boundsDiaMaxH, pkpd.initialDiaH + 0.5)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialDiaH.key,
                    fromValue = pkpd.initialDiaH,
                    toValue = newDia,
                    explanation = rh.gs(R.string.aimi_pkpd_hypo_dia, pkpd.initialDiaH.toString(), newDia.toString())
                )
            }

            // B) Peak too early?
             if (pkpd.initialPeakMin < pkpd.boundsPeakMinMax - 5) {
                val newPeak = min(pkpd.boundsPeakMinMax, pkpd.initialPeakMin + 5.0)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialPeakMin.key,
                    fromValue = pkpd.initialPeakMin,
                    toValue = newPeak,
                    explanation = rh.gs(R.string.aimi_pkpd_hypo_peak, newPeak.toString())
                )
            }
            
            // C) SMB Damping too weak?
            if (pkpd.smbTailDamping < 0.8) { 
                val newDamping = pkpd.smbTailDamping + 0.1
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMISmbTailDamping.key,
                    fromValue = pkpd.smbTailDamping,
                    toValue = newDamping,
                    explanation = rh.gs(R.string.aimi_pkpd_hypo_damping)
                )
            }
        }

        return suggestions
    }
}
