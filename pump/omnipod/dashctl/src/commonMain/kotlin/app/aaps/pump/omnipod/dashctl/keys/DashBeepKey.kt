package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings

/**
 * The four beep settings of the Omnipod driver: the same keys and the same defaults as Android
 * `OmnipodBooleanPreferenceKey` (`:pump:omnipod:common`, Android only). A phone that moves its
 * settings from Android to an iPhone keeps them, and an iPhone with no saved value beeps as Android
 * does.
 *
 * Non-preference keys for now: the iPhone has no settings screen for Dash yet (phase 5). When the
 * screen comes, these become preference keys with a title, with the same key and default.
 */
enum class DashBeepKey(
    override val key: String,
    override val defaultValue: Boolean,
) : BooleanNonPreferenceKey {

    BolusBeepsEnabled("AAPS.Omnipod.bolus_beeps_enabled", true),
    SmbBeepsEnabled("AAPS.Omnipod.smb_beeps_enabled", true),
    BasalBeepsEnabled("AAPS.Omnipod.basal_beeps_enabled", false),
    TbrBeepsEnabled("AAPS.Omnipod.tbr_beeps_enabled", false),
    ;

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
