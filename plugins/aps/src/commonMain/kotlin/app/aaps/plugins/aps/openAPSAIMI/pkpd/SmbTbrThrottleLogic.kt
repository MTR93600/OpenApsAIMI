package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Logique de throttle intelligent SMB vs TBR basé sur l'état de l'action insulinique.
 * 
 * Principe: Ajuster la cadence et l'amplitude des SMBs selon le stage d'activité
 * de l'insuline active, tout en privilégiant TBR quand approprié.
 * 
 * GARANTIE: Jamais de blocage total. smbFactor minimum = 0.2
 */
object SmbTbrThrottleLogic {
    
    /**
     * Calcule le throttle approprié basé sur l'état insulinique et la tendance BG
     */
    fun computeThrottle(
        actionState: InsulinActionState,
        bgDelta: Double,
        bgRising: Boolean,
        targetBg: Double,
        currentBg: Double
    ): SmbTbrThrottle {
        
        // Règle 1: Onset non confirmé + BG monte → TBR prioritaire, SMB réduit
        // Rationale: On ne sait pas encore si l'insuline agit vraiment
        if (!actionState.onsetConfirmed && bgRising) {
            return SmbTbrThrottle(
                smbFactor = 0.6,
                intervalAddMin = 3,
                preferTbr = true,
                reason = "Onset unconfirmed, rising BG → TBR priority"
            )
        }
        
        // Règle 2: Near peak (activité élevée) → SMB très réduit
        // Rationale: Risque de stacking maximal quand l'insuline est la plus active
        if (actionState.activityStage == ActivityStage.PEAK || actionState.activityNow > 0.7) {
            return SmbTbrThrottle(
                smbFactor = 0.3,
                intervalAddMin = 5,
                preferTbr = true,
                reason = "Near peak / High activity → SMB throttled"
            )
        }
        
        // Règle 3: Tail (résiduel faible) + BG monte → SMB permissif
        // Rationale: Fin d'action, peu de risque d'empilement
        if (actionState.activityStage == ActivityStage.TAIL && 
            actionState.residualEffect < 0.3 && 
            bgRising) {
            return SmbTbrThrottle(
                smbFactor = 1.0,
                intervalAddMin = 0,
                preferTbr = false,
                reason = "Tail stage, low residual → SMB permitted"
            )
        }
        
        // Règle 4: Falling (post-peak normal) → SMB modéré
        // Rationale: Décroissance normale, prudence modérée
        if (actionState.activityStage == ActivityStage.FALLING) {
            return SmbTbrThrottle(
                smbFactor = 0.7,
                intervalAddMin = 2,
                preferTbr = false,
                reason = "Falling stage → SMB moderate"
            )
        }
        
        // Règle 5: BG très élevé (>targetBg+60) → override, SMB plus permissif
        // Rationale: Urgence hyperglycémie
        if (currentBg > targetBg + 60) {
            return SmbTbrThrottle(
                smbFactor = 0.9,
                intervalAddMin = 0,
                preferTbr = false,
                reason = "High BG override → SMB permitted"
            )
        }
        
        // Défaut: Normal (RISING avec onset confirmé)
        return SmbTbrThrottle.normal()
    }
}
