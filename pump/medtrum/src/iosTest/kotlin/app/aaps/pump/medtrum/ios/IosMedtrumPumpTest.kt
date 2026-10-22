package app.aaps.pump.medtrum.ios

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.RecordingPumpSync
import app.aaps.pump.medtrum.comm.FakeDetailedBolusInfoStorage
import app.aaps.pump.medtrum.comm.FakeTemporaryBasalStorage
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.CommandType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.session.FakeMedtrumBleTransport
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSession
import app.aaps.pump.medtrum.session.MedtrumSessionState
import app.aaps.pump.medtrum.session.errorResponse
import app.aaps.pump.medtrum.session.successResponse
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A [TextResolver] that makes the resolved text easy to see in a test. */
object TestTextResolver : TextResolver {

    override fun gs(ref: TextRef): String = "resolved:$ref"
    override fun gs(ref: TextRef, vararg args: Any?): String = "resolved:$ref"
    override fun gsNotLocalised(ref: TextRef): String = "resolved:$ref"
    override fun shortTextMode(): Boolean = false
}

/**
 * Tests for the behaviour of [IosMedtrumPump] on top of a real [MedtrumSession] and a fake transport.
 * The tests use `runBlocking` and real time: the session starts its auth flow on its own scope, and a
 * virtual-time scheduler would skip the connect timeout while that flow runs.
 */
class IosMedtrumPumpTest {

    private val nowMs = 1_700_000_000_000L

    private class Rig(
        val fake: FakeMedtrumBleTransport,
        val state: MedtrumSessionState,
        val pump: IosMedtrumPump,
        val logs: MutableList<String>,
        val sync: RecordingPumpSync,
        val infos: FakeDetailedBolusInfoStorage,
        val tbrs: FakeTemporaryBasalStorage,
    )

