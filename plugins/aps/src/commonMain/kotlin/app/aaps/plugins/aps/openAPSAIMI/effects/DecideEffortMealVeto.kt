package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief

/** Minimum effort confidence before an undeclared rise is read as exertion. */
internal const val EFFORT_MEAL_SUPPRESS_CONF = 0.30

/** At or above this COB the rise is a real meal and the veto stays off. */
internal const val EFFORT_MEAL_SUPPRESS_MAX_COB_G = 12.0

/**
 * True when a rise should be read as exertion, not an undeclared meal.
 * EXERTION, ACTIVE or RECENT_EFFORT, confidence at least [EFFORT_MEAL_SUPPRESS_CONF],
 * no declared meal, COB strictly under [EFFORT_MEAL_SUPPRESS_MAX_COB_G].
 * No assessment returns false. This only suppresses an escalation.
 */
internal fun decideEffortSuppressesUndeclaredMeal(
    assessment: EffortActivityBelief.Assessment?,
    declaredMeal: Boolean,
    cobG: Double,
): Boolean {
    val a = assessment ?: return false
    if (a.posture != EffortActivityBelief.Posture.EXERTION) return false
    if (a.state != EffortActivityBelief.State.ACTIVE && a.state != EffortActivityBelief.State.RECENT_EFFORT) return false
    if (a.confidence < EFFORT_MEAL_SUPPRESS_CONF) return false
    return !declaredMeal && cobG < EFFORT_MEAL_SUPPRESS_MAX_COB_G
}
