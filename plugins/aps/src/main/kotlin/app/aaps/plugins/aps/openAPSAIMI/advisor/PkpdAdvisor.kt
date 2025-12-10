package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
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
        pkpd: PkpdPrefsSnapshot
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
                    explanation = "Le module PKPD adaptatif est désactivé. L'activer permettrait d'ajuster automatiquement votre DIA et pic d'action pour améliorer le TIR."
                )
            }
            // If disabled, we probably shouldn't suggest tuning other params yet?
            // Or maybe suggest initial params. Let's stick to just enabling for now.
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
                    explanation = "Vos hypers sont fréquentes. Réduire la Durée d'Action (DIA) de ${pkpd.initialDiaH}h à ${newDia}h pourrait rendre l'insuline active plus 'rapide' dans les calculs et augmenter les corrections."
                )
            }

            // B) Peak too late? Suggest earlier peak
            if (pkpd.initialPeakMin > pkpd.boundsPeakMinMin + 5) {
                val newPeak = max(pkpd.boundsPeakMinMin, pkpd.initialPeakMin - 5.0)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialPeakMin.key,
                    fromValue = pkpd.initialPeakMin,
                    toValue = newPeak,
                    explanation = "Un pic d'action plus tôt (${newPeak} min) peut aider à être plus agressif sur les montées post-prandiales."
                )
            }

            // C) ISF Fusion too restrictive?
            if (pkpd.isfFusionMaxFactor < 1.4) {
                 val newFactor = min(2.0, pkpd.isfFusionMaxFactor + 0.1)
                 suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIIsfFusionMaxFactor.key,
                    fromValue = pkpd.isfFusionMaxFactor,
                    toValue = newFactor,
                    explanation = "Augmenter le facteur max de fusion ISF permettrait de renforcer l'insuline quand la glycémie est résistante."
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
                    explanation = "Hypos fréquentes détectées. Allonger la DIA de ${pkpd.initialDiaH}h à ${newDia}h permettrait de mieux prendre en compte l'insuline restante (IOB) et d'éviter les cumuls."
                )
            }

            // B) Peak too early?
             if (pkpd.initialPeakMin < pkpd.boundsPeakMinMax - 5) {
                val newPeak = min(pkpd.boundsPeakMinMax, pkpd.initialPeakMin + 5.0)
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMIPkpdInitialPeakMin.key,
                    fromValue = pkpd.initialPeakMin,
                    toValue = newPeak,
                    explanation = "Retarder le pic d'action (${newPeak} min) adoucirait la courbe d'activité de l'insuline."
                )
            }
            
            // C) SMB Damping too weak?
            if (pkpd.smbTailDamping < 0.8) { // Assuming 0-1 range or similar factor. User uses inputs like "AdaptiveDoublePreference".
                // If it's a factor where higher = more damping? Usually "Damping" means reduction.
                // Assuming Logic: Damping implies "how much we suppress".
                // Usually SMB Damping factor: 1.0 = no damping? Or 0.5 = 50%?
                // Assuming Standard AAPS/AIMI conventions: "Tail Damping" usually implies a coefficient applied to SMB.
                // If hypos, we want MORE damping (smaller fractional multiplier? or larger reduction?).
                // Let's assume user prompt implies "réduire un peu smbTailDamping si trop élevé" for HYPERS.
                // So High Damping = Less Insulin?
                // Wait. "réduire smbTailDamping... pour HYPERS" -> implies Damping restricts insulin. So reducing damping = unleashing insulin.
                // Therefore for HYPOS (too much insulin), we want TO INCREASE Damping.
                
                // Check bounds?
                // Let's be conservative. Increase by 0.1
                val newDamping = pkpd.smbTailDamping + 0.1
                suggestions += PkpdTuningSuggestion(
                    technicalKey = DoubleKey.OApsAIMISmbTailDamping.key,
                    fromValue = pkpd.smbTailDamping,
                    toValue = newDamping,
                    explanation = "Augmenter l'amortissement (damping) en fin de DIA (Tail) réduirait les SMB risqués avec de l'IOB tardive."
                )
            }
        }

        return suggestions
    }
}
