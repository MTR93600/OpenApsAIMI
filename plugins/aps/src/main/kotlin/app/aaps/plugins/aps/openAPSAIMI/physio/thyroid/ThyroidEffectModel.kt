package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

class ThyroidEffectModel {
    // These constants will ideally also be configurable via Prefs, but hardcoded models are first level
    
    fun calculateEffects(status: ThyroidStatus, confidence: Double): ThyroidEffects {
        if (status == ThyroidStatus.EUTHYROID || status == ThyroidStatus.UNKNOWN) {
             return ThyroidEffects()
        }
        
        val severityFactor = when (status) {
             ThyroidStatus.HYPER_MILD -> 0.33
             ThyroidStatus.HYPER_MODERATE -> 0.66
             ThyroidStatus.HYPER_SEVERE -> 1.0
             ThyroidStatus.NORMALIZING -> 0.1 // Normalizing has very low "hyper" effects but HUGE safety implications
             else -> 0.0
        } * confidence

        // Hyperthyroidism = increased EGP, faster clearance (shorter DIA), faster carbs
        val egpMult = 1.0 + (0.35 * severityFactor) // Up to +35% EGP
        val diaMult = 1.0 - (0.20 * severityFactor) // Up to -20% DIA
        val carbMult = 1.0 + (0.25 * severityFactor) // Up to +25% faster carb absorption
        val isfMult = 1.0 - (0.10 * severityFactor) // ISF is slightly more "resistant" 

        return ThyroidEffects(
            status = status,
            diaMultiplier = diaMult.coerceIn(0.75, 1.0),
            egpMultiplier = egpMult.coerceIn(1.0, 1.40),
            carbRateMultiplier = carbMult.coerceIn(1.0, 1.30),
            isfMultiplier = isfMult.coerceIn(0.85, 1.0)
        )
    }
}
