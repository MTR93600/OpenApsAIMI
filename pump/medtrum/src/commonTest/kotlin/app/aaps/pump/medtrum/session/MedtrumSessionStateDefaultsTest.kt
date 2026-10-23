package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings a new patch gets must be the Android defaults. The iPhone has no screen for them, so
 * these values are what an iPhone sends at activation.
 */
class MedtrumSessionStateDefaultsTest {

    private val state = MedtrumSessionState(clock = MedtrumClock.fixed(1_700_000_000_000L))

    @Test
    fun `the patch settings are the Android defaults`() {
        // Android `MedtrumBooleanKey.MedtrumPatchExpiration` is on by default.
        assertTrue(state.desiredPatchExpiration)
        // Android `MedtrumStringKey.MedtrumAlarmSettings` is "6". The Nano offers only beep and silent.
        assertEquals(AlarmSetting.BEEP_ONLY, state.desiredAlarmSetting)
        assertEquals(6.toByte(), state.desiredAlarmSetting.code)
        // Android `MedtrumIntKey.MedtrumHourlyMaxInsulin` and `MedtrumDailyMaxInsulin`.
        assertEquals(25, state.desiredHourlyMaxInsulin)
        assertEquals(80, state.desiredDailyMaxInsulin)
    }

    @Test
    fun `the warning settings are the Android defaults`() {
        // Android `MedtrumBooleanKey.MedtrumWarningNotification` and `MedtrumIntKey.MedtrumPumpExpiryWarningHours`.
        assertTrue(state.desiredPumpWarning)
        assertEquals(72, state.desiredPumpWarningExpiryThresholdHours)
    }

    @Test
    fun `the temp basal rate is zero without a running temp basal`() {
        state.lastBasalRate = 1.5
        assertEquals(0.0, state.tempBasalAbsoluteRate)
    }
}
