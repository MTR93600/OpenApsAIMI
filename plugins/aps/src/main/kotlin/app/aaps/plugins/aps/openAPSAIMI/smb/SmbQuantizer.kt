package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.model.Constants

object SmbQuantizer {

    /** API générique en Double (déjà présente) */
    fun quantize(
        units: Double,
        step: Double = Constants.DEFAULT_INSULIN_STEP_U,
        minU: Double = 0.0,
        maxU: Double = Double.POSITIVE_INFINITY
    ): Double {
        val clamped = units.coerceIn(minU, maxU)
        val q = kotlin.math.round(clamped / step) * step
        return if (kotlin.math.abs(q) < 1e-6) 0.0 else q
    }

    /** Surcouche “convenience” pour rester compatible avec les appels existants */
    fun quantizeToPumpStep(
        units: Float,
        step: Float,
        minU: Float = 0f,
        maxU: Float = Float.POSITIVE_INFINITY
    ): Float {
        // D'abord, on quantifie normalement
        var quantized = quantize(
            units = units.toDouble(),
            step  = step.toDouble(),
            minU  = minU.toDouble(),
            maxU  = maxU.toDouble()
        )

        // --- Nouveau : plancher sécurité ---
        if (quantized == 0.0 && units > 0.02) {
            // si tout est OK, injecte le pas mini (ex: 0.05U)
            quantized = step.toDouble()
        }

        return quantized.toFloat()
    }

    /** Variante Double si tu veux standardiser tout en Double dans le futur */
    fun quantizeToPumpStep(units: Double, step: Double, minU: Double = 0.0, maxU: Double = Double.POSITIVE_INFINITY): Double {
        return quantize(units, step, minU, maxU)
    }
}
