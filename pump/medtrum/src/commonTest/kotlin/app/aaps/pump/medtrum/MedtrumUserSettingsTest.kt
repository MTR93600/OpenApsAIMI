package app.aaps.pump.medtrum

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.keys.MedtrumIntKey
import app.aaps.pump.medtrum.keys.MedtrumStringKey
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An [AAPSLogger] that writes nothing. */
private object QuietLogger : AAPSLogger {

    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) = Unit
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) = Unit
    override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(message: String) = Unit
    override fun error(message: String, throwable: Throwable) = Unit
    override fun error(format: String, vararg arguments: Any?) = Unit
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
}

/**
 * The pump settings the person chose reach the patch: the hourly and daily insulin maximums stop the
 * pump when they are passed, so a value read from the wrong key would change therapy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedtrumUserSettingsTest {

    @AfterTest
    fun resetLimits() {
        // The limits are fields of shared enum entries; put back the values a Nano has.
        MedtrumIntKey.MedtrumHourlyMaxInsulin.max = 40
        MedtrumIntKey.MedtrumDailyMaxInsulin.max = 180
    }

    @Test
    fun `the keys and defaults are the ones of dev`() {
        assertEquals("alarm_setting", MedtrumStringKey.MedtrumAlarmSettings.key)
        assertEquals("6", MedtrumStringKey.MedtrumAlarmSettings.defaultValue)
        assertEquals("hourly_max_insulin", MedtrumIntKey.MedtrumHourlyMaxInsulin.key)
        assertEquals(25, MedtrumIntKey.MedtrumHourlyMaxInsulin.defaultValue)
        assertEquals("daily_max_insulin", MedtrumIntKey.MedtrumDailyMaxInsulin.key)
        assertEquals(80, MedtrumIntKey.MedtrumDailyMaxInsulin.defaultValue)
        assertEquals("pump_expiry_warning_hour", MedtrumIntKey.MedtrumPumpExpiryWarningHours.key)
        assertEquals(72, MedtrumIntKey.MedtrumPumpExpiryWarningHours.defaultValue)
        assertEquals("patch_expiration", MedtrumBooleanKey.MedtrumPatchExpiration.key)
        assertTrue(MedtrumBooleanKey.MedtrumPatchExpiration.defaultValue)
        assertEquals("pump_warning_notification", MedtrumBooleanKey.MedtrumWarningNotification.key)
        assertTrue(MedtrumBooleanKey.MedtrumWarningNotification.defaultValue)
        assertEquals("scan_on_connection_error", MedtrumBooleanKey.MedtrumScanOnConnectionErrors.key)
        assertFalse(MedtrumBooleanKey.MedtrumScanOnConnectionErrors.defaultValue)
    }

    @Test
    fun `untouched preferences give the dev defaults`() {
        val settings = MedtrumUserSettings.from(FakeMedtrumPreferences())

        assertEquals(
            MedtrumUserSettings(
                patchExpiration = true,
                alarmSetting = AlarmSetting.BEEP_ONLY,
                hourlyMaxInsulin = 25,
                dailyMaxInsulin = 80,
                pumpWarning = true,
                pumpWarningExpiryThresholdHours = 72
            ),
            settings
        )
    }

    @Test
    fun `every preference goes to its own setting`() {
        // All values differ from the defaults and from each other, so a swapped key shows.
        val preferences = FakeMedtrumPreferences().apply {
            put(MedtrumStringKey.MedtrumAlarmSettings, "7")
            put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 13)
            put(MedtrumIntKey.MedtrumDailyMaxInsulin, 57)
            put(MedtrumBooleanKey.MedtrumPatchExpiration, false)
            put(MedtrumBooleanKey.MedtrumWarningNotification, false)
            put(MedtrumIntKey.MedtrumPumpExpiryWarningHours, 50)
        }

        val settings = MedtrumUserSettings.from(preferences)

        assertEquals(AlarmSetting.NONE, settings.alarmSetting)
        assertEquals(13, settings.hourlyMaxInsulin)
        assertEquals(57, settings.dailyMaxInsulin)
        assertFalse(settings.patchExpiration)
        assertFalse(settings.pumpWarning)
        assertEquals(50, settings.pumpWarningExpiryThresholdHours)
    }

    @Test
    fun `the settings land in the state the patch commands read`() {
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(1_700_000_000_000L))
        val preferences = FakeMedtrumPreferences().apply {
            put(MedtrumStringKey.MedtrumAlarmSettings, "7")
            put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 13)
            put(MedtrumIntKey.MedtrumDailyMaxInsulin, 57)
            put(MedtrumBooleanKey.MedtrumPatchExpiration, false)
            put(MedtrumBooleanKey.MedtrumWarningNotification, false)
            put(MedtrumIntKey.MedtrumPumpExpiryWarningHours, 50)
        }

        MedtrumUserSettings.from(preferences).applyTo(state)

        assertEquals(AlarmSetting.NONE, state.desiredAlarmSetting)
        assertEquals(13, state.desiredHourlyMaxInsulin)
        assertEquals(57, state.desiredDailyMaxInsulin)
        assertFalse(state.desiredPatchExpiration)
        assertFalse(state.desiredPumpWarning)
        assertEquals(50, state.desiredPumpWarningExpiryThresholdHours)
    }

    @Test
    fun `an unknown alarm code gives light vibrate and beep like on Android`() {
        assertEquals(AlarmSetting.BEEP_ONLY, MedtrumUserSettings.alarmSettingFromCode("6"))
        assertEquals(AlarmSetting.LIGHT_VIBRATE_AND_BEEP, MedtrumUserSettings.alarmSettingFromCode("9"))
        assertEquals(AlarmSetting.LIGHT_VIBRATE_AND_BEEP, MedtrumUserSettings.alarmSettingFromCode("beep"))
    }

    @Test
    fun `a Nano lowers maximums above its limits`() {
        val preferences = FakeMedtrumPreferences().apply {
            put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 60)
            put(MedtrumIntKey.MedtrumDailyMaxInsulin, 270)
        }

        MedtrumInsulinLimits.applyFor(PumpType.MEDTRUM_NANO, preferences)

        assertEquals(40, MedtrumIntKey.MedtrumHourlyMaxInsulin.max)
        assertEquals(180, MedtrumIntKey.MedtrumDailyMaxInsulin.max)
        assertEquals(40, preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin))
        assertEquals(180, preferences.get(MedtrumIntKey.MedtrumDailyMaxInsulin))
    }

    @Test
    fun `a 300U allows higher maximums and keeps the values`() {
        val preferences = FakeMedtrumPreferences().apply {
            put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 60)
            put(MedtrumIntKey.MedtrumDailyMaxInsulin, 270)
        }

        MedtrumInsulinLimits.applyFor(PumpType.MEDTRUM_300U, preferences)

        assertEquals(60, MedtrumIntKey.MedtrumHourlyMaxInsulin.max)
        assertEquals(270, MedtrumIntKey.MedtrumDailyMaxInsulin.max)
        assertEquals(60, preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin))
        assertEquals(270, preferences.get(MedtrumIntKey.MedtrumDailyMaxInsulin))
    }

    @Test
    fun `the Nano and the 300U offer only beep and silent`() {
        assertEquals(listOf("6", "7"), MedtrumInsulinLimits.alarmEntries(PumpType.MEDTRUM_NANO).keys.toList())
        assertEquals(listOf("6", "7"), MedtrumInsulinLimits.alarmEntries(PumpType.MEDTRUM_300U).keys.toList())
        assertEquals(
            listOf("0", "1", "2", "3", "4", "5", "6", "7"),
            MedtrumInsulinLimits.alarmEntries(PumpType.MEDTRUM_UNTESTED).keys.toList()
        )
    }

    @Test
    fun `a changed patch setting is sent to the patch and a warning setting only reloads`() = runTest {
        val preferences = FakeMedtrumPreferences()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        var reloads = 0
        var sends = 0
        watchMedtrumUserSettings(preferences, scope, QuietLogger, reload = { reloads++ }, sendToPatch = { sends++ })
        runCurrent()

        // The values at the start are not changes.
        assertEquals(0, reloads)
        assertEquals(0, sends)

        preferences.put(MedtrumIntKey.MedtrumHourlyMaxInsulin, 30)
        preferences.put(MedtrumIntKey.MedtrumDailyMaxInsulin, 120)
        preferences.put(MedtrumStringKey.MedtrumAlarmSettings, "7")
        preferences.put(MedtrumBooleanKey.MedtrumPatchExpiration, false)
        runCurrent()
        assertEquals(4, reloads)
        assertEquals(4, sends)

        preferences.put(MedtrumBooleanKey.MedtrumWarningNotification, false)
        preferences.put(MedtrumIntKey.MedtrumPumpExpiryWarningHours, 60)
        runCurrent()
        assertEquals(6, reloads)
        assertEquals(4, sends, "warning settings are not sent to the patch")

        scope.cancel()
    }
}
