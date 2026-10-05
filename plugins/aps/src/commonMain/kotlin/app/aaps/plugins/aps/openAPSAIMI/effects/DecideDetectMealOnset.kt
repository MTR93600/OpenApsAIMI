package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief

/**
 * Undeclared-meal onset behind the effort veto.
 * The veto is [decideEffortSuppressesUndeclaredMeal]: no assessment is false.
 * A true veto returns false and does not start the forced meal temp basal.
 */
internal fun decideMealOnsetBehindEffortVeto(
    delta: Float,
    predictedDelta: Float,
    acceleration: Float,
    predictedBg: Float,
    targetBg: Float,
    assessment: EffortActivityBelief.Assessment?,
    declaredMeal: Boolean,
    cobG: Double,
): Boolean = decideDetectMealOnset(
    delta = delta,
    predictedDelta = predictedDelta,
    acceleration = acceleration,
    predictedBg = predictedBg,
    targetBg = targetBg,
    effortSuppressesUndeclaredMeal = decideEffortSuppressesUndeclaredMeal(
        assessment = assessment,
        declaredMeal = declaredMeal,
        cobG = cobG,
    ),
)

/**
 * Undeclared-meal onset. A true veto returns false and does not start the forced meal temp basal.
 * Callers that already know the veto pass it here. [decideMealOnsetBehindEffortVeto] computes it.
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
