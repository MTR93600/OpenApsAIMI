package app.aaps.plugins.sync.nsclientV3.remotecontrol

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/**
 * One button of the AIMI Remote Control.
 *
 * @property therapyKeyword the word the master looks for in a note (see `Therapy` in AIMI). It is
 *   part of the protocol between the phones, so it is never translated.
 * @property defaultDurationMin the duration shown before the user changes it. 0 for [ModePresets.STOP_ID].
 */
data class ModePreset(
    val id: String,
    val name: TextRef,
    val description: TextRef,
    val therapyKeyword: String,
    val defaultDurationMin: Int,
    val category: ModeCategory
)

/** Decides how a mode is sent, and on which tab it is shown. */
enum class ModeCategory {

    /** Sent as a note. Starts the P1/P2 prebolus on the master. Modes tab. */
    MEAL,

    /** Sent as an AIMI context. Modes tab. */
    ACTIVITY,

    /** Sent as an AIMI context. Contexts tab only. */
    CONTEXT_ONLY,

    /** Sent as an AIMI context (stress, illness, ...). Contexts tab. */
    PHYSIO,

    /** Sent as a note (stop, fasting, low carb). Modes tab. */
    CONTROL;

    /** True when the mode goes out as a note, false when it goes out as an AIMI context. */
    val sentAsNote: Boolean get() = this == MEAL || this == CONTROL
}

/** All modes of the AIMI Remote Control, in the order of dev_OAPSAIMI. */
object ModePresets {

    const val STOP_ID = "stop"

    val ALL_MODES: List<ModePreset> = listOf(
        // Meal modes: start the P1/P2 prebolus
        ModePreset("breakfast", SyncStrings.aimi_rc_mode_breakfast, SyncStrings.aimi_rc_mode_meal_desc, "bfast", 60, ModeCategory.MEAL),
        ModePreset("lunch", SyncStrings.aimi_rc_mode_lunch, SyncStrings.aimi_rc_mode_meal_desc, "lunch", 60, ModeCategory.MEAL),
        ModePreset("dinner", SyncStrings.aimi_rc_mode_dinner, SyncStrings.aimi_rc_mode_meal_desc, "dinner", 60, ModeCategory.MEAL),
        ModePreset("snack", SyncStrings.aimi_rc_mode_snack, SyncStrings.aimi_rc_mode_meal_desc, "snack", 30, ModeCategory.MEAL),
        ModePreset("highcarb", SyncStrings.aimi_rc_mode_highcarb, SyncStrings.aimi_rc_mode_highcarb_desc, "highcarb", 90, ModeCategory.MEAL),
        ModePreset("meal", SyncStrings.aimi_rc_mode_meal, SyncStrings.aimi_rc_mode_meal_general_desc, "meal", 60, ModeCategory.MEAL),
        // Activity modes (Modes tab)
        ModePreset("sport", SyncStrings.aimi_rc_mode_sport, SyncStrings.aimi_rc_mode_sport_desc, "sport", 120, ModeCategory.ACTIVITY),
        ModePreset("sleep", SyncStrings.aimi_rc_mode_sleep, SyncStrings.aimi_rc_mode_sleep_desc, "sleep", 480, ModeCategory.ACTIVITY),
        // Activity contexts (Contexts tab only)
        ModePreset("cardio", SyncStrings.aimi_rc_mode_cardio, SyncStrings.aimi_rc_mode_cardio_desc, "cardio", 60, ModeCategory.CONTEXT_ONLY),
        ModePreset("strength", SyncStrings.aimi_rc_mode_strength, SyncStrings.aimi_rc_mode_strength_desc, "strength", 45, ModeCategory.CONTEXT_ONLY),
        ModePreset("yoga", SyncStrings.aimi_rc_mode_yoga, SyncStrings.aimi_rc_mode_yoga_desc, "yoga", 60, ModeCategory.CONTEXT_ONLY),
        ModePreset("walking", SyncStrings.aimi_rc_mode_walking, SyncStrings.aimi_rc_mode_walking_desc, "walking", 30, ModeCategory.CONTEXT_ONLY),
        // Physiological contexts
        ModePreset("stress", SyncStrings.aimi_rc_mode_stress, SyncStrings.aimi_rc_mode_stress_desc, "stress", 180, ModeCategory.PHYSIO),
        ModePreset("illness", SyncStrings.aimi_rc_mode_illness, SyncStrings.aimi_rc_mode_illness_desc, "illness", 480, ModeCategory.PHYSIO),
        ModePreset("gastro", SyncStrings.aimi_rc_mode_gastro, SyncStrings.aimi_rc_mode_gastro_desc, "gastro", 480, ModeCategory.PHYSIO),
        ModePreset("work_stress", SyncStrings.aimi_rc_mode_work_stress, SyncStrings.aimi_rc_mode_work_stress_desc, "work stress", 240, ModeCategory.PHYSIO),
        ModePreset("exam_stress", SyncStrings.aimi_rc_mode_exam_stress, SyncStrings.aimi_rc_mode_exam_stress_desc, "exam stress", 180, ModeCategory.PHYSIO),
        ModePreset("alcohol", SyncStrings.aimi_rc_mode_alcohol, SyncStrings.aimi_rc_mode_alcohol_desc, "alcohol", 360, ModeCategory.PHYSIO),
        ModePreset("travel", SyncStrings.aimi_rc_mode_travel, SyncStrings.aimi_rc_mode_travel_desc, "travel", 720, ModeCategory.PHYSIO),
        // Control modes
        ModePreset("fasting", SyncStrings.aimi_rc_mode_fasting, SyncStrings.aimi_rc_mode_fasting_desc, "fasting", 720, ModeCategory.CONTROL),
        ModePreset("lowcarb", SyncStrings.aimi_rc_mode_lowcarb, SyncStrings.aimi_rc_mode_lowcarb_desc, "lowcarb", 180, ModeCategory.CONTROL),
        ModePreset(STOP_ID, SyncStrings.aimi_rc_mode_stop, SyncStrings.aimi_rc_mode_stop_desc, "stop", 0, ModeCategory.CONTROL)
    )

    /** The Modes tab: meals, sport, sleep and the control modes. */
    val MODES_TAB: List<ModePreset> =
        ALL_MODES.filter { it.category == ModeCategory.MEAL || it.category == ModeCategory.ACTIVITY || it.category == ModeCategory.CONTROL }

    /** The Contexts tab: activity contexts and physiological contexts. */
    val CONTEXTS_TAB: List<ModePreset> =
        ALL_MODES.filter { it.category == ModeCategory.CONTEXT_ONLY || it.category == ModeCategory.PHYSIO }

    fun findByKeyword(keyword: String): ModePreset? = ALL_MODES.find { it.therapyKeyword.equals(keyword, ignoreCase = true) }
}
