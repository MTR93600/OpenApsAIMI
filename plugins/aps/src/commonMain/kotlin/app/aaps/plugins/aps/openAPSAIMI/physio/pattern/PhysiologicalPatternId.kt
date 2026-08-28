package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

/**
 * Catalog of body-state patterns AIMI can recognize and route through RBT + dose policy.
 * See docs/AIMI_PHYSIOLOGICAL_PATTERN_CATALOG.md.
 */
enum class PhysiologicalPatternId {
    // Endocrine / circadian
    DAWN_CORTISOL,
    MALE_CIRCADIAN_HORMONAL,
    FEMALE_CYCLE_HORMONAL,
    ENDOGENOUS_COUNTER_REGULATORY,
    NGR_NIGHT_GROWTH,

    // Meal / absorption
    MEAL_DECLARED,
    MEAL_UNDECLARED_FAST,
    MEAL_FIRST_WAVE,
    MEAL_SECOND_WAVE,
    LATE_FAT_PROTEIN,

    // Stress / recovery / sleep
    STRESS_CORTISOL_ACUTE,
    PSYCHOSOCIAL_STRESS,
    SLEEP_DEBT,
    HRV_DEPRESSED,
    RECOVERY_NEEDED,
    POOR_SLEEP_MORNING_RISE,
    INFECTION_RISK,

    // Activity
    EXERCISE_ACUTE,
    POST_EXERCISE_SENSITIVITY,
    SEDENTARY_DAY,
    EXERCISE_LOCKOUT,

    // Insulin / trajectory / sensor
    IOB_STACKING_SURVEILLANCE,
    POST_HYPO_REBOUND,
    HYPER_INSTALLED,
    COMPRESSION_ARTIFACT,

    // Explicit user / context intents
    CONTEXT_ILLNESS,
    CONTEXT_STRESS_INTENT,
    CONTEXT_ACTIVITY_INTENT,
    ;

    val category: PhysiologicalPatternCategory
        get() = PhysiologicalPatternCatalog.categoryOf(this)
}

enum class PhysiologicalPatternCategory {
    ENDOCRINE,
    MEAL,
    STRESS_RECOVERY,
    ACTIVITY,
    INSULIN_TRAJECTORY,
    CONTEXT,
}
