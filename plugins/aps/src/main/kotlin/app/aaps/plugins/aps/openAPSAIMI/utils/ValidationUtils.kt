package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * 🛡️ ValidationUtils
 * 
 * Enforces strict medical bounds for glucose data and predictions.
 * Part of the advanced Kotlin refactoring for safety.
 */
object ValidationUtils {

    const val MIN_BG = 30.0
    const val MAX_BG = 600.0
    const val MIN_VELOCITY = -10.0
    const val MAX_VELOCITY = 10.0

    /**
     * Validates blood glucose value.
     */
    fun validateBg(bg: Double): Double {
        return bg.coerceIn(MIN_BG, MAX_BG)
    }

    /**
     * Validates blood glucose velocity.
     */
    fun validateVelocity(velocity: Double): Double {
        return velocity.coerceIn(MIN_VELOCITY, MAX_VELOCITY)
    }

    /**
     * Validates a numeric type for a generic preference.
     */
    fun <T : Number> validatePreference(value: T, min: T, max: T): T {
        // Simple numeric validation for basic types
        return if (value.toDouble() < min.toDouble()) min 
        else if (value.toDouble() > max.toDouble()) max
        else value
    }
}
