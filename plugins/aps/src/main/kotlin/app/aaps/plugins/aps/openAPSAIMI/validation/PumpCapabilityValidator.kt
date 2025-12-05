package app.aaps.plugins.aps.openAPSAIMI.validation

import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PumpCapabilityValidator @Inject constructor() {

    /**
     * Validates and adjusts the basal rate to ensure it respects pump capabilities.
     * 1. Clamps to [0, maxBasal]
     * 2. Aligns to basalStep
     */
    fun validateBasal(rate: Double, caps: PumpCaps): Double {
        var validatedRate = rate

        // 1. Check max basal
        validatedRate = min(validatedRate, caps.maxBasal)

        // 2. Ensure non-negative
        validatedRate = max(validatedRate, 0.0)

        // 3. Align to step
        validatedRate = alignToStep(validatedRate, caps.basalStep)

        return validatedRate
    }

    /**
     * Aligns a value to the nearest multiple of the step.
     */
    fun alignToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return (value / step).roundToInt() * step
    }

    /**
     * Checks if the duration is valid (>= minDurationMin).
     */
    fun isValidDuration(durationMin: Int, caps: PumpCaps): Boolean {
        return durationMin >= caps.minDurationMin
    }
}
