package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Prevents 5-minute flip-flop from dawn/endogenous hormonal phases into MEAL_UNDECLARED / OFF.
 *
 * Do **not** call [reset] from the per-tick loop — that zeroes the hold every invoke and makes
 * the cross-tick hold dead. Reset only from tests or plugin/loop restart.
 */
object EndogenousPhaseHysteresis {

    const val HOLD_TICKS_DEFAULT = 4

    @Volatile
    private var holdTicksRemaining: Int = 0

    @Volatile
    private var heldPhase: PhysiologicalPhase? = null

    fun reset() {
        holdTicksRemaining = 0
        heldPhase = null
    }

    fun stabilize(raw: PhysiologicalPhaseClassifier.Output): PhysiologicalPhaseClassifier.Output {
        if (raw.phase == PhysiologicalPhase.MEAL_DECLARED) {
            reset()
            return raw
        }
        // A rise too steep for cortisol is new strong evidence, not flip-flop. Without this the
        // hold would hand the cortisol policy — and its 0.75 U SMB cap — back to the first four
        // ticks of the meal, which is when the dose is worth most.
        if (raw.breaksEndogenousHold) {
            reset()
            return raw
        }
        if (raw.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY ||
            raw.phase.isHormonalRisk ||
            raw.phase == PhysiologicalPhase.STRESS_CORTISOL
        ) {
            holdTicksRemaining = HOLD_TICKS_DEFAULT
            heldPhase = raw.phase
            return raw
        }
        val phaseToHold = heldPhase
        if (holdTicksRemaining > 0 &&
            phaseToHold != null &&
            (raw.phase == PhysiologicalPhase.MEAL_UNDECLARED || raw.phase == PhysiologicalPhase.OFF)
        ) {
            holdTicksRemaining -= 1
            return PhysiologicalPhaseClassifier.Output(
                phase = phaseToHold,
                confidence = raw.confidence.coerceAtMost(0.88),
                policy = BehavioralRiskPolicy.forPhase(
                    phaseToHold,
                    raw.confidence.coerceAtMost(0.88),
                    "endogenous hysteresis hold",
                ),
            )
        }
        reset()
        return raw
    }
}

