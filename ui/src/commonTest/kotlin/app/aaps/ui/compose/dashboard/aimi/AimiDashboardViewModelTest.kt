package app.aaps.ui.compose.dashboard.aimi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AimiDashboardViewModelTest {

    @Test
    fun client_detailsShowMasterTargetSmbBasal() {
        val numbers = AimiDashboardRules.adjustmentNumbers(
            hasRequest = true,
            targetBg = 80.0,
            smb = 0.2,
            rate = 1.1,
            peakMinutes = null,
            diaHours = null,
        )

        assertEquals(80.0, numbers.targetMgdl)
        assertEquals(0.2, numbers.smbUnits)
        assertEquals(1.1, numbers.basalUh)
    }

    @Test
    fun client_diaPeakStayDash() {
        val numbers = AimiDashboardRules.adjustmentNumbers(
            hasRequest = true,
            targetBg = 80.0,
            smb = 0.2,
            rate = 1.1,
            peakMinutes = null,
            diaHours = null,
        )

        assertNull(numbers.peakMinutes)
        assertNull(numbers.diaHours)
    }

    @Test
    fun client_autosensNotFake100() {
        assertNull(AimiDashboardRules.autosensRatio(isClient = true, requestRatio = null, sensitivityRatio = null))
        assertEquals(0.9, AimiDashboardRules.autosensRatio(isClient = true, requestRatio = null, sensitivityRatio = 0.9))
        assertNull(AimiDashboardRules.autosensRatio(isClient = true, requestRatio = 1.0, sensitivityRatio = Double.NaN))
    }

    @Test
    fun master_detailsUnchanged() {
        assertEquals(1.0, AimiDashboardRules.autosensRatio(isClient = false, requestRatio = null, sensitivityRatio = 0.4))
        assertEquals(0.8, AimiDashboardRules.autosensRatio(isClient = false, requestRatio = 0.8, sensitivityRatio = 0.4))
        assertEquals(1_000L, AimiDashboardRules.pulseAtMs(isClient = false, localLastApsRun = 1_000L, openApsTimestamp = 9_000L))
        val numbers = AimiDashboardRules.adjustmentNumbers(
            hasRequest = true,
            targetBg = 85.0,
            smb = 0.0,
            rate = 0.6,
            peakMinutes = 55.0,
            diaHours = 6.0,
        )
        assertEquals(85.0, numbers.targetMgdl)
        assertEquals(0.0, numbers.smbUnits)
        assertEquals(0.6, numbers.basalUh)
        assertEquals(55.0, numbers.peakMinutes)
        assertEquals(6.0, numbers.diaHours)
    }

    @Test
    fun client_pulseUsesDeviceStatusTime() {
        assertEquals(8_000L, AimiDashboardRules.pulseAtMs(isClient = true, localLastApsRun = null, openApsTimestamp = 8_000L))
        assertNull(AimiDashboardRules.pulseAtMs(isClient = true, localLastApsRun = 1_000L, openApsTimestamp = -1L))
    }
}
