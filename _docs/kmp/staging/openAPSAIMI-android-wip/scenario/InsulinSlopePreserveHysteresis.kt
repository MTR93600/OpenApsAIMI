package app.aaps.plugins.aps.openAPSAIMI.scenario

/**
 * Holds [preserveInsulinSlope] true for [HOLD_TICKS_DEFAULT] ticks after it last went true,
 * so hybrid/floor geometry flicker (or a brief mealIntent blip) does not flip Scenario between
 * insulin-slope seed and collapsed-near-BG every 5 minutes.
 *
 * Same companion-object pattern as `MealAbsorptionPhaseHysteresis` / `EndogenousPhaseHysteresis`.
 * Lives outside the pure fusion math; [ScenarioProjectionEngine.build] calls [stabilize].
 *
 * Do **not** call [reset] from the per-tick loop — that zeroes [holdTicksRemaining] every invoke
 * and makes the cross-tick hold dead. Reset only from tests or plugin/loop restart.
 */
object InsulinSlopePreserveHysteresis {

    /** ~15–20 min at a 5 min loop — short enough to release when hybrid recovers shape. */
    const val HOLD_TICKS_DEFAULT = 4

    @Volatile
    private var holdTicksRemaining: Int = 0

    fun reset() {
        holdTicksRemaining = 0
    }

    /**
     * @param rawPreserve geometry + !mealIntent gate computed this tick
     * @return stabilized preserve flag for seed / layer skip
     */
    fun stabilize(rawPreserve: Boolean): Boolean {
        if (rawPreserve) {
            holdTicksRemaining = HOLD_TICKS_DEFAULT
            return true
        }
        if (holdTicksRemaining > 0) {
            holdTicksRemaining -= 1
            return true
        }
        return false
    }
}
