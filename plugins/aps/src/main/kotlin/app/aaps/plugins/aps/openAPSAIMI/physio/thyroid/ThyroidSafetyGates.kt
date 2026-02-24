package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

class ThyroidSafetyGates {
    
    fun applyGates(
        inputs: ThyroidInputs,
        effects: ThyroidEffects,
        currentBg: Double,
        bgDelta: Double,
        currentIob: Double
    ): ThyroidEffects {
        
        if (effects.status != ThyroidStatus.NORMALIZING) {
             return effects // No specific heavy gates outside of normalizing
        }

        // --- NORMALIZING GATE ---
        // During normalizing, insulin sensitivity rapidly returns. 
        // We MUST prevent aggressive corrections that were previously needed in the hyperthyroid state.

        val isDropping = bgDelta < -2.0
        val isIobHigh = currentIob > 1.5 // Rough threshold, ideally dynamic vs TDD

        var blockSmb = false
        var smbCap: Double? = null
        var basalCap = 1.5 // Default max
        
        when (inputs.guardLevel) {
             NormalizingGuardLevel.HIGH -> {
                 basalCap = 1.1 // Very tight max basal
                 if (isDropping) blockSmb = true
                 else smbCap = 0.5 // Force small SMBs
             }
             NormalizingGuardLevel.MEDIUM -> {
                 basalCap = 1.3
                 if (isDropping && isIobHigh) blockSmb = true
                 else smbCap = 1.0
             }
             NormalizingGuardLevel.LOW -> {
                 basalCap = 1.5
                 if (isDropping && isIobHigh && currentBg < 110) blockSmb = true
             }
        }

        return effects.copy(
             blockSmb = blockSmb,
             smbCapUnits = smbCap,
             basalCapMultiplier = basalCap
        )
    }
}
