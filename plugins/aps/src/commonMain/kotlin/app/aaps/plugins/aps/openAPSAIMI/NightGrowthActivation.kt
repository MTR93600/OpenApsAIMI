package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences

/** Why night growth support (NGR) is on or off right now. */
internal enum class NightGrowthState(val enabled: Boolean) {

    /** Its own switch was set and is on. */
    ON_BY_SWITCH(true),

    /** Its own switch was set and is off. */
    OFF_BY_SWITCH(false),

    /** The switch was never set and the stored age is under 18. */
    ON_BY_AGE(true),

    /** The switch was never set and the stored age is 18 or more. */
    OFF_BY_AGE(false),

    /** The switch and the age were never set: AIMI takes the person for an adult. */
    OFF_NO_AGE(false),

    /** T3C brittle mode is on, which always turns NGR on, whatever the switch and the age say. */
    ON_BY_T3C(true),
}

/**
 * The one rule that says whether night growth support runs.
 *
 * `DetermineBasalAIMI2.buildNightGrowthResistanceConfig` uses it to dose. The Essentials page, the
 * night growth page (its state row and its switch row) and the "What AIMI learned" status use [read]
 * to show the state, so a page can never show a state the loop does not use.
 *
 * Owner decision of step D1 (2026-11-15): an age that was never stored means an adult, so NGR is off.
 * Before D1 the key default (14) was used, so NGR ran for every person who never set an age or the
 * switch. A stored switch or a stored age keeps the behaviour of before.
 *
 * Owner decision of step D2 (2026-11-15): the switch key default is off. This changes no decision:
 * the rule reads the switch with `getIfExists` only and never uses its key default. Any new reader
 * must do the same (use [read]).
 *
 * Owner decision of step A1 (2026-11-15, display only): the switch row of the night growth page shows
 * [read] too (`AimiNightGrowthSwitch`), not the stored switch, so it is right also with a stored age
 * under 18 and no stored switch. A tap there stores the opposite of what it shows.
 */
internal object NightGrowthActivation {

    /** Age under which NGR is on when its switch was never set. Also the adult age used without a stored age. */
    const val AGE_LIMIT_YEARS: Int = 18

    /**
     * The two keys where "never stored" is a state of its own, not the key default. A stored 14 turns
     * NGR on, while nothing stored means an adult (off). A stored `false` (the switch key default since
     * step D2) keeps NGR off even with a stored age under 18, while a switch never stored lets that age
     * turn it on. So a reset of these keys must remove the stored value, and must not write the key
     * default.
     */
    val STORED_OR_NOT_KEYS: Set<NonPreferenceKey> =
        setOf(IntKey.OApsAIMINightGrowthAgeYears, BooleanKey.OApsAIMINightGrowthEnabled)

    /**
     * The state from what is stored now: the switch and the age as stored (or not), and T3C brittle
     * mode. The loop and every page use this one read, so they always agree.
     */
    fun read(preferences: Preferences): NightGrowthState =
        resolve(
            enabledPref = preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled),
            storedAgeYears = preferences.getIfExists(IntKey.OApsAIMINightGrowthAgeYears),
            t3cBrittleMode = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode),
        )

    /**
     * The switch decides when it was set ([enabledPref] not null). When it was never set, a stored age
     * under 18 turns NGR on. No stored age ([storedAgeYears] null) means an adult: off. This is the
     * part of the rule before T3C.
     */
    fun isEnabledBySwitchOrAge(enabledPref: Boolean?, storedAgeYears: Int?): Boolean =
        enabledPref ?: (storedAgeYears != null && storedAgeYears < AGE_LIMIT_YEARS)

    /** The full rule with its reason: switch or stored age, and T3C brittle mode turns it on. */
    fun resolve(enabledPref: Boolean?, storedAgeYears: Int?, t3cBrittleMode: Boolean): NightGrowthState =
        when {
            enabledPref == true                                 -> NightGrowthState.ON_BY_SWITCH
            isEnabledBySwitchOrAge(enabledPref, storedAgeYears) -> NightGrowthState.ON_BY_AGE
            t3cBrittleMode                                      -> NightGrowthState.ON_BY_T3C
            enabledPref == false                                -> NightGrowthState.OFF_BY_SWITCH
            storedAgeYears == null                              -> NightGrowthState.OFF_NO_AGE
            else                                                -> NightGrowthState.OFF_BY_AGE
        }

    /**
     * The age the NGR config (its learner, its monitor and its slot cap) uses. It only matters while
     * NGR runs. Without a stored age that happens only with the switch set on, or with T3C brittle
     * mode. Both must work as before step D1, and the NGR monitor stops at age 18, so the key default
     * (14) stays in use for them.
     */
    fun configAgeYears(storedAgeYears: Int?): Int =
        (storedAgeYears ?: IntKey.OApsAIMINightGrowthAgeYears.defaultValue).coerceAtLeast(0)

    /**
     * The age for the night slope threshold of `GlucoseStatusCalculatorAimi`: the stored age, else the
     * adult age ([AGE_LIMIT_YEARS]). With the thresholds of today this gives the same value as the old
     * key default 14 (both are above 12), so nothing changes in practice.
     */
    fun nightSlopeAgeYears(storedAgeYears: Int?): Int = storedAgeYears ?: AGE_LIMIT_YEARS
}
