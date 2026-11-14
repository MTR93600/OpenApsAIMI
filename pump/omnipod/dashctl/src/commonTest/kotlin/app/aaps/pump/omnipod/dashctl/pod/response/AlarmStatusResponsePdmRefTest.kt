package app.aaps.pump.omnipod.dashctl.pod.response

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.PdmFaultCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AlarmStatusResponse.pdmRef] and [AlarmType.pdmFaultCategory], with the vectors of Android
 * `AlarmStatusResponseTest` (`:pump:omnipod:dash`), unchanged. Common code, so it runs on the JVM and on
 * the iOS simulator.
 */
class AlarmStatusResponsePdmRefTest {

    @Test
    fun `the PDM reference uses the alarm time when it is valid`() {
        assertEquals("19-00003-02251-062", createResponseWithTimes(alarmTime = 180, minutesSinceActivation = 900).pdmRef)
    }

    @Test
    fun `the PDM reference falls back to the time since activation when the alarm time is 0`() {
        assertEquals("19-00015-02251-062", createResponseWithTimes(alarmTime = 0, minutesSinceActivation = 900).pdmRef)
    }

    @Test
    fun `the PDM reference falls back to the time since activation when the alarm time is FFFF`() {
        assertEquals("19-00015-02251-062", createResponseWithTimes(alarmTime = 0xffff, minutesSinceActivation = 900).pdmRef)
    }

    @Test
    fun `there is no PDM reference without a fault`() {
        val response = createResponse(AlarmType.NONE.value.toInt() and 0xff)

        assertEquals(AlarmType.NONE, response.alarmType)
        assertNull(response.pdmRef)
    }

    @Test
    fun `an unknown fault code gives no PDM reference`() {
        // 0xb0 falls between ALARM_BLE_REQ_STUCK_HIGH (0xaf) and ALARM_BLE_STATE_MACHINE_1 (0xb1).
        val response = createResponse(0xb0)

        assertEquals(AlarmType.UNKNOWN, response.alarmType)
        assertNull(response.pdmRef)
    }

    @Test
    fun `an empty reservoir has category 14`() {
        assertEquals("14-00003-02251-024", createResponse(AlarmType.ALARM_EMPTY_RESERVOIR.value.toInt() and 0xff).pdmRef)
    }

    @Test
    fun `an alert alarm has the auto off category 15`() {
        assertEquals("15-00003-02251-041", createResponse(AlarmType.ALARM_ALERT0.value.toInt() and 0xff).pdmRef)
    }

    @Test
    fun `an expired pod has category 16`() {
        assertEquals("16-00003-02251-028", createResponse(AlarmType.ALARM_PUMP_EXPIRED.value.toInt() and 0xff).pdmRef)
    }

    @Test
    fun `an occlusion has category 17`() {
        assertEquals("17-00003-02251-020", createResponse(AlarmType.ALARM_OCCLUDED.value.toInt() and 0xff).pdmRef)
    }

    @Test
    fun `the fault categories are the Android ones`() {
        assertNull(AlarmType.NONE.pdmFaultCategory)
        assertNull(AlarmType.UNKNOWN.pdmFaultCategory)
        assertEquals(PdmFaultCategory.RESERVOIR_EMPTY, AlarmType.ALARM_EMPTY_RESERVOIR.pdmFaultCategory)
        assertEquals(PdmFaultCategory.AUTO_OFF, AlarmType.ALARM_ALERT7.pdmFaultCategory)
        assertEquals(PdmFaultCategory.POD_EXPIRED, AlarmType.ALARM_PUMP_EXPIRED.pdmFaultCategory)
        assertEquals(PdmFaultCategory.OCCLUDED, AlarmType.ALARM_OCCLUDED.pdmFaultCategory)
        assertEquals(PdmFaultCategory.POD_ERROR, AlarmType.ALARM_FLASH_FAILURE.pdmFaultCategory)
        assertEquals(listOf(14, 15, 16, 17, 19), PdmFaultCategory.entries.map { it.tt })
    }

    @Test
    fun `the fault codes added after the first dashctl port are known as in Android`() {
        // 0x63 was ALARM_SPARE99 in the first port; Android names it, and 0xcb was UNKNOWN.
        assertEquals(AlarmType.ALARM_OCCL_PARAM_INVALID, createResponse(0x63).alarmType)
        assertEquals(AlarmType.ALARM_UNKNOWN_CB, createResponse(0xcb).alarmType)
        assertEquals(0xcb, AlarmType.ALARM_UNKNOWN_CB.code)
    }

    private fun createResponseWithTimes(alarmTime: Int, minutesSinceActivation: Int): AlarmStatusResponse =
        createResponse(AlarmType.ALARM_FLASH_FAILURE.value.toInt() and 0xff, alarmTime, minutesSinceActivation)

    private fun createResponse(alarmTypeByteValue: Int, alarmTime: Int = 180, minutesSinceActivation: Int = 900): AlarmStatusResponse {
        val encoded = parityHex("021602080100000501BD00000003FF01950000000000670A")
        encoded[10] = alarmTypeByteValue.toByte()
        encoded[11] = (alarmTime shr 8).toByte()
        encoded[12] = alarmTime.toByte()
        encoded[15] = (minutesSinceActivation shr 8).toByte()
        encoded[16] = minutesSinceActivation.toByte()
        return AlarmStatusResponse(encoded)
    }
}
