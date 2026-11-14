package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings

/**
 * The four beep settings of the Omnipod driver: the same keys and the same defaults as Android
 * `OmnipodBooleanPreferenceKey` (`:pump:omnipod:common`, Android only). A phone that moves its
 * settings from Android to an iPhone keeps them, and an iPhone with no saved value beeps as Android
 * does.
 *
 * Preference keys with the titles of the Android settings screen, shown by the iPhone settings screen
 * (`dashSettingsScreen`).
 */
enum class DashBeepKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
) : BooleanPreferenceKey {

    BolusBeepsEnabled("AAPS.Omnipod.bolus_beeps_enabled", true, DashStrings.dash_pref_bolus_beeps_enabled),
    SmbBeepsEnabled("AAPS.Omnipod.smb_beeps_enabled", true, DashStrings.dash_pref_smb_beeps_enabled),
    BasalBeepsEnabled("AAPS.Omnipod.basal_beeps_enabled", false, DashStrings.dash_pref_basal_beeps_enabled),
    TbrBeepsEnabled("AAPS.Omnipod.tbr_beeps_enabled", false, DashStrings.dash_pref_tbr_beeps_enabled),
    ;

    override val preferenceType: PreferenceType = PreferenceType.SWITCH

    companion object {

        /**
         * The beep settings the driver uses, read from the user settings with [read] (on the phone
         * `Preferences.get`). Read again for each command, as Android reads its preferences when it
         * builds a command.
         */
        fun beepSettings(read: (BooleanNonPreferenceKey) -> Boolean): DashBeepSettings = DashBeepSettings(
            bolusBeeps = read(BolusBeepsEnabled),
            smbBeeps = read(SmbBeepsEnabled),
            basalBeeps = read(BasalBeepsEnabled),
            tempBasalBeeps = read(TbrBeepsEnabled),
        )
    }
}
