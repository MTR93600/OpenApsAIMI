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
    ): List<AimiRecommendation> {
        val suggestions = mutableListOf<AimiRecommendation>()

        // 1. Check if Enabled
        if (!pkpd.pkpdEnabled) {
            // If control is poor, suggest enabling
            // TIR < 80%?
            if (metrics.tir70_180 < 0.80) {
                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = BooleanKey.OApsAIMIPkpdEnabled,
                            keyName = rh.gs(R.string.aimi_pkpd_title_enable),
                            oldValue = false, // False
                            newValue = true, // True
                            explanation = rh.gs(R.string.aimi_pkpd_advisor_disabled)
                        )
                    )
                )

                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_enable,
                    descriptionResId = R.string.aimi_pkpd_advisor_disabled,
                    priority = RecommendationPriority.HIGH, // High priority if disabled and poorly controlled
                    domain = RecommendationDomain.PKPD,
                    action = action
                )
            }
            return suggestions
        }

        // 2. HYPERS Analysis (>180 dominant, low hypos)
        // Criteria: > 25% Time > 180 AND < 2% Time < 70
        if (metrics.timeAbove180 > 0.25 && metrics.timeBelow70 < 0.02) {
            
            // A) DIA too long? Suggest shortening slightly if above min
            if (pkpd.initialDiaH > pkpd.boundsDiaMinH + 0.5) {
                val newDia = max(pkpd.boundsDiaMinH, pkpd.initialDiaH - 0.5)
                val explanation = rh.gs(R.string.aimi_pkpd_hyper_dia, pkpd.initialDiaH.toString(), newDia.toString())
                
                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIPkpdInitialDiaH,
                            keyName = rh.gs(R.string.aimi_pkpd_title_dia),
                            oldValue = pkpd.initialDiaH,
                            newValue = newDia,
                            explanation = explanation
                        )
                    )
                )
                
                suggestions += AimiRecommendation(
                     titleResId = R.string.aimi_pkpd_title_dia,
                     descriptionResId = R.string.aimi_pkpd_hyper_dia,
                     priority = RecommendationPriority.MEDIUM,
                     domain = RecommendationDomain.PKPD,
                     action = action,
                     extraData = "$newDia h"
                )
            }

            // B) Peak too late? Suggest earlier peak
            if (pkpd.initialPeakMin > pkpd.boundsPeakMinMin + 5) {
                val newPeak = max(pkpd.boundsPeakMinMin, pkpd.initialPeakMin - 5.0)
                val explanation = rh.gs(R.string.aimi_pkpd_hyper_peak, newPeak.toString())

                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIPkpdInitialPeakMin,
                            keyName = rh.gs(R.string.aimi_pkpd_title_peak),
                            oldValue = pkpd.initialPeakMin,
                            newValue = newPeak,
                            explanation = explanation
                        )
                    )
                )

                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_peak,
                    descriptionResId = R.string.aimi_pkpd_hyper_peak,
                    priority = RecommendationPriority.MEDIUM,
                    domain = RecommendationDomain.PKPD,
                    action = action,
                    extraData = "$newPeak min"
                )
            }

            // C) ISF Fusion too restrictive?
            if (pkpd.isfFusionMaxFactor < 1.4) {
                 val newFactor = min(2.0, pkpd.isfFusionMaxFactor + 0.1)
                 val explanation = rh.gs(R.string.aimi_pkpd_hyper_isf)

                 val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIIsfFusionMaxFactor,
                            keyName = rh.gs(R.string.aimi_pkpd_title_isf),
                            oldValue = pkpd.isfFusionMaxFactor,
                            newValue = newFactor,
                            explanation = explanation
                        )
                    )
                 )
                 
                 suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_isf,
                    descriptionResId = R.string.aimi_pkpd_hyper_isf,
                    priority = RecommendationPriority.MEDIUM,
                    domain = RecommendationDomain.PKPD,
                    action = action,
                    extraData = "x$newFactor"
                 )
            }
        }

        // 3. HYPOS Analysis (> 4% Time < 70)
        if (metrics.timeBelow70 > 0.04) {
            
            // A) DIA too short? Suggest increasing
            if (pkpd.initialDiaH < pkpd.boundsDiaMaxH - 0.5) {
                val newDia = min(pkpd.boundsDiaMaxH, pkpd.initialDiaH + 0.5)
                val explanation = rh.gs(R.string.aimi_pkpd_hypo_dia, pkpd.initialDiaH.toString(), newDia.toString())
                
                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIPkpdInitialDiaH,
                            keyName = rh.gs(R.string.aimi_pkpd_title_dia),
                            oldValue = pkpd.initialDiaH,
                            newValue = newDia,
                            explanation = explanation
                        )
                    )
                )

                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_dia,
                    descriptionResId = R.string.aimi_pkpd_hypo_dia,
                    priority = RecommendationPriority.CRITICAL, // Safety
                    domain = RecommendationDomain.PKPD,
                    action = action,
                    extraData = "$newDia h"
                )
            }

            // B) Peak too early?
             if (pkpd.initialPeakMin < pkpd.boundsPeakMinMax - 5) {
                val newPeak = min(pkpd.boundsPeakMinMax, pkpd.initialPeakMin + 5.0)
                val explanation = rh.gs(R.string.aimi_pkpd_hypo_peak, newPeak.toString())

                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIPkpdInitialPeakMin,
                            keyName = rh.gs(R.string.aimi_pkpd_title_peak),
                            oldValue = pkpd.initialPeakMin,
                            newValue = newPeak,
                            explanation = explanation
                        )
                    )
                )

                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_peak,
                    descriptionResId = R.string.aimi_pkpd_hypo_peak,
                    priority = RecommendationPriority.CRITICAL,
                    domain = RecommendationDomain.PKPD,
                    action = action,
                    extraData = "$newPeak min"
                )
            }
            
            // C) SMB Damping too weak?
            if (pkpd.smbTailDamping < 0.8) { 
                val newDamping = pkpd.smbTailDamping + 0.1
                val explanation = rh.gs(R.string.aimi_pkpd_hypo_damping)
                
                val action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMISmbTailDamping,
                            keyName = rh.gs(R.string.aimi_pkpd_title_damping),
                            oldValue = pkpd.smbTailDamping,
                            newValue = newDamping,
                            explanation = explanation
                        )
                    )
                )

                suggestions += AimiRecommendation(
                    titleResId = R.string.aimi_pkpd_title_damping,
                    descriptionResId = R.string.aimi_pkpd_hypo_damping,
                    priority = RecommendationPriority.CRITICAL,
                    domain = RecommendationDomain.PKPD,
                    action = action,
                    extraData = "x$newDamping"
                )
            }
        }

        return suggestions
    }
}
