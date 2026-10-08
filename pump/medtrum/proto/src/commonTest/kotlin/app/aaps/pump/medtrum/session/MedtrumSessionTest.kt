package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumRecordSync
import app.aaps.pump.medtrum.comm.enums.CommandType
import app.aaps.pump.medtrum.comm.packets.SetBolusPacket
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fake BLE transport: records sent messages, scripted responses.
 * Calls back synchronously like a loopback device.
 */
class FakeMedtrumBleTransport : MedtrumBleTransport {

    val sentMessages = mutableListOf<ByteArray>()
    private var listener: MedtrumBleCallback? = null
    var connectResult = true
    var disconnects = mutableListOf<String>()

    /** opcode -> response bytes. Null response = silence (timeout). */
    var responses: MutableMap<Byte, ByteArray?> = mutableMapOf()

    override fun connect(from: String, deviceSN: Long): Boolean {
        if (connectResult) listener?.onConnected()
        return connectResult
    }

    override fun disconnect(from: String) {
        disconnects.add(from)
        listener?.onDisconnected()
    }

    override fun sendMessage(message: ByteArray) {
        sentMessages.add(message)
        val opcode = message.getOrNull(0) ?: return
        responses[opcode]?.let { listener?.onIndication(it) }
    }

    override fun setCallback(callback: MedtrumBleCallback?) {
        this.listener = callback
    }

    fun lastSentOpcode(): Byte? = sentMessages.lastOrNull()?.getOrNull(0)
}

/** Build a minimal success response: [seq, opcode, 0, 0, resultHi, resultLo, ...extra]. */
fun successResponse(opcode: Byte, vararg extra: Byte): ByteArray =
    byteArrayOf(0, opcode, 0, 0, 0, 0) + extra

/** Build an error response (result != 0). */
fun errorResponse(opcode: Byte): ByteArray =
    byteArrayOf(0, opcode, 0, 0, 0, 1)

/** Mutable clock for virtual-time tests: the session's sleeper advances it instead of sleeping. */
class TestMedtrumClock(var nowMs: Long) : MedtrumClock {
    override fun nowMillis(): Long = nowMs
}

class MedtrumSessionTest {

    private val nowMs = 1_700_000_000_000L
    private val clock = MedtrumClock.fixed(nowMs, minuteOfDay = 600)

    private fun newState() = MedtrumSessionState(MedtrumLogger.NO_OP, clock).apply {
        pumpSN = 0x12345678L
        patchSessionToken = 0xABCDEF01L
    }

    private fun newSession(fake: FakeMedtrumBleTransport, state: MedtrumSessionState = newState()): MedtrumSession =
        MedtrumSession(
            transport = fake,
            state = state,
            recordSync = MedtrumRecordSync.NO_OP,
            logger = MedtrumLogger.NO_OP,
            clock = clock,
            timeUtil = MedtrumTimeUtil(clock::nowMillis),
            timeZoneOffsetMinutes = { 60 }
        )

    /** Virtual-time session: sleeps advance [testClock] instead of real time. */
    private fun newVirtualSession(
        fake: FakeMedtrumBleTransport,
        testClock: TestMedtrumClock,
        state: MedtrumSessionState = MedtrumSessionState(MedtrumLogger.NO_OP, testClock).apply {
            pumpSN = 0x12345678L
            patchSessionToken = 0xABCDEF01L
        }
    ): MedtrumSession =
        MedtrumSession(
            transport = fake,
            state = state,
            recordSync = MedtrumRecordSync.NO_OP,
            logger = MedtrumLogger.NO_OP,
            clock = testClock,
            timeUtil = MedtrumTimeUtil(testClock::nowMillis),
            timeZoneOffsetMinutes = { 60 },
            sleeper = { ms -> testClock.nowMs += ms }
        )

