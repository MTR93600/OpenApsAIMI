package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The beep settings use the keys and the defaults of Android `OmnipodBooleanPreferenceKey`, written
 * out here by hand from `:pump:omnipod:common` (which this module cannot see), so a change on one side
 * fails here.
 */
class DashBeepKeyTest {

    @Test
    fun `the keys and the defaults are the Android ones`() {
        assertEquals(
            listOf(
                "AAPS.Omnipod.bolus_beeps_enabled" to true,
                "AAPS.Omnipod.smb_beeps_enabled" to true,
                "AAPS.Omnipod.basal_beeps_enabled" to false,
                "AAPS.Omnipod.tbr_beeps_enabled" to false,
            ),
            DashBeepKey.entries.map { it.key to it.defaultValue }
        )
    }

    @Test
    fun `with no saved value the driver beeps as Android`() {
        val settings = DashBeepKey.beepSettings { key -> key.defaultValue }

        assertEquals(DashBeepSettings(bolusBeeps = true, smbBeeps = true, basalBeeps = false, tempBasalBeeps = false), settings)
    }

    @Test
    fun `each saved value goes to its own beep`() {
        val saved = mapOf<BooleanNonPreferenceKey, Boolean>(
            DashBeepKey.BolusBeepsEnabled to false,
            DashBeepKey.SmbBeepsEnabled to true,
            DashBeepKey.BasalBeepsEnabled to true,
            DashBeepKey.TbrBeepsEnabled to false,
        )

        val settings = DashBeepKey.beepSettings { key -> saved.getValue(key) }

        assertEquals(DashBeepSettings(bolusBeeps = false, smbBeeps = true, basalBeeps = true, tempBasalBeeps = false), settings)
    }
}
