package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Unified meal-absorption state for SMB, HTR, IOB surveillance, and basal overlays.
 * See docs/AIMI_MEAL_ABSORPTION_PHASE.md.
 */
enum class MealAbsorptionPhase {
    NONE,
    /** Initial fast carb rise — full correction authority. */
    FIRST_WAVE,
    /** BG very high with stacked IOB but still in meal window — no IOB surveillance. */
    PEAK_CORRECTION,
    /** Plateau between waves — attenuated surveillance only. */
    INTER_WAVE,
    /** Re-acceleration after plateau within meal memory window. */
    SECOND_WAVE,
    /** Late fat/protein rise 2–7h post bolus. */
    LATE_FAT,
    ;

    val isActive: Boolean
        get() = this != NONE

    val bypassesIobSurveillance: Boolean
        get() = this == FIRST_WAVE || this == SECOND_WAVE || this == PEAK_CORRECTION

    val attenuatesIobSurveillance: Boolean
        get() = this == INTER_WAVE

    val forcesHtrRise: Boolean
        get() = this == FIRST_WAVE || this == SECOND_WAVE

    val usesMealHtrPolicy: Boolean
        get() = this == FIRST_WAVE || this == SECOND_WAVE || this == PEAK_CORRECTION || this == INTER_WAVE
}

