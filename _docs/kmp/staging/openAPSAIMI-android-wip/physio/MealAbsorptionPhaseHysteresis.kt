package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Prevents flip-flop from active meal absorption phases back to NONE within [HOLD_TICKS_DEFAULT] ticks.
 */
object MealAbsorptionPhaseHysteresis {

    /** ~50 min at a 5 min loop — covers silent early absorption before CGM rise. */
    const val HOLD_TICKS_DEFAULT = 10

    @Volatile
    private var holdTicksRemaining: Int = 0

    @Volatile
    private var heldPhase: MealAbsorptionPhase? = null

    fun reset() {
        holdTicksRemaining = 0
        heldPhase = null
    }

    fun stabilize(raw: MealAbsorptionPhaseEngine.Output): MealAbsorptionPhaseEngine.Output {
        if (raw.phase == MealAbsorptionPhase.FIRST_WAVE ||
            raw.phase == MealAbsorptionPhase.SECOND_WAVE ||
            raw.phase == MealAbsorptionPhase.PEAK_CORRECTION ||
            raw.phase == MealAbsorptionPhase.INTER_WAVE
        ) {
            holdTicksRemaining = HOLD_TICKS_DEFAULT
            heldPhase = raw.phase
            return raw
        }
        val phaseToHold = heldPhase
        if (holdTicksRemaining > 0 &&
            phaseToHold != null &&
            raw.phase == MealAbsorptionPhase.NONE
        ) {
            holdTicksRemaining -= 1
            return raw.copy(
                phase = phaseToHold,
                belief = raw.belief.coerceAtLeast(0.45),
                reason = "meal absorption hysteresis hold",
            )
        }
        reset()
        return raw
    }
}

