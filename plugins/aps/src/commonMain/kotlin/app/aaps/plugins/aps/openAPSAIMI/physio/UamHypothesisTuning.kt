package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * The five tunables `UamHypothesisStateBuilder` reads from the user's behaviour settings.
 *
 * `AimiBehaviorRuntimeProfile` is the only implementation. It lives in the `compose` package. Since
 * step C1 of the settings plan dosing always gets its fixed `NEUTRAL` profile, whose five values
 * are the same as the builder's fallbacks.
 *
 * **This interface holds no numbers.** Each implementation decides its own values, and the builder
 * keeps its own `?:` fallback for the case where no profile was supplied. Do not add default bodies
 * here - that would put a third copy of the numbers in play.
 */
internal interface UamHypothesisTuning {

    /** Upper bound on meal probability once meal interpretation is being suppressed. */
    fun mealSuppressionCap(): Double

    /** How far a competing non-meal hypothesis must lead the meal one before it damps it. */
    fun competingNonMealDominanceMargin(): Double

    /** Absolute confidence a competing non-meal hypothesis needs before it may damp the meal one. */
    fun competingNonMealConfidenceFloor(): Double

    /** How far the dominant hypothesis must lead the damped meal one to suppress meal reading. */
    fun suppressMealDecisionMargin(): Double

    /** Absolute confidence the dominant hypothesis needs to suppress meal reading. */
    fun suppressMealDecisionFloor(): Double
}
