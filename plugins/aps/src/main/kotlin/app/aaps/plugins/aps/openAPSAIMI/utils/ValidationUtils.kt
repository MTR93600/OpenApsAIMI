package app.aaps.plugins.aps.openAPSAIMI.utils

import android.util.Log

/**
 * Utilities for safe numeric operations and validation in the AIMI module.
 */
object ValidationUtils {

    private const val TAG = "AIMI_Validation"

    /**
     * Safely parse a Double from a string with a default fallback.
     */
    @JvmStatic
    fun safeParseDouble(value: String?, fallback: Double = 0.0): Double {
        if (value == null) return fallback
        return try {
            value.toDouble()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse Double from '$value', using fallback $fallback", e)
            fallback
        }
    }

    /**
     * Safely perform division, avoiding division by zero and NaN results.
     */
    @JvmStatic
    fun safeDiv(numerator: Double, denominator: Double, fallback: Double = 0.0): Double {
        if (denominator == 0.0 || denominator.isNaN()) {
            Log.w(TAG, "Division by zero or NaN denominator. Returning fallback $fallback")
            return fallback
        }
        val result = numerator / denominator
        return if (result.isNaN() || result.isInfinite()) {
            Log.w(TAG, "Division resulted in $result. Returning fallback $fallback")
            fallback
        } else {
            result
        }
    }

    /**
     * Ensure a value is within a specified range, or return a default fallback if it's NaN.
     */
    @JvmStatic
    fun safeClamp(value: Double, min: Double, max: Double, fallback: Double = min): Double {
        if (value.isNaN()) return fallback
        return value.coerceIn(min, max)
    }
}