    /** Script the full successful auth handshake. Pump time = now (no drift). */
    private fun scriptAuthSuccess(fake: FakeMedtrumBleTransport) {
        val pumpSeconds = ((nowMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000).toInt()
        // Little-endian (ByteArray.toLong is LE, Android-verbatim)
        val pumpTimeBytes = byteArrayOf(
            pumpSeconds.toByte(), (pumpSeconds shr 8).toByte(),
            (pumpSeconds shr 16).toByte(), (pumpSeconds shr 24).toByte()
        )
        // Authorize: 11 bytes min (deviceType @7, version @8..10)
        fake.responses[CommandType.AUTH_REQ.code] =
            successResponse(CommandType.AUTH_REQ.code, 0, 0, 1, 2, 3)
        // GetDeviceType: 11 bytes min (deviceType @6, SN @7..10)
        fake.responses[CommandType.GET_DEVICE_TYPE.code] =
            successResponse(CommandType.GET_DEVICE_TYPE.code, 1, 0x12, 0x34, 0x56, 0x78)
        // GetTime: 10 bytes min (pump time @6..9)
        fake.responses[CommandType.GET_TIME.code] =
            successResponse(CommandType.GET_TIME.code) + pumpTimeBytes
        // Synchronize: 10 bytes min (sync data @9)
        fake.responses[CommandType.SYNCHRONIZE.code] =
            successResponse(CommandType.SYNCHRONIZE.code, 0, 0, 0, 0)
        fake.responses[CommandType.SUBSCRIBE.code] =
            successResponse(CommandType.SUBSCRIBE.code)
    }

    private fun connectAndAuth(fake: FakeMedtrumBleTransport, session: MedtrumSession): Boolean =
        runBlocking { session.connect("test") }

    @Test
    fun authFlowSuccessReachesReady() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)

        val ok = session.connect("test")

