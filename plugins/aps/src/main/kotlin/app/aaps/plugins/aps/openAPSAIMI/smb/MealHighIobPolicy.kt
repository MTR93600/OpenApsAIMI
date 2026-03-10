package app.aaps.plugins.aps.openAPSAIMI.smb

internal data class MealHighIobDecision(val relax: Boolean, val damping: Double)

/**
 * Décide s’il faut assouplir temporairement le plafond IOB en montée post-prandiale
 * et renvoie un facteur de réduction (0.5..1.0). Logique identique à l’existant.
 */
internal fun computeMealHighIobDecision(
    mealModeActive: Boolean,
    bg: Double,
    delta: Double,
    eventualBg: Double,
    targetBg: Double,
    iob: Double,
    maxIob: Double
): MealHighIobDecision {
    if (!mealModeActive) return MealHighIobDecision(false, 1.0)
    if (maxIob <= 0.0) return MealHighIobDecision(false, 1.0)
    if (iob <= maxIob) return MealHighIobDecision(false, 1.0)
    if (bg <= kotlin.math.max(120.0, targetBg)) return MealHighIobDecision(false, 1.0)
    if (delta <= 0.5) return MealHighIobDecision(false, 1.0)
    if (eventualBg <= targetBg + 10.0) return MealHighIobDecision(false, 1.0)
    val slack = maxIob * 0.3
    if (slack <= 0.0) return MealHighIobDecision(false, 1.0)
    if (iob > maxIob + slack) return MealHighIobDecision(false, 1.0)
    val excessFraction = ((iob - maxIob) / slack).coerceIn(0.0, 1.0)
    val damping = 1.0 - 0.5 * excessFraction
    return MealHighIobDecision(true, damping)
}
