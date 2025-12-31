package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * 🛡️ PKPD Absorption Guard (FIX 2025-12-30)
 * 
 * Soft guard basé sur la physiologie de l'absorption d'insuline.
 * Empêche la sur-correction en modulant SMB et intervalle selon le stage d'activité.
 * 
 * Principe : "Injecter → Laisser agir → Réévaluer" au lieu de "sur-corriger à chaque tick"
 * 
 * @property factor Facteur multiplicatif SMB (0.4..1.0). Plus bas = plus prudent
 * @property intervalAddMin Minutes à ajouter à l'intervalle avant prochain SMB (0..6)
 * @property preferTbr Si true, privilégier TBR au lieu de SMB
 * @property reason Raison du guard (pour logs)
 * @property stage Stage d'activité insuline qui a déclenché ce guard
 */
data class PkpdAbsorptionGuard(
    val factor: Double,
    val intervalAddMin: Int,
    val preferTbr: Boolean,
    val reason: String,
    val stage: InsulinActivityStage
) {
    companion object {
        /**
         * Calcule le guard basé sur l'état PKPD actuel
         * 
         * @param pkpdRuntime Runtime PKPD actuel (peut être null si PKPD désactivé)
         * @param windowSinceLastDoseMin Minutes depuis dernière dose
         * @param bg Glycémie actuelle
         * @param delta Delta glycémie (mg/dL/5min)
         * @param shortAvgDelta Moyenne courte du delta
         * @param targetBg Cible glycémie
         * @param predBg Glycémie prédite (peut être null si prédiction absente)
         * @param isMealMode Si mode repas actif (prebolus, etc.)
         * @return Guard calculé, ou neutral guard si pas de restriction nécessaire
         */
        fun compute(
            pkpdRuntime: PkPdRuntime?,
            windowSinceLastDoseMin: Double,
            bg: Double,
            delta: Double,
            shortAvgDelta: Double,
            targetBg: Double,
            predBg: Double?,
            isMealMode: Boolean
        ): PkpdAbsorptionGuard {
            
            // Pas de guard si PKPD absent ou modes repas (prebolus/TBR modes)
            if (pkpdRuntime == null || isMealMode) {
                return neutral("PKPD_ABSENT_OR_MEAL_MODE")
            }
            
            val activity = pkpdRuntime.activity
            val params = pkpdRuntime.params
            
            // Base guard selon stage d'activité
            val baseGuard = when (activity.stage) {
                InsulinActivityStage.PRE_ONSET -> 
                    PkpdAbsorptionGuard(
                        factor = 0.5,
                        intervalAddMin = 4,
                        preferTbr = true,
                        reason = "PRE_ONSET",
                        stage = activity.stage
                    )
                
                InsulinActivityStage.RISING -> 
                    PkpdAbsorptionGuard(
                        factor = 0.6,
                        intervalAddMin = 3,
                        preferTbr = false,
                        reason = "RISING",
                        stage = activity.stage
                    )
                
                InsulinActivityStage.PEAK -> 
                    PkpdAbsorptionGuard(
                        factor = 0.7,
                        intervalAddMin = 2,
                        preferTbr = false,
                        reason = "PEAK",
                        stage = activity.stage
                    )
                
                InsulinActivityStage.TAIL -> {
                    // Dans la queue, moduler selon tailFraction
                    val tailFrac = pkpdRuntime.tailFraction
                    when {
                        tailFrac > 0.5 -> PkpdAbsorptionGuard(0.85, 1, false, "TAIL_HIGH", activity.stage)
                        tailFrac > 0.3 -> PkpdAbsorptionGuard(0.92, 1, false, "TAIL_MED", activity.stage)
                        else -> neutral("TAIL_LOW")
                    }
                }
                
                InsulinActivityStage.EXHAUSTED -> 
                    neutral("EXHAUSTED")
            }
            
            // Urgency relaxation : relâcher guard si vraie urgence
            val isUrgency = bg > targetBg + 80 && delta > 5.0 && (predBg ?: bg) > bg + 30
            
            if (isUrgency) {
                val relaxedFactor = (baseGuard.factor + 0.25).coerceAtMost(1.0)
                val relaxedInterval = (baseGuard.intervalAddMin - 2).coerceAtLeast(0)
                return baseGuard.copy(
                    factor = relaxedFactor,
                    intervalAddMin = relaxedInterval,
                    reason = "${baseGuard.reason}_URGENCY_RELAXED"
                )
            }
            
            // Modulation selon tendance : si BG stable ou baisse, on peut être plus permissif
            val isStableOrFalling = delta < 1.0 && shortAvgDelta < 1.5
            if (isStableOrFalling && baseGuard.factor < 0.9) {
                val adjustedFactor = (baseGuard.factor + 0.1).coerceAtMost(0.95)
                return baseGuard.copy(
                    factor = adjustedFactor,
                    reason = "${baseGuard.reason}_STABLE"
                )
            }
            
            return baseGuard
        }
        
        /**
         * Guard neutre (pas de restriction)
         */
        private fun neutral(reason: String) = PkpdAbsorptionGuard(
            factor = 1.0,
            intervalAddMin = 0,
            preferTbr = false,
            reason = reason,
            stage = InsulinActivityStage.EXHAUSTED
        )
    }
    
    /**
     * Retourne true si ce guard est actif (impose des restrictions)
     */
    fun isActive(): Boolean = factor < 0.99 || intervalAddMin > 0
    
    /**
     * Formatte pour logs
     */
    fun toLogString(): String {
        val formattedFactor = String.format("%.2f", factor)
        return "PKPD_GUARD stage=$stage factor=$formattedFactor +${intervalAddMin}m reason=$reason"
    }
}