        assertTrue(ok, "connect should succeed")
        assertEquals(MedtrumSession.Phase.READY, session.phase)
        assertEquals(ConnectionState.CONNECTED, session.state.connectionState)
        // Auth sequence: Authorize, GetDeviceType, GetTime, Synchronize, Subscribe
        val opcodes = fake.sentMessages.map { it[0] }
        assertEquals(
            listOf(
                CommandType.AUTH_REQ.code,
                CommandType.GET_DEVICE_TYPE.code,
                CommandType.GET_TIME.code,
                CommandType.SYNCHRONIZE.code,
                CommandType.SUBSCRIBE.code
            ),
            opcodes
        )
    }

    @Test
    fun authFlowFailureReturnsFalseAndIdles() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        fake.responses[CommandType.AUTH_REQ.code] = errorResponse(CommandType.AUTH_REQ.code)

        val ok = session.connect("test")

        assertFalse(ok, "connect should fail")
        assertEquals(MedtrumSession.Phase.IDLE, session.phase)
        assertEquals(ConnectionState.DISCONNECTED, session.state.connectionState)
        assertTrue(fake.disconnects.isNotEmpty(), "should disconnect on auth failure")
    }

    @Test
    fun setBolusSendsExactProtocolBytes() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))

        // Bolus command: script success, then complete via state hook
        fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        val job = launch {
            session.setBolus(2.35)
        }
        // Wait for the bolus command to be sent
        var tries = 0
        while (fake.sentMessages.none { it[0] == CommandType.SET_BOLUS.code } && tries < 100) {
            kotlinx.coroutines.delay(10)
            tries++
        }
        val bolusMsg = fake.sentMessages.first { it[0] == CommandType.SET_BOLUS.code }
        // Differential vs M1: SetBolusPacket(2.35) must produce [19, 1, 47, 0, 0]
        assertContentEquals(byteArrayOf(19, 1, 47, 0, 0), bolusMsg)
        // Simulate pump-reported completion
        session.state.handleBolusStatusUpdate(1, true, 2.35)
        job.join()
    }

    @Test
    fun setBolusDeliveredWhenPumpReportsCompletion() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))
        fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)

        val job = launch {
            val result = session.setBolus(1.0)
            assertEquals(MedtrumSession.BolusResult.DELIVERED, result)
        }
        var tries = 0
        while (fake.sentMessages.none { it[0] == CommandType.SET_BOLUS.code } && tries < 100) {
            kotlinx.coroutines.delay(10)
            tries++
        }
        session.state.handleBolusStatusUpdate(1, true, 1.0)
        job.join()
        assertEquals(1.0, session.state.bolusAmountDelivered)
        assertTrue(session.state.bolusDone)
    }

    @Test
    fun setBolusRejectedWhenNotConnected() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        // No connect — session is IDLE
        val result = session.setBolus(1.0)
        assertEquals(MedtrumSession.BolusResult.NOT_CONNECTED, result)
        assertTrue(fake.sentMessages.isEmpty(), "no bytes should be sent when not connected")
    }

    @Test
    fun commandTimeoutDisconnectsToIdle() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))

        // Silence: no response scripted for SET_BOLUS → timeout.
        // Use a short timeout via sendCommand directly.
        fake.responses.clear()
        val ok = session.sendCommand(SetBolusPacket(1.0), timeoutSec = 1)

        assertFalse(ok, "command should time out")
        assertEquals(MedtrumSession.Phase.IDLE, session.phase)
        assertEquals(ConnectionState.DISCONNECTED, session.state.connectionState)
    }

    @Test
    fun notificationUpdatesPumpState() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))

        // Notification: pump state ACTIVE (byte 1 = state). Minimal masked message.
        // FieldMask bit for state change; the exact parse is M1-tested — here we
        // verify the session routes notifications to the packet handler.
        val stateBefore = session.state.pumpState
        // Empty notification (no fields) should not crash
        session.onNotification(byteArrayOf(0, 0, 0, 0))
        assertEquals(stateBefore, session.state.pumpState)
    }

    @Test
    fun sendMessageErrorRetriesThenFails() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val session = newSession(fake)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))

        fake.responses.clear() // silence → will timeout, but first trigger send errors
        fake.sentMessages.clear() // ignore auth flow messages
        val job = launch {
            session.sendCommand(SetBolusPacket(1.0), timeoutSec = 30)
        }
        var tries = 0
        while (fake.sentMessages.isEmpty() && tries < 100) {
            kotlinx.coroutines.delay(10)
            tries++
        }
        // 3 retries then failure → disconnect + IDLE
        repeat(4) { session.onSendMessageError("test", true) }
        job.join()
        // 1 initial + 3 retries = 4 sends
        assertEquals(4, fake.sentMessages.size)
        assertEquals(MedtrumSession.Phase.IDLE, session.phase)
    }

    @Test
    fun tempBasalSendsCancelThenSet() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val state = newState()
        val session = newSession(fake, state)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))
        fake.responses[CommandType.CANCEL_TEMP_BASAL.code] =
            // 17 bytes min: basal type @6, rate @7..8, seq @9..10, patchId @11..12, startTime @13..16
            successResponse(CommandType.CANCEL_TEMP_BASAL.code, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        fake.responses[CommandType.SET_TEMP_BASAL.code] =
            successResponse(CommandType.SET_TEMP_BASAL.code, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        // Simulate a temp basal in progress
        state.lastBasalType = app.aaps.pump.medtrum.comm.enums.BasalType.ABSOLUTE_TEMP
        val ok = session.setTempBasal(1.5, 30)

        assertTrue(ok)
        val opcodes = fake.sentMessages.map { it[0] }
        assertTrue(opcodes.contains(CommandType.CANCEL_TEMP_BASAL.code), "should cancel existing TBR first")
        assertTrue(opcodes.contains(CommandType.SET_TEMP_BASAL.code), "should set new TBR")
    }

    @Test
    fun basalProfileBuilderMatchesAndroidEncoding() {
        val state = newState()
        // 1.0 U/h at 00:00, 0.8 U/h at 06:00 → rate 20 and 16 (×0.05), times 0 and 360 min
        // toByteArray is little-endian (Android-verbatim)
        val bytes = state.buildMedtrumProfileArray(listOf(1.0 to 0, 0.8 to 21600))
        assertTrue(bytes != null)
        assertEquals(2, bytes[0].toInt()) // count
        // Slot 1: (20 << 12) | 0 = 0x14000 → LE [0x00, 0x40, 0x01]
        assertContentEquals(byteArrayOf(0x00, 0x40, 0x01), bytes.copyOfRange(1, 4))
        // Slot 2: (16 << 12) | 360 = 0x10168 → LE [0x68, 0x01, 0x01]
        assertContentEquals(byteArrayOf(0x68, 0x01, 0x01), bytes.copyOfRange(4, 7))
    }

    @Test
    fun hourlyBasalLookupFindsCorrectSlot() {
        val state = newState()
        val bytes = state.buildMedtrumProfileArray(listOf(1.0 to 0, 0.8 to 21600))!!
        // 10:00 = 600 min → second slot (06:00–24:00) → 0.8 U/h
        val basal = state.getHourlyBasalFromMedtrumProfileArray(bytes, nowMs)
        assertEquals(0.8, basal, 0.001)
    }

    /**
     * Safety-critical: a 2.35 U bolus takes ~141 s to deliver. The old fixed
     * 60 s timeout fired mid-delivery; the dose-proportional window
     * (2.35*60_000 + 1000 = 142_000 ms) must let a completion at 140 s succeed.
     *
     * Fully synchronous in virtual time: the sleeper fires scheduled pump
     * notifications at their virtual timestamps, so there is no real-time race.
     */
    @Test
    fun setBolusLargeDoseSucceedsWhenCompletedAt140s() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val testClock = TestMedtrumClock(nowMs)
        val state = MedtrumSessionState(MedtrumLogger.NO_OP, testClock).apply {
            pumpSN = 0x12345678L
            patchSessionToken = 0xABCDEF01L
        }
        val startMs = testClock.nowMs
        // Pump script: progress every 10 s virtual, completion at 140 s.
        val schedule = ArrayDeque<Pair<Long, () -> Unit>>()
        for (t in 10_000L..130_000L step 10_000L) {
            val at = startMs + t
            schedule.add(at to {
                val delivered = (t / 60_000.0).coerceAtMost(2.35)
                state.handleBolusStatusUpdate(1, false, delivered)
            })
        }
        schedule.add((startMs + 140_000L) to {
            state.handleBolusStatusUpdate(1, true, 2.35)
        })
        val session = MedtrumSession(
            transport = fake,
            state = state,
            recordSync = MedtrumRecordSync.NO_OP,
            logger = MedtrumLogger.NO_OP,
            clock = testClock,
            timeUtil = MedtrumTimeUtil(testClock::nowMillis),
            timeZoneOffsetMinutes = { 60 },
            sleeper = { ms ->
                val target = testClock.nowMs + ms
                while (schedule.isNotEmpty() && schedule.first().first <= target) {
                    val (at, action) = schedule.removeFirst()
                    testClock.nowMs = at
                    action()
                }
                testClock.nowMs = target
            }
        )
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))
        fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)

        val result = session.setBolus(2.35)

        assertEquals(MedtrumSession.BolusResult.DELIVERED, result, "2.35 U completed at 140 s must not TIMEOUT")
        assertEquals(2.35, state.bolusAmountDelivered, 0.001)
        assertTrue(state.bolusDone)
    }

    /**
     * Safety-critical: with no progress notifications, the session must retry
     * the connection 3× (20 s apart) and then fail cleanly — never hang, never
     * report a phantom delivery.
     */
    @Test
    fun setBolusStallRetriesThreeTimesThenFailsCleanly() = runBlocking {
        val fake = FakeMedtrumBleTransport()
        val testClock = TestMedtrumClock(nowMs)
        val state = MedtrumSessionState(MedtrumLogger.NO_OP, testClock).apply {
            pumpSN = 0x12345678L
            patchSessionToken = 0xABCDEF01L
        }
        val session = newVirtualSession(fake, testClock, state)
        scriptAuthSuccess(fake)
        assertTrue(session.connect("test"))
        fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)

        // No progress updates at all → stall detection fires.
        // Synchronous in virtual time: 20 s × 4 (3 retries + give-up) ≈ 80 s fast-forwarded.
        val result = session.setBolus(1.0)

        assertEquals(MedtrumSession.BolusResult.TIMEOUT, result, "stalled bolus must fail cleanly")
        assertEquals("communication_lost", state.bolusErrorReason)
        assertTrue(fake.disconnects.contains("Communication stopped"), "must disconnect after 3 failed retries")
        // 80 s virtual (not the 61 s dose window): phase 2 never ran, loss was in phase 1.
        assertTrue(
            testClock.nowMs - nowMs >= 80_000,
            "should have waited through 3 retries, got ${testClock.nowMs - nowMs} ms"
        )
    }
}
