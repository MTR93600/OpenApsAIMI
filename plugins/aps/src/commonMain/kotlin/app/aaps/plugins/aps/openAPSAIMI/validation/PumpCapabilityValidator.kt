package app.aaps.plugins.aps.openAPSAIMI.validation

import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import dev.zacsweers.metro.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Inject
class PumpCapabilityValidator {

    fun validateBasal(rate: Double, caps: PumpCaps): Double {
        var validatedRate = rate
        validatedRate = min(validatedRate, caps.maxBasal)
        validatedRate = max(validatedRate, 0.0)
        validatedRate = alignToStep(validatedRate, caps.basalStep)
        return validatedRate
    }

    fun alignToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return (value / step).roundToInt() * step
    }

    fun isValidDuration(durationMin: Int, caps: PumpCaps): Boolean {
        return durationMin >= caps.minDurationMin
    }
}
