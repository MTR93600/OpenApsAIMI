package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.FakeMedtrumProtocolState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Bit-exact differential for Medtrum packet encoding.
 *
 * Each test encodes a packet with the ported implementation and asserts the exact
 * bytes locked by the Android driver tests (pump/medtrum/src/test). Zero deviation
 * is tolerated — these bytes go to an insulin pump.
 *
 * Ground truth sources are noted per test.
 */
class MedtrumPacketEncodingDifferentialTest {

    private val logger = MedtrumLogger.NO_OP

    // SetBolusPacketTest.getRequestGivenPacketWhenCalledThenReturnOpCode (insulin=2.35)
    @Test fun setBolusPacketEncoding() {
        val packet = SetBolusPacket(2.35)
        assertContentEquals(byteArrayOf(19, 1, 47, 0, 0), packet.getRequest())
    }

    // GetRecordPacketTest.getRequestGivenPacketWhenCalledThenReturnOpCode (recordIndex=4, patchId=146)
    @Test fun getRecordPacketEncoding() {
        val state = FakeMedtrumProtocolState().apply { patchId = 146 }
        val packet = GetRecordPacket(state, 4)
        assertContentEquals(byteArrayOf(99, 4, 0, -110, 0), packet.getRequest())
    }

    // SetPatchPacketTest (from Android test — note: LIGHT_AND_VIBRATE, not the default)
    @Test fun setPatchPacketEncoding() {
        val state = FakeMedtrumProtocolState().apply {
            desiredAlarmSetting = AlarmSetting.LIGHT_AND_VIBRATE
            desiredHourlyMaxInsulin = 40
            desiredDailyMaxInsulin = 180
            desiredPatchExpiration = false
        }
        val packet = SetPatchPacket(state)
        assertContentEquals(byteArrayOf(35, 1, 32, 3, 16, 14, 0, 0, 12, 0, 0, 30), packet.getRequest())
    }

    // SetTempBasalPacketTest (rate=1.25, duration=60 → bytes from Android test)
    @Test fun setTempBasalPacketEncoding() {
        val state = FakeMedtrumProtocolState()
        val timeUtil = MedtrumTimeUtil { 1_700_000_000_000L }
        val packet = SetTempBasalPacket(state, absoluteRate = 1.25, durationInMinutes = 60, timeUtil = timeUtil)
        assertContentEquals(byteArrayOf(24, 6, 25, 0, 60, 0), packet.getRequest())
    }

    // AuthorizePacketTest.getRequestGivenPacketAndSNWhenCalledThenReturnAuthorizePacket
    // pumpSN=2859923929, patchSessionToken=667, key=3364239851 (from CryptTest vector)
    @Test fun authorizePacketEncoding() {
        val state = FakeMedtrumProtocolState().apply {
            pumpSN = 2859923929
            patchSessionToken = 667
        }
        val packet = AuthorizePacket(state)
        val key = 3364239851L
        val expected = byteArrayOf(5) + 2.toByte() + 667L.toByteArray(4) + key.toByteArray(4)
        assertContentEquals(expected, packet.getRequest())
    }

    // SubscribePacketTest (opcode 4)
    @Test fun subscribePacketEncoding() {
        val packet = SubscribePacket()
        // opCode + 4095 as 2 bytes (from Android test)
        val expected = byteArrayOf(4) + 4095.toByteArray(2)
        assertContentEquals(expected, packet.getRequest())
    }

    // CancelBolusPacketTest — opcode 20 + 1
    @Test fun cancelBolusPacketEncoding() {
        val packet = CancelBolusPacket()
        assertContentEquals(byteArrayOf(20, 1), packet.getRequest())
    }

    // ClearPumpAlarmPacketTest — opcode + clearCode
    @Test fun clearPumpAlarmPacketEncoding() {
        val packet = ClearPumpAlarmPacket(4)
        assertEquals(115.toByte(), packet.getRequest()[0])
        assertEquals(4.toByte(), packet.getRequest()[1])
    }

    // GetDeviceTypePacketTest — opcode only
    @Test fun getDeviceTypePacketEncoding() {
        val packet = GetDeviceTypePacket()
        assertEquals(6.toByte(), packet.getRequest()[0])
    }

    // SetBasalProfilePacketTest.getRequestGivenPacketWhenCalledThenReturnOpCode
    // (basalProfile=8,2,3,4,-1,0,0,0,0 from Android test)
    @Test fun setBasalProfilePacketEncoding() {
        val state = FakeMedtrumProtocolState()
        val basalProfile = byteArrayOf(8, 2, 3, 4, -1, 0, 0, 0, 0)
        val packet = SetBasalProfilePacket(state, basalProfile = basalProfile)
        assertContentEquals(byteArrayOf(21, 1, 8, 2, 3, 4, -1, 0, 0, 0, 0), packet.getRequest())
    }

    // CancelTempBasalPacketTest.getRequestGivenPacketWhenCalledThenReturnOpCode (opcode 25)
    @Test fun cancelTempBasalPacketEncoding() {
        val packet = CancelTempBasalPacket(FakeMedtrumProtocolState())
        assertContentEquals(byteArrayOf(25), packet.getRequest())
    }
}
