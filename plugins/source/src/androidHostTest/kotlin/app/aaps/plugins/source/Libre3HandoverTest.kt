package app.aaps.plugins.source

import app.aaps.plugins.libre3.identity.Libre3SensorIdentity
import app.aaps.plugins.libre3.identity.Libre3SessionKeys
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Libre3HandoverTest {

    private val identity = Libre3SensorIdentity(
        serialNumber = "0D00000ABC",
        bleAddress = "11:22:33:44:55:66",
        blePin = byteArrayOf(1, 2, 3, 4),
        receiverId = 123_456_789,
        generation = 1,
        warmupMinutes = 60,
        wearDurationMinutes = 21_600,
        activatedAtMs = 1_700_000_000_000L,
    )
    private val keys = Libre3SessionKeys(phase5RawKey = ByteArray(32) { 7 }, kEnc = ByteArray(16) { 8 }, ivEnc = ByteArray(8) { 9 })

    @Test
    fun `the next phone gets the same sensor, receiver id and keys`() {
        val (sensor, sessionKeys) = Libre3Handover.parse(Libre3Handover.export(identity, keys))!!

        assertEquals(identity, sensor)
        assertEquals(123_456_789, sensor.receiverId)
        assertArrayEquals(keys.phase5RawKey, sessionKeys.phase5RawKey)
        assertArrayEquals(keys.kEnc, sessionKeys.kEnc)
        assertArrayEquals(keys.ivEnc, sessionKeys.ivEnc)
    }

    @Test
    fun `keys the sensor did not have yet stay absent`() {
        val (_, sessionKeys) = Libre3Handover.parse(Libre3Handover.export(identity, Libre3SessionKeys(null, null, null)))!!

        assertNull(sessionKeys.phase5RawKey)
        assertNull(sessionKeys.kEnc)
    }

    @Test
    fun `a missing address or receiver id means no sensor`() {
        assertNull(Libre3Handover.parse(Libre3Handover.export(identity, keys) - "address"))
        assertNull(Libre3Handover.parse(Libre3Handover.export(identity, keys) - "receiverId"))
    }
}
