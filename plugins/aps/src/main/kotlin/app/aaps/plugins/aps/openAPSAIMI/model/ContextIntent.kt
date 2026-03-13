package app.aaps.plugins.aps.openAPSAIMI.model

import app.aaps.core.interfaces.aps.OapsProfileAimi

/**
 * 🎯 ContextIntent
 * 
 * Represents a clinical intention derived from user action or physiological state.
 * Includes validation logic to ensure the intent is safe for the current context.
 */
sealed class ContextIntent {
    
    /** Intent to cover carbs with a meal bolus. */
    data class MealSupport(val cob: Double, val ic: Double) : ContextIntent() {
        init {
            require(cob in 0.0..300.0) { "COB out of clinical bounds: $cob" }
            require(ic in 1.0..100.0) { "IC ratio out of safe bounds: $ic" }
        }
    }

    /** Intent to correct a high glucose level via SMB or TBR. */
    data class HighCorrection(val target: Double, val isf: Double) : ContextIntent() {
        init {
            require(target in 70.0..180.0) { "Target BG out of clinical bounds: $target" }
            require(isf > 0.0) { "ISF must be positive: $isf" }
        }
    }

    /** Intent to prevent a predicted hypoglycemia. */
    object HypoPrevention : ContextIntent()

    /** Intent to maintain stability (Routine check). */
    object StabilityMaintenance : ContextIntent()

    /**
     * Validates if the intent is clinically appropriate for the current profile.
     */
    fun isClinicallyValid(profile: OapsProfileAimi): Boolean = when (this) {
        is MealSupport -> cob > 0.0 && ic <= profile.sens // Use sens as fallback for ISF checks
        is HighCorrection -> target >= profile.target_bg - 10
        else -> true
    }
}
