package app.aaps.pump.medtrum.ios

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.medtrum.comm.RecordingPumpSync
import app.aaps.pump.medtrum.comm.FakeDetailedBolusInfoStorage
import app.aaps.pump.medtrum.comm.FakeTemporaryBasalStorage
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.enums.CommandType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.session.FakeMedtrumBleTransport
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSessionState
import app.aaps.pump.medtrum.session.errorResponse
import app.aaps.pump.medtrum.session.successResponse
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full round trip of a bolus in the history of AAPS.
 *
 * When a bolus starts, the driver writes a temporary bolus with the amount that was asked for. When
 * the bolus is over, the driver reads the records of the pump. The record carries the amount that was
 * really delivered, and it replaces the temporary bolus. These tests run the real session and the real
 * `GetRecordPacket` with scripted record bytes, and check the exact `PumpSync` calls.
 */
class IosMedtrumBolusHistoryTest {

    private val nowMs = 1_700_000_000_000L
    private val serial = "12345678"

    /** A clock that can run fast: each read moves time forward by [jumpMs]. Used to skip the 20 s waits. */
    private class JumpClock(val base: Long) : MedtrumClock {

        var jumpMs = 0L
        private var elapsed = 0L
        override fun nowMillis(): Long {
            val now = base + elapsed
            elapsed += jumpMs
            return now
        }

        override fun utcOffsetMinutes(millis: Long): Int = 120
        fun reset() { jumpMs = 0L; elapsed = 0L }
    }

    private class Rig(
        val fake: FakeMedtrumBleTransport,
        val state: MedtrumSessionState,
        val pump: IosMedtrumPump,
        val sync: RecordingPumpSync,
        val infos: FakeDetailedBolusInfoStorage,
        val clock: JumpClock,
    )

    private fun CoroutineScope.newRig(): Rig {
        val clock = JumpClock(nowMs)
        val fake = FakeMedtrumBleTransport()
        val state = MedtrumSessionState(MedtrumLogger.NO_OP, clock).apply {
            pumpSN = 0x12345678L
            deviceType = 80
            patchId = 7
            patchSessionToken = 0xABCDEF01L
            pumpState = MedtrumPumpState.ACTIVE
            // The pump already runs on the phone's time zone, so a connect sends no time zone update.
            pumpTimeZoneOffset = 120
        }
        val sync = RecordingPumpSync()
        val infos = FakeDetailedBolusInfoStorage()
        val pump = IosMedtrumPump(
            fake, state, clock, TestTextResolver,
            pumpSync = sync, detailedBolusInfoStorage = infos, temporaryBasalStorage = FakeTemporaryBasalStorage(),
            scope = this, store = null, logger = MedtrumLogger.NO_OP
        )
        return Rig(fake, state, pump, sync, infos, clock)
    }

    private fun scriptAuthSuccess(fake: FakeMedtrumBleTransport) {
        val pumpSeconds = ((nowMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000).toInt()
        val pumpTimeBytes = byteArrayOf(
            pumpSeconds.toByte(), (pumpSeconds shr 8).toByte(),
            (pumpSeconds shr 16).toByte(), (pumpSeconds shr 24).toByte()
        )
        fake.responses[CommandType.AUTH_REQ.code] = successResponse(CommandType.AUTH_REQ.code, 0, 0, 1, 2, 3)
        fake.responses[CommandType.GET_DEVICE_TYPE.code] = successResponse(CommandType.GET_DEVICE_TYPE.code, 1, 0x12, 0x34, 0x56, 0x78)
        fake.responses[CommandType.GET_TIME.code] = successResponse(CommandType.GET_TIME.code) + pumpTimeBytes
        fake.responses[CommandType.SYNCHRONIZE.code] = successResponse(CommandType.SYNCHRONIZE.code, 32, 0, 0, 0)
        fake.responses[CommandType.SUBSCRIBE.code] = successResponse(CommandType.SUBSCRIBE.code)
    }

    private suspend fun waitUntil(maxMs: Long = 5_000, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition() && waited < maxMs) {
            delay(20)
            waited += 20
        }
        return condition()
    }

