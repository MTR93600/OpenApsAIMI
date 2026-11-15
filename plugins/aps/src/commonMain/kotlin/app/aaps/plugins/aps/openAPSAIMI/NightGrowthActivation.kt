package app.aaps.plugins.aps.openAPSAIMI

/** Why night growth support (NGR) is on or off right now. */
internal enum class NightGrowthState(val enabled: Boolean) {

    /** Its own switch was set and is on. */
    ON_BY_SWITCH(true),

    /** Its own switch was set and is off. */
    OFF_BY_SWITCH(false),

    /** The switch was never set and the age is under 18. */
    ON_BY_AGE(true),

    /** The switch was never set and the age is 18 or more. */
    OFF_BY_AGE(false),

    /** T3C brittle mode is on, which always turns NGR on, whatever the switch and the age say. */
    ON_BY_T3C(true),
}

/**
 * The one rule that says whether night growth support runs.
 *
 * `DetermineBasalAIMI2.buildNightGrowthResistanceConfig` uses it to dose, and the Essentials page
 * uses it to show the state read only, so the page can never show a state the loop does not use.
 */
internal object NightGrowthActivation {

    /** Age under which NGR is on when its switch was never set. */
    const val AGE_LIMIT_YEARS: Int = 18

    /**
     * The switch decides when it was set ([enabledPref] not null). When it was never set, the age
     * decides. This is the part of the rule before T3C.
     */
    fun isEnabledBySwitchOrAge(enabledPref: Boolean?, ageYears: Int): Boolean =
        enabledPref ?: (ageYears < AGE_LIMIT_YEARS)

    /** The full rule with its reason: switch or age, and T3C brittle mode turns it on. */
    fun resolve(enabledPref: Boolean?, ageYears: Int, t3cBrittleMode: Boolean): NightGrowthState =
        when {
            isEnabledBySwitchOrAge(enabledPref, ageYears) -> if (enabledPref == true) NightGrowthState.ON_BY_SWITCH else NightGrowthState.ON_BY_AGE
            t3cBrittleMode                                -> NightGrowthState.ON_BY_T3C
            enabledPref == false                          -> NightGrowthState.OFF_BY_SWITCH
            else                                          -> NightGrowthState.OFF_BY_AGE
        }
}
