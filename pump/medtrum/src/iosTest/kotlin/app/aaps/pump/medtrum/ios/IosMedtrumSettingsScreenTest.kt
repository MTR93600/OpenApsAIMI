package app.aaps.pump.medtrum.ios

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringKeyWithEntriesProvider
import app.aaps.pump.medtrum.FakeMedtrumPreferences
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.keys.MedtrumIntKey
import app.aaps.pump.medtrum.keys.MedtrumStringKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The iPhone settings screen offers the pump settings of the Android screen. */
class IosMedtrumSettingsScreenTest {

    @AfterTest
    fun resetLimits() {
        MedtrumIntKey.MedtrumHourlyMaxInsulin.max = 40
        MedtrumIntKey.MedtrumDailyMaxInsulin.max = 180
    }

    @Test
    fun `the screen lists the Android pump settings in the Android order`() {
        val items = medtrumIosSettingItems(PumpType.MEDTRUM_NANO, FakeMedtrumPreferences())

        val alarm = assertIs<StringKeyWithEntriesProvider>(items.first())
        assertEquals(MedtrumStringKey.MedtrumAlarmSettings.key, alarm.key)
        assertEquals(listOf("6", "7"), alarm.entriesProvider().keys.toList(), "a Nano offers only beep and silent")
        assertEquals(
            listOf(
                MedtrumBooleanKey.MedtrumWarningNotification,
                MedtrumBooleanKey.MedtrumPatchExpiration,
                MedtrumIntKey.MedtrumPumpExpiryWarningHours,
                MedtrumIntKey.MedtrumHourlyMaxInsulin,
                MedtrumIntKey.MedtrumDailyMaxInsulin
            ),
            items.drop(1)
        )
    }

    @Test
    fun `scan on connection error is not offered because the iPhone always scans`() {
        val items = medtrumIosSettingItems(PumpType.MEDTRUM_NANO, FakeMedtrumPreferences())

        assertFalse(items.any { (it as? PreferenceKey)?.key == MedtrumBooleanKey.MedtrumScanOnConnectionErrors.key })
        assertFalse(MedtrumBooleanKey.MedtrumScanOnConnectionErrors in IOS_MEDTRUM_BOOLEAN_KEYS)
    }

    @Test
    fun `opening the screen sets the insulin limits of the pump model`() {
        val preferences = FakeMedtrumPreferences().apply { put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 55) }

        medtrumIosSettingItems(PumpType.MEDTRUM_300U, preferences)
        assertEquals(60, MedtrumIntKey.MedtrumHourlyMaxInsulin.max)
        assertEquals(55, preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin))

        medtrumIosSettingItems(PumpType.MEDTRUM_NANO, preferences)
        assertEquals(40, MedtrumIntKey.MedtrumHourlyMaxInsulin.max)
        assertEquals(40, preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin))
    }
}
