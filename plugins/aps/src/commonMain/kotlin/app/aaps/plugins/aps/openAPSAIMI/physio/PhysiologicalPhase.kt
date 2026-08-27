package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Dominant physiological context for dosing risk (one tick).
 * See docs/AIMI_PHYSIOLOGICAL_PHASE.md.
 */
enum class PhysiologicalPhase {
    OFF,
    DAWN_CORTISOL,
    MALE_CIRCADIAN_HORMONAL,
    FEMALE_CYCLE_HORMONAL,
    STRESS_CORTISOL,
    MEAL_DECLARED,
    MEAL_UNDECLARED,
    /** Sustained hepatic / cortisol ramp, COB=0 — basal bridge only, not meal UAM. */
    ENDOGENOUS_COUNTER_REGULATORY,
    HYPER_INSTALLED,
    ;

    val isHormonalRisk: Boolean
        get() = this == DAWN_CORTISOL ||
            this == MALE_CIRCADIAN_HORMONAL ||
            this == FEMALE_CYCLE_HORMONAL

    val isEndogenousRisk: Boolean
        get() = this == ENDOGENOUS_COUNTER_REGULATORY

    val isMealRisk: Boolean
        get() = this == MEAL_DECLARED || this == MEAL_UNDECLARED
}