    private fun CoroutineScope.newRig(clock: MedtrumClock = MedtrumClock.fixed(nowMs, minuteOfDay = 600, utcOffsetMinutes = 120)): Rig {
        val fake = FakeMedtrumBleTransport()
        val state = MedtrumSessionState(MedtrumLogger.NO_OP, clock).apply {
            pumpSN = 0x12345678L
            deviceType = 80 // MD0201, a Nano
            patchSessionToken = 0xABCDEF01L
            pumpState = MedtrumPumpState.ACTIVE
            // The pump already runs on the phone's time zone, so a connect sends no time zone update.
            pumpTimeZoneOffset = 120
        }
        val logs = mutableListOf<String>()
        val logger = object : MedtrumLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun error(tag: String, message: String) { logs.add("E $message") }
            override fun warn(tag: String, message: String) { logs.add("W $message") }
        }
        val sync = RecordingPumpSync()
        val infos = FakeDetailedBolusInfoStorage()
        val tbrs = FakeTemporaryBasalStorage()
        val pump = IosMedtrumPump(
            fake, state, clock, TestTextResolver,
            pumpSync = sync, detailedBolusInfoStorage = infos, temporaryBasalStorage = tbrs,
            scope = this, store = null, logger = logger
        )
        return Rig(fake, state, pump, logs, sync, infos, tbrs)
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
        fake.responses[CommandType.SYNCHRONIZE.code] = // Byte 6 is the pump state: 32 = ACTIVE, so the loop may use the pump after the connect.
            successResponse(CommandType.SYNCHRONIZE.code, 32, 0, 0, 0)
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
        // The scripted auth answer gives an unknown device type. A real Nano reports 80.
        rig.state.deviceType = 80
        return rig
    }

    private fun Rig.sent(opcode: CommandType): Boolean = fake.sentMessages.any { it[0] == opcode.code }

    private fun bolusInfo(units: Double) = DetailedBolusInfo().apply { insulin = units }

    // -- Item 1: stop reaches the pump during delivery ----------------------------

    @Test
    fun stopBolusReachesPumpWhileBolusIsDelivering() = runBlocking {
        val rig = readyRig()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.CANCEL_BOLUS.code] = successResponse(CommandType.CANCEL_BOLUS.code)

        // 2 U takes about 2 minutes. The delivery is still running when the user presses stop.
        val delivery = async { rig.pump.deliverTreatment(bolusInfo(2.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) }, "bolus command should go out")
        rig.state.handleBolusStatusUpdate(1, false, 0.5)

        rig.pump.stopBolusDelivering()
        val cancelSent = waitUntil(2_000) { rig.sent(CommandType.CANCEL_BOLUS) }
        // If the cancel did not go out the delivery would run for minutes. Finish it so the test ends.
        if (!cancelSent) rig.state.handleBolusStatusUpdate(1, true, 2.0)
        val result = delivery.await()

        assertTrue(cancelSent, "cancel must reach the pump during the delivery")
        assertTrue(result.success, "a stopped bolus is a success, as on Android")
        assertEquals(0.5, result.bolusDelivered, "report what the pump delivered")
    }

    // -- Item 2: reported amount -------------------------------------------------

    @Test
    fun deliveredBolusReportsPumpTrackedAmount() = runBlocking {
        val rig = readyRig()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        val delivery = async { rig.pump.deliverTreatment(bolusInfo(1.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        rig.state.handleBolusStatusUpdate(1, true, 1.0)
        val result = delivery.await()

        assertTrue(result.success)
        assertEquals(1.0, result.bolusDelivered)
        // Item 3: the last bolus is now visible to the loop.
        assertEquals(1.0, rig.pump.lastBolusAmount.value?.cU)
        assertEquals(rig.state.bolusStartTime, rig.pump.lastBolusTime.value)
    }

    @Test
    fun bolusDoneWithLessThanAskedIsNotASuccess() = runBlocking {
        val rig = readyRig()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        val delivery = async { rig.pump.deliverTreatment(bolusInfo(2.0)) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        // The pump says "done" but only 1 U was given.
        rig.state.handleBolusStatusUpdate(1, true, 1.0)
        val result = delivery.await()

        assertFalse(result.success, "1 U of 2 U is not a success")
        assertEquals(1.0, result.bolusDelivered, "report the 1 U the pump gave, not the 2 U asked")
        assertEquals(null, rig.pump.lastBolusAmount.value, "a failed bolus is not the last bolus")
    }

    @Test
    fun sendFailedReportsPumpTrackedAmountAndFails() = runBlocking {
        val rig = readyRig()
        rig.fake.responses[CommandType.SET_BOLUS.code] = errorResponse(CommandType.SET_BOLUS.code)
        val result = rig.pump.deliverTreatment(bolusInfo(1.0))

        assertFalse(result.success)
        assertEquals(rig.state.bolusAmountDelivered, result.bolusDelivered)
    }

    @Test
    fun bolusVerdictTable() {
        val step = 0.05
        val r = MedtrumSession.BolusResult.entries.associateWith { result ->
            bolusVerdict(result, requested = 2.0, delivered = 2.0, bolusStep = step)
        }
        assertEquals(true, r[MedtrumSession.BolusResult.DELIVERED])
        assertEquals(true, r[MedtrumSession.BolusResult.TIMEOUT])
        assertEquals(true, r[MedtrumSession.BolusResult.STOPPED])
        assertEquals(false, r[MedtrumSession.BolusResult.NOT_CONNECTED])
        assertEquals(false, r[MedtrumSession.BolusResult.ALREADY_IN_PROGRESS])
        assertEquals(false, r[MedtrumSession.BolusResult.SEND_FAILED])
        // Partial amount: the user stop is the only success. A time out with a gap is a failure.
        assertEquals(false, bolusVerdict(MedtrumSession.BolusResult.TIMEOUT, 2.0, 1.2, step))
        assertEquals(false, bolusVerdict(MedtrumSession.BolusResult.DELIVERED, 2.0, 1.2, step))
        assertEquals(true, bolusVerdict(MedtrumSession.BolusResult.STOPPED, 2.0, 0.0, step))
        // Within one step still counts, as on Android.
        assertEquals(true, bolusVerdict(MedtrumSession.BolusResult.DELIVERED, 2.0, 1.97, step))
        assertEquals(false, bolusVerdict(MedtrumSession.BolusResult.DELIVERED, 2.0, 1.95, step))
    }

    // -- Item 3: exposed state follows the real state ----------------------------

    @Test
    fun exposedStateFollowsSessionState() = runBlocking {
        val rig = newRig()
        assertEquals(0L, rig.pump.lastDataTime.value)

        rig.state.reservoir = 123.5
        rig.state.lastBolusAmount = 0.75
        rig.state.lastBolusTime = nowMs - 1000
        rig.pump.publishState()

        assertEquals(123.5, rig.pump.reservoirLevel.value.cU)
        assertEquals(0.75, rig.pump.lastBolusAmount.value?.cU)
        assertEquals(nowMs - 1000, rig.pump.lastBolusTime.value)
        assertEquals(null, rig.pump.batteryLevel.value, "no battery value, same as Android")
    }

    @Test
    fun lastDataTimeIsSetWhenConnectFinishes() = runBlocking {
        val rig = readyRig()
        assertEquals(nowMs, rig.pump.lastDataTime.value)
    }

    @Test
    fun baseBasalRateReadsProfileAtLocalMinuteOfDay() = runBlocking {
        val rig = newRig() // the fixed clock says 10:00 (minute 600)
        // 0.5 U/h from 00:00, 1.0 U/h from 08:00, 2.0 U/h from 12:00
        rig.state.actualBasalProfile = rig.state.buildMedtrumProfileArray(
            listOf(0.5 to 0, 1.0 to 8 * 3600, 2.0 to 12 * 3600)
        )!!
        assertEquals(1.0, rig.pump.baseBasalRate.cU, 0.0001)
    }

    @Test
    fun baseBasalRateIsZeroForEmptyProfile() = runBlocking {
        val rig = newRig()
        rig.state.actualBasalProfile = ByteArray(0)
        assertEquals(0.0, rig.pump.baseBasalRate.cU)
    }

    // -- Items 4 and 5: suspended and initialized --------------------------------

    @Test
    fun isSuspendedFollowsPumpState() = runBlocking {
        val rig = newRig()
        for (s in MedtrumPumpState.entries) {
            rig.state.pumpState = s
            val canDeliver = s == MedtrumPumpState.ACTIVE || s == MedtrumPumpState.ACTIVE_ALT
            assertEquals(!canDeliver, rig.pump.isSuspended(), "isSuspended for $s")
        }
    }

    @Test
    fun isInitializedDoesNotDependOnTheBleLink() = runBlocking {
        val rig = newRig()
        assertEquals(ConnectionState.DISCONNECTED, rig.state.connectionState)
        assertFalse(rig.pump.isReady())
        rig.state.pumpState = MedtrumPumpState.ACTIVE
        assertTrue(rig.pump.isInitialized(), "an active patch is initialized with the link down")
    }

    @Test
    fun isInitializedFollowsStoredPatchState() = runBlocking {
        val rig = newRig()
        for (s in MedtrumPumpState.entries) {
            rig.state.pumpState = s
            val expected = s > MedtrumPumpState.EJECTED && s < MedtrumPumpState.STOPPED
            assertEquals(expected, rig.pump.isInitialized(), "isInitialized for $s")
        }
        rig.state.pumpState = MedtrumPumpState.ACTIVE
        rig.state.pumpSN = 0L
        assertFalse(rig.pump.isInitialized(), "no serial number: not configured")
    }

    @Test
    fun isConnectedIsTrueWithoutAnActivePatch() = runBlocking {
        val rig = newRig()
        rig.state.pumpState = MedtrumPumpState.STOPPED
        assertTrue(rig.pump.isConnected(), "Android says connected so the queue does not start connects")
        rig.state.pumpState = MedtrumPumpState.ACTIVE
        assertFalse(rig.pump.isConnected())
    }

    // -- Item 6: pump description --------------------------------------------------

    @Test
    fun pumpDescriptionComesFromThePumpType() = runBlocking {
        val d = newRig().pump.pumpDescription
        assertEquals(PumpDescription.ABSOLUTE, d.tempBasalStyle)
        assertEquals(25.0, d.maxTempAbsolute)
        assertEquals(0.05, d.basalStep)
        assertEquals(0.05, d.bolusStep)
        assertEquals(200, d.maxReservoirReading)
        assertTrue(d.isPatchPump)
        assertFalse(d.isExtendedBolusCapable)
    }

    // -- Item 7: clock and time zone -----------------------------------------------

    @Test
    fun sessionSendsThePhoneTimeZoneToThePump() = runBlocking {
        val rig = newRig()
        scriptAuthSuccess(rig.fake)
        // The pump clock is far off, so the flow must set time and time zone.
        rig.fake.responses[CommandType.GET_TIME.code] = successResponse(CommandType.GET_TIME.code, 0, 0, 0, 0)
        rig.fake.responses[CommandType.SET_TIME.code] = successResponse(CommandType.SET_TIME.code)
        rig.fake.responses[CommandType.SET_TIME_ZONE.code] = successResponse(CommandType.SET_TIME_ZONE.code)
        rig.pump.connect("test")
        assertTrue(waitUntil { rig.pump.isReady() })

        val tz = rig.fake.sentMessages.first { it[0] == CommandType.SET_TIME_ZONE.code }
        // Offset is 120 minutes, little endian in the two bytes after the opcode.
        assertEquals(120, (tz[1].toInt() and 0xFF) or ((tz[2].toInt() and 0xFF) shl 8))
    }

    @Test
    fun systemClockGivesNowAndLocalOffset() {
        val clock = systemMedtrumClock()
        val now = clock.nowMillis()
        val expectedOffset = (NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(now / 1000.0)) / 60).toInt()
        assertEquals(expectedOffset, clock.utcOffsetMinutes(now))
        val minute = clock.minuteOfDay(now)
        assertTrue(minute in 0..1439)
        assertEquals(localMinuteOfDay(now / 60_000 + expectedOffset), minute)
        assertNotEquals(0L, now)
    }

    private fun localMinuteOfDay(minutes: Long): Int = (((minutes % 1440) + 1440) % 1440).toInt()

    // -- Item 8: scope and failed connect --------------------------------------------

    @Test
    fun failedConnectIsLoggedAndLeavesPumpNotReady() = runBlocking {
        val rig = newRig()
        rig.fake.connectResult = false
        rig.pump.connect("test")
        assertTrue(waitUntil { rig.logs.any { it.contains("connect failed") } }, "a failed connect must be logged")
        assertFalse(rig.pump.isReady())
    }

    @Test
    fun connectWorkRunsOnTheOwnerScope() = runBlocking {
        val job = Job()
        val scope = CoroutineScope(job)
        val rig = scope.newRig()
        scriptAuthSuccess(rig.fake)
        job.cancel() // the owner stops the driver
        rig.pump.connect("test")
        delay(300)
        assertTrue(rig.fake.sentMessages.isEmpty(), "a cancelled owner scope must not start a connect")
    }

    // -- Item 9: small ones -----------------------------------------------------------

    @Test
    fun stopConnectingDropsTheAttemptAndEndsTheConnect() = runBlocking {
        val rig = newRig()
        // No answers: the connect waits for the pump.
        rig.pump.connect("test")
        assertTrue(waitUntil { rig.pump.isConnecting() })
        rig.pump.stopConnecting()
        assertTrue(rig.fake.disconnects.contains("stopConnecting"))
        assertTrue(waitUntil { !rig.pump.isConnecting() }, "the connect must end")
        assertFalse(rig.pump.isHandshakeInProgress())
    }

    @Test
    fun serialNumberIsUpperCaseHex() = runBlocking {
        val rig = newRig()
        rig.state.pumpSN = 0xABCDEF1L
        assertEquals("ABCDEF1", rig.pump.serialNumber())
    }

    @Test
    fun commentFromTextRefIsResolved() {
        val result = IosPumpEnactResult(textResolver = TestTextResolver)
        val ref = TextRef.AndroidRes(42)
        result.comment(ref)
        assertEquals("resolved:$ref", result.comment)
        // Without a resolver the text is not lost either.
        val plain = IosPumpEnactResult().comment(ref)
        assertEquals(ref.toString(), plain.comment)
    }

    // -- History: what the loop is told -------------------------------------------------------------

    @Test
    fun startedBolusIsWrittenAsTemporaryBolusWithTheAppInfo() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        val info = bolusInfo(1.0).apply { bolusType = BS.Type.SMB }
        val delivery = async { rig.pump.deliverTreatment(info) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        // The bolus is still running: the loop must already count it.
        assertEquals(
            listOf("addBolusWithTempId(timestamp=$nowMs, amount=1.0, temporaryId=$nowMs, type=SMB, pumpType=MEDTRUM_NANO, pumpSerial=12345678)"),
            rig.sync.calls
        )
        assertEquals(listOf(info), rig.infos.infos)
        assertEquals(nowMs, info.timestamp)
        rig.state.handleBolusStatusUpdate(1, true, 1.0)
        delivery.await()
        Unit
    }

    @Test
    fun bolusStoppedBeforeAnyInsulinSyncsZeroUnits() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.fake.responses[CommandType.SET_BOLUS.code] = successResponse(CommandType.SET_BOLUS.code)
        rig.fake.responses[CommandType.CANCEL_BOLUS.code] = successResponse(CommandType.CANCEL_BOLUS.code)
        val delivery = async { rig.pump.deliverTreatment(bolusInfo(2.0).apply { bolusType = BS.Type.SMB }) }
        assertTrue(waitUntil { rig.sent(CommandType.SET_BOLUS) })
        rig.pump.stopBolusDelivering()
        val cancelSent = waitUntil(2_000) { rig.sent(CommandType.CANCEL_BOLUS) }
        if (!cancelSent) rig.state.handleBolusStatusUpdate(1, true, 2.0)
        delivery.await()
        assertTrue(cancelSent)
        assertEquals(2, rig.sync.calls.size, rig.sync.calls.toString())
        assertEquals(
            "syncBolusWithTempId(timestamp=$nowMs, amount=0.0, temporaryId=$nowMs, type=SMB, pumpId=$nowMs, pumpType=MEDTRUM_NANO, pumpSerial=12345678)",
            rig.sync.calls[1]
        )
        assertTrue(rig.infos.infos.isEmpty())
    }

    /** iOS has no time zone receiver, so a connect is where a daylight saving change reaches the pump. */
    @Test
    fun aConnectSendsTheNewTimeZoneWhenThePhoneOffsetChanged() = runBlocking {
        val rig = newRig()
        rig.state.pumpTimeZoneOffset = 60 // the pump still runs on winter time
        scriptAuthSuccess(rig.fake)
        val pumpSeconds = ((nowMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000).toInt()
        val pumpTimeBytes = byteArrayOf(
            pumpSeconds.toByte(), (pumpSeconds shr 8).toByte(),
            (pumpSeconds shr 16).toByte(), (pumpSeconds shr 24).toByte()
        )
        rig.fake.responses[CommandType.SET_TIME.code] = successResponse(CommandType.SET_TIME.code) + pumpTimeBytes
        rig.fake.responses[CommandType.SET_TIME_ZONE.code] = successResponse(CommandType.SET_TIME_ZONE.code) + pumpTimeBytes

        rig.pump.connect("test")

        assertTrue(waitUntil { rig.state.pumpTimeZoneOffset == 120 }, "the pump should get the phone's offset")
        assertTrue(rig.fake.sentMessages.any { it.getOrNull(0) == CommandType.SET_TIME_ZONE.code })
    }

    @Test
    fun tempBasalRequestIsStoredAndTheStatusFromThePumpIsSynced() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        val pumpSeconds = (nowMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000
        // Answer: basal type 6 (absolute temp), rate 20 * 0.05 = 1.0, sequence 3, patch 0, start time.
        rig.fake.responses[CommandType.SET_TEMP_BASAL.code] = successResponse(
            CommandType.SET_TEMP_BASAL.code, 6, 20, 0, 3, 0, 0, 0,
            pumpSeconds.toByte(), (pumpSeconds shr 8).toByte(), (pumpSeconds shr 16).toByte(), (pumpSeconds shr 24).toByte()
        )
        val result = rig.pump.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL)
        assertTrue(result.success, result.comment)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$nowMs, rate=1.0, duration=${30 * 60_000L}, isAbsolute=true, type=NORMAL, pumpId=$nowMs, pumpType=MEDTRUM_NANO, pumpSerial=12345678)"),
            rig.sync.calls
        )
        assertTrue(rig.tbrs.items.isEmpty(), "the request was used")
    }

    @Test
    fun pumpStateChangeToAnAlarmIsAnnouncedAndAFakeTempBasalIsSet() = runBlocking {
        val rig = readyRig()
        rig.sync.calls.clear()
        rig.state.pumpState = MedtrumPumpState.OCCLUSION
        rig.pump.publishState()
        assertEquals(2, rig.sync.calls.size, rig.sync.calls.toString())
        assertEquals("insertAnnouncement(error=Occlusion, pumpId=null, pumpType=MEDTRUM_NANO, pumpSerial=12345678)", rig.sync.calls[0])
        assertTrue(rig.sync.calls[1].startsWith("syncTemporaryBasalWithPumpId(timestamp=$nowMs, rate=0.0, duration=${4800 * 60_000L}, isAbsolute=true, type=PUMP_SUSPEND"))
        assertTrue(AlarmState.OCCLUSION in rig.state.activeAlarms)
        // The same state again writes nothing more.
        rig.pump.publishState()
        assertEquals(2, rig.sync.calls.size)
    }

    // -- Store: patch state survives a restart -------------------------------------------

    @Test
    fun storeKeepsThePatchState() {
        val defaults = NSUserDefaults(suiteName = "medtrum-test-${nowMs}")!!
        val store = IosMedtrumStateStore(defaults, prefix = "t.")
        try {
            val saved = MedtrumSessionState(clock = MedtrumClock.fixed(nowMs)).apply {
                pumpSN = 77L
                pumpState = MedtrumPumpState.ACTIVE
            }
            store.save(saved)
            val loaded = MedtrumSessionState(clock = MedtrumClock.fixed(nowMs))
            store.load(loaded)
            assertEquals(MedtrumPumpState.ACTIVE, loaded.pumpState)

            saved.pumpState = MedtrumPumpState.STOPPED
            store.save(saved)
            val loaded2 = MedtrumSessionState(clock = MedtrumClock.fixed(nowMs))
            store.load(loaded2)
            assertEquals(MedtrumPumpState.STOPPED, loaded2.pumpState)
        } finally {
            store.clear()
        }
    }
}
