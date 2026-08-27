package app.aaps.plugins.aps.openAPSAIMI.model

import app.aaps.core.interfaces.aps.OapsProfileAimi

sealed class ContextIntent {

    data class MealSupport(val cob: Double, val ic: Double) : ContextIntent() {
        init {
            require(cob in 0.0..300.0) { "COB out of clinical bounds: $cob" }
            require(ic in 1.0..100.0) { "IC ratio out of safe bounds: $ic" }
        }
    }

    data class HighCorrection(val target: Double, val isf: Double) : ContextIntent() {
        init {
            require(target in 70.0..180.0) { "Target BG out of clinical bounds: $target" }
            require(isf > 0.0) { "ISF must be positive: $isf" }
        }
    }

    object HypoPrevention : ContextIntent()

    object StabilityMaintenance : ContextIntent()

    fun isClinicallyValid(profile: OapsProfileAimi): Boolean = when (this) {
        is MealSupport -> cob > 0.0 && ic <= profile.sens
        is HighCorrection -> target >= profile.target_bg - 10
        else -> true
    }
}
