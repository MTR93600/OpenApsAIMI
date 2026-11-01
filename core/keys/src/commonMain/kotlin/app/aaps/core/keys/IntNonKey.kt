package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),

    /** Hours shown by the AIMI dashboard graph (6, 9, 12, 18 or 24). Same key and default as `dev_OAPSAIMI`. */
    RangeToDisplay("rangetodisplay", 6)
}