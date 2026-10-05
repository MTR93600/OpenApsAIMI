package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * Undeclared-meal onset. The effort veto is read by the Android caller at this call.
 * A true veto returns false and does not start the forced meal temp basal.
 * The approved iOS value, without an assessment, is false. Android still reads the assessment.
 */
internal fun decideDetectMealOnset(
    delta: Float,
    predictedDelta: Float,
    acceleration: Float,
    predictedBg: Float,
    targetBg: Float,
    effortSuppressesUndeclaredMeal: Boolean,
): Boolean {
    if (effortSuppressesUndeclaredMeal) return false
    val combinedDelta = (delta + predictedDelta) / 2.0f

    if (combinedDelta > 3.0f && acceleration > 1.2f) return true

    val normalizedRise = ((predictedBg - targetBg) / 70.0f).coerceIn(0.0f, 1.0f)
    if (normalizedRise > 0.3f && combinedDelta > 2.0f && acceleration > 0.3f) return true

    val isHighNoise = (delta > 5.0f && acceleration < 0.0f)
    if (!isHighNoise && (combinedDelta > 6.0f || (delta > 5.0f && acceleration > 0.5f))) return true

    return false
}
