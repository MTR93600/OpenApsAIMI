package app.aaps.pump.medtrum

import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MedtrumHandoverTest {

    private fun newState() = MedtrumSessionState(clock = MedtrumClock.fixed(1_700_000_000_000L))

    private fun pairedState() = newState().apply {
        pumpSN = 0xA1B2C3D4L
        deviceType = 80
        swVersion = "1.2.3"
        patchId = 42L
        patchSessionToken = 0x12345678L
        patchStartTime = 1_699_000_000_000L
        currentSequenceNumber = 731
        syncedSequenceNumber = 729
        actualBasalProfile = byteArrayOf(0x01, 0x7F, -0x80, 0x00)
        pumpTimeZoneOffset = 60
        pumpState = MedtrumPumpState.ACTIVE
        lastBolusTime = 1_699_999_000_000L
        lastBolusAmount = 1.25
    }

    @Test
    fun `the next phone gets the same patch as the first one`() {
        val fields = MedtrumHandover.export(pairedState())!!
        val next = newState()

        val serial = MedtrumHandover.import(fields, next)

        assertEquals("A1B2C3D4", serial)
        assertEquals(0xA1B2C3D4L, next.pumpSN)
        assertEquals(80, next.deviceType)
        assertEquals("1.2.3", next.swVersion)
        assertEquals(42L, next.patchId)
        assertEquals(0x12345678L, next.patchSessionToken)
        assertEquals(1_699_000_000_000L, next.patchStartTime)
        assertEquals(731, next.currentSequenceNumber)
        assertEquals(729, next.syncedSequenceNumber)
        assertContentEquals(byteArrayOf(0x01, 0x7F, -0x80, 0x00), next.actualBasalProfile)
        assertEquals(60, next.pumpTimeZoneOffset)
        assertEquals(MedtrumPumpState.ACTIVE, next.pumpState)
        assertEquals(1_699_999_000_000L, next.lastBolusTime)
        assertEquals(1.25, next.lastBolusAmount)
    }

    @Test
    fun `no pump paired means nothing to hand over`() {
        assertNull(MedtrumHandover.export(newState()))
    }

    @Test
    fun `a damaged field changes nothing`() {
        val fields = MedtrumHandover.export(pairedState())!! + ("sessionToken" to "not a number")
        val next = newState()

        assertNull(MedtrumHandover.import(fields, next))
        assertEquals(0L, next.pumpSN)
        assertEquals(0L, next.patchSessionToken)
    }

    @Test
    fun `a missing serial changes nothing`() {
        val fields = MedtrumHandover.export(pairedState())!! - "serial"
        val next = newState()

        assertNull(MedtrumHandover.import(fields, next))
        assertEquals(0L, next.pumpSN)
    }
}