    private suspend fun CoroutineScope.readyRig(): Rig {
        val rig = newRig()
        scriptAuthSuccess(rig.fake)
        rig.pump.connect("test")
        assertTrue(waitUntil { rig.pump.isReady() }, "pump should reach READY")
        rig.state.deviceType = 80
        return rig
    }

    private fun Rig.sent(opcode: CommandType): Boolean = fake.sentMessages.any { it[0] == opcode.code }
    private fun Rig.sentCount(opcode: CommandType): Int = fake.sentMessages.count { it[0] == opcode.code }

    // -- Record bytes, same layout as MedtrumPumpSyncHistoryTest -----------------------------------

    private fun le(value: Long, size: Int): ByteArray = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }

    /** One normal bolus record: the bolus started at [startMs], [askedRaw] and [deliveredRaw] are in 0.05 U steps. */
    private fun bolusRecordFrame(sequence: Int, startMs: Long, askedRaw: Int, deliveredRaw: Int): ByteArray {
        val pumpSecs = (startMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000
        val data = byteArrayOf(1, 0, 0, 0) + le(pumpSecs, 4) + le(askedRaw.toLong(), 2) + le(deliveredRaw.toLong(), 2) +
            le(0, 2) + le(0, 2) + le(0, 2) + ByteArray(10)
        return successResponse(CommandType.GET_RECORD.code) +
            byteArrayOf(170.toByte(), 0, 1, 0) + le(0x12345678L, 4) + le(7, 2) + le(sequence.toLong(), 2) + data
    }

    private fun bolusInfo(units: Double) = DetailedBolusInfo().apply { insulin = units; bolusType = BS.Type.NORMAL }

    private fun add(units: Double) =
        "addBolusWithTempId(timestamp=$nowMs, amount=$units, temporaryId=$nowMs, type=NORMAL, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"

    private fun replaced(units: Double) =
        "syncBolusWithTempId(timestamp=$nowMs, amount=$units, temporaryId=$nowMs, type=NORMAL, pumpId=$nowMs, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"

    // -- The round trip -------------------------------------------------------------------------------

    @Test
    fun bolusAskedAt1UnitAndStoppedAt04UnitEndsWithARecordOf04Unit() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.CANCEL_BOLUS.code] = successResponse(CommandType.CANCEL_BOLUS.code)
        // The pump wrote one record: asked 1.0 U (20 steps), delivered 0.4 U (8 steps).
        rig.fake.responses[CommandType.GET_RECORD.code] = bolusRecordFrame(1, nowMs, askedRaw = 20, deliveredRaw = 8)

        val delivery = async { rig.pump.deliverTreatment(bolusInfo(1.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        rig.state.handleBolusStatusUpdate(1, false, 0.4)
        // The pump tells that it has one new record.
        rig.state.currentSequenceNumber = 1
        rig.pump.stopBolusDelivering()
        assertTrue(waitUntil { rig.sent(CommandType.CANCEL_BOLUS) }, "stop must reach the pump")
        val result = delivery.await()

        assertTrue(result.success, "a stopped bolus is a success")
        assertEquals(0.4, result.bolusDelivered)
        assertEquals(1, rig.sentCount(CommandType.GET_RECORD), "the records are read after the bolus")
        // The temporary bolus (1.0 U) is replaced by the record (0.4 U). No second record is written.
        assertEquals(listOf(add(1.0), replaced(8 * 0.05)), rig.sync.calls)
        assertTrue(rig.infos.infos.isEmpty(), "the stored info was used by the record")
    }

    @Test
    fun fullyDeliveredBolusIsReplacedByTheRecordWithoutASecondRecord() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.GET_RECORD.code] = bolusRecordFrame(1, nowMs, askedRaw = 20, deliveredRaw = 20)

        val delivery = async { rig.pump.deliverTreatment(bolusInfo(1.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        rig.state.currentSequenceNumber = 1
        rig.state.handleBolusStatusUpdate(1, true, 1.0)
        val result = delivery.await()

        assertTrue(result.success)
        assertEquals(listOf(add(1.0), replaced(1.0)), rig.sync.calls)
    }

    @Test
    fun stoppedBeforeAnyInsulinKeepsTheZeroUnitRecordAndReadsNoMoreRecords() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.CANCEL_BOLUS.code] = successResponse(CommandType.CANCEL_BOLUS.code)

        val delivery = async { rig.pump.deliverTreatment(bolusInfo(1.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        rig.pump.stopBolusDelivering()
        assertTrue(waitUntil { rig.sent(CommandType.CANCEL_BOLUS) })
        delivery.await()

        // The pump has no new record, so the zero record from the stop stays the only one.
        assertEquals(listOf(add(1.0), replaced(0.0)), rig.sync.calls)
        assertEquals(0, rig.sentCount(CommandType.GET_RECORD))
    }

    @Test
    fun sendFailureStillReadsTheRecordsBecauseTheBolusMayHaveStarted() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        // The pump did not answer well, but it did write a record of 0.4 U.
        rig.fake.responses[CommandType.SET_BOLUS.code] = errorResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.GET_RECORD.code] = bolusRecordFrame(1, nowMs, askedRaw = 20, deliveredRaw = 8)
        rig.state.currentSequenceNumber = 1

        val result = rig.pump.deliverTreatment(bolusInfo(1.0))

        assertFalse(result.success)
        assertEquals(1, rig.sentCount(CommandType.GET_RECORD))
        // No temporary bolus was written, so the record is a new one, found by its pump id.
        assertEquals(
            listOf("syncBolusWithPumpId(timestamp=$nowMs, amount=${8 * 0.05}, type=null, pumpId=$nowMs, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            rig.sync.calls
        )
    }

    @Test
    fun communicationLostMidBolusIsFixedWhenTheStatusIsReadAfterReconnect() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.GET_RECORD.code] = bolusRecordFrame(1, nowMs, askedRaw = 20, deliveredRaw = 8)

        val delivery = async { rig.pump.deliverTreatment(bolusInfo(1.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) && rig.sync.calls.size == 1 })
        rig.state.handleBolusStatusUpdate(1, false, 0.4)
        rig.state.currentSequenceNumber = 1
        // From now on the clock runs fast, so the pump looks silent for minutes.
        rig.clock.jumpMs = 2_000
        val result = delivery.await()
        rig.clock.reset()

        assertFalse(result.success, "0.4 U of 1.0 U is not a success")
        assertFalse(rig.pump.isReady(), "the session dropped the link")
        // The link is down, so the records cannot be read now. The temporary bolus is still there.
        assertEquals(listOf(add(1.0)), rig.sync.calls)

        // The loop asks for the status after the link is back. This reads the records.
        scriptAuthSuccess(rig.fake)
        rig.pump.connect("reconnect")
        assertTrue(waitUntil { rig.pump.isReady() })
        // The scripted auth answer gives an unknown device type. A real Nano reports 80.
        rig.state.deviceType = 80
        rig.pump.getPumpStatus("after reconnect")

        assertEquals(listOf(add(1.0), replaced(8 * 0.05)), rig.sync.calls)
    }

    @Test
    fun getPumpStatusDoesNothingWhenThePumpIsNotReady() = runBlocking {
        val rig = newRig()
        rig.state.currentSequenceNumber = 1
        rig.pump.getPumpStatus("test")
        assertTrue(rig.fake.sentMessages.isEmpty())
        assertTrue(rig.sync.calls.isEmpty())
    }
}
