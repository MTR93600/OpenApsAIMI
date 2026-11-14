package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The [IosDashPluginPump] the iOS plugin gives to the app, on the real driver against the simulated
 * pod, with the `PumpSync` recorder wired as `IosDashDriverHost` wires it. Each case is a rule of
 * Android `OmnipodDashPumpPlugin`: what it answers itself, and what it writes to `PumpSync` and when.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7 the first command and 8 its ACK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosDashPluginPumpTest {

    private val f = DashPumpOnFakePod()
    private val pumpSync = RecordingDashPumpSync()
    private val recorder = DashPumpSyncRecorder(
        pumpSync = pumpSync,
        aapsLogger = SilentPodLogger,
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
    )

    /** The wall clock [IosDashPluginPump] reads for the basal rate; the driver reads `f.nowMs`. */
    private var clockMs = SENT_AT

    private val pump = IosDashPluginPump(f.pump, f.stateManager, recorder, SilentPodLogger, now = { clockMs })

    init {
        f.onOutcome = recorder::onOutcome
        f.nowMs = SENT_AT
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double, type: BS.Type = BS.Type.NORMAL) = DetailedBolusInfo().apply {
        insulin = units
        bolusType = type
    }

    private fun bolusLine(at: Long, units: Double, type: BS.Type?) =
        "bolus(timestamp=$at, amount=$units, type=$type, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)"

    private fun tempBasalLine(at: Long, rate: Double, durationMs: Long, type: PumpSync.TemporaryBasalType) =
        "tempBasal(timestamp=$at, rate=$rate, duration=$durationMs, isAbsolute=true, type=$type, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)"

    private fun stopLine(at: Long) = "stopTempBasal(timestamp=$at, endPumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)"

    // ---------------------------------------------------------------- what the plugin answers

    @Test
    fun `the pump description is the Android one`() {
        val android = PumpDescription().fillFor(PumpType.OMNIPOD_DASH)
        val ios = pump.pumpDescription

        assertEquals(android.pumpType, ios.pumpType)
        assertEquals(android.bolusStep, ios.bolusStep)
        assertEquals(android.isBolusCapable, ios.isBolusCapable)
        assertEquals(android.isTempBasalCapable, ios.isTempBasalCapable)
        assertEquals(android.isExtendedBolusCapable, ios.isExtendedBolusCapable)
        assertEquals(android.tempBasalStyle, ios.tempBasalStyle)
        assertEquals(android.maxTempAbsolute, ios.maxTempAbsolute)
        assertEquals(android.tempAbsoluteStep, ios.tempAbsoluteStep)
        assertEquals(android.tempDurationStep, ios.tempDurationStep)
        assertEquals(android.tempMaxDuration, ios.tempMaxDuration)
        assertEquals(android.basalStep, ios.basalStep)
        assertEquals(android.basalMinimumRate, ios.basalMinimumRate)
        assertFalse(ios.isExtendedBolusCapable, "Dash has no extended bolus")
        assertEquals(PumpType.OMNIPOD_DASH, pump.model())
    }

    @Test
    fun `with no pod the pump is configured but not initialized and has the Android serial`() {
        assertTrue(pump.isConfigured(), "Android does not override isConfigured")
        assertFalse(pump.isInitialized(), "Android isPodRunning: no pod, no loop")
        assertEquals("4241", pump.serialNumber())
        assertNull(pump.deliveredBasal())
        assertEquals(0.0, pump.baseBasalRate.cU)
    }

    @Test
    fun `a running pod is initialized also while the link is down as Android`() = runTest {
        f.pairAndConnect()
        assertTrue(pump.isInitialized())
        assertEquals(SERIAL, pump.serialNumber())

        f.dropLink()

        assertTrue(pump.isInitialized(), "Android isInitialized is isPodRunning, not the link")
        assertFalse(f.pump.isInitialized(), "the driver itself says no session")
    }

    @Test
    fun `extended bolus TDD and percent temp basal are refused with nothing sent`() = runTest {
        f.pairAndConnect()
        val before = f.writesFromPump().size

        assertFalse(pump.setExtendedBolus(1.0, 30).success)
        assertFalse(pump.cancelExtendedBolus().success)
        assertFalse(pump.loadTDDs().success)
        assertFalse(pump.setTempBasalPercent(150, 30, false, PumpSync.TemporaryBasalType.NORMAL).success)
        pump.timezoneOrDSTChanged(TimeChangeType.TimeChanged)

        assertEquals(before, f.writesFromPump().size)
        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `a bolus with carbs or without insulin is refused with nothing sent`() = runTest {
        f.pairAndConnect()

        val withCarbs = bolus(1.0).apply { carbs = 10.0 }
        assertFalse(pump.deliverTreatment(withCarbs).success)
        assertFalse(pump.deliverTreatment(bolus(0.0)).success)

        assertEquals(0, f.bolusCommands())
        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `the base basal rate and the delivered basal come from the confirmed program`() = runTest {
        f.pairAndConnect()
        f.pump.getPumpStatus("first status")
        assertTrue(pump.setNewBasalProfile(PROFILE).success)
        clockMs = LocalDateTime(2026, 11, 14, 13, 10).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        assertEquals(1.0, pump.baseBasalRate.cU, "13:10 is in the 1.0 U/h part")
        val hourly = pump.deliveredBasal()!!
        assertEquals(24, hourly.size)
        assertEquals(0.5, hourly[0].value)
        assertEquals(1.0, hourly[12].value)
        assertEquals(12 * 3600, hourly[12].timeAsSeconds)

        f.stateManager.overwriteForTest { it.copy(alarmType = AlarmType.ALARM_OCCLUDED) }
        assertEquals(0.0, pump.baseBasalRate.cU, "Android: 0 while the pod reports an alarm")
    }

    // ---------------------------------------------------------------- what goes to PumpSync

    @Test
    fun `a confirmed SMB is written once at the asked amount with its type`() = runTest {
        f.pairAndConnect()

        val result = pump.deliverTreatment(bolus(0.5, BS.Type.SMB))

        assertTrue(result.success, result.comment)
        assertEquals(listOf(bolusLine(SENT_AT, 0.5, BS.Type.SMB)), pumpSync.calls)
    }

    @Test
    fun `a bolus the pod refused writes nothing`() = runTest {
        f.pairAndConnect()
        f.pod.answerFor = { DashPumpOnFakePod.defaultStatus(lastProgrammingSequence = 9) }

        assertFalse(pump.deliverTreatment(bolus(2.0)).success)

        assertTrue(pumpSync.calls.isEmpty(), "Android writes 0 U for a sent bolus the pod refused; here nothing was written")
    }

    @Test
    fun `a bolus decided late is written with the type and the time it was sent with`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        assertFalse(pump.deliverTreatment(bolus(1.5, BS.Type.SMB)).success)
        assertTrue(pumpSync.calls.isEmpty(), "not confirmed: nothing written, every delivery command is refused")

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open bolus")

        assertEquals(listOf(bolusLine(SENT_AT, 1.5, BS.Type.SMB)), pumpSync.calls)
    }

    @Test
    fun `a refused bolus call does not change the type of the open bolus`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertFalse(pump.deliverTreatment(bolus(3.0, BS.Type.NORMAL)).success)

        // The open bolus cannot be decided with the link down, so the SMB is refused.
        assertFalse(pump.deliverTreatment(bolus(0.4, BS.Type.SMB)).success)
        assertTrue(pumpSync.calls.isEmpty())

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open bolus")

        assertEquals(listOf(bolusLine(SENT_AT, 3.0, BS.Type.NORMAL)), pumpSync.calls, "written as it was sent, not as the refused SMB")
        assertEquals(1, f.bolusCommands(), "the SMB was never sent")
    }

    @Test
    fun `a stopped bolus corrects the same record and adds no second bolus`() = runTest {
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40

        val delivery = async { pump.deliverTreatment(bolus(2.0)) }
        advanceTimeBy(10_000L)
        f.bolusPulsesRemaining = 30
        pump.stopBolusDelivering()
        val result = delivery.await()

        assertTrue(result.success, result.comment)
        assertEquals(listOf(bolusLine(SENT_AT, 2.0, BS.Type.NORMAL), bolusLine(SENT_AT, 0.5, null)), pumpSync.calls)
    }

    @Test
    fun `a pod discarded with an open bolus counts it at the asked amount`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertFalse(pump.deliverTreatment(bolus(3.0, BS.Type.SMB)).success)

        f.pump.discardPod()

        assertEquals(listOf(bolusLine(SENT_AT, 3.0, BS.Type.SMB)), pumpSync.calls, "Android wrote it when it was sent")
        assertEquals("4241", pump.serialNumber(), "the pod is forgotten after the record")
    }

    @Test
    fun `a confirmed temp basal is written with its type and a cancel stops it`() = runTest {
        f.pairAndConnect()
        f.pump.getPumpStatus("first status")

        assertTrue(pump.setTempBasalAbsolute(1.2, 30, false, PumpSync.TemporaryBasalType.SUPERBOLUS).success)
        f.nowMs = SENT_AT + ONE_HOUR
        assertTrue(pump.cancelTempBasal(false).success)

        assertEquals(
            listOf(
                tempBasalLine(SENT_AT, 1.2, 30 * 60_000L, PumpSync.TemporaryBasalType.SUPERBOLUS),
                stopLine(SENT_AT + ONE_HOUR)
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a temp basal decided late is written with its type at the time it was sent`() = runTest {
        f.pairAndConnect()
        f.pump.getPumpStatus("first status")
        // Status 7-8, then the temp basal is message 9.
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(pump.setTempBasalAbsolute(0.0, 60, false, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND).success)
        assertTrue(pumpSync.calls.isEmpty())

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open temp basal")

        assertEquals(listOf(tempBasalLine(SENT_AT, 0.0, 60 * 60_000L, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)), pumpSync.calls)
    }

    @Test
    fun `a new basal program writes the suspend and then ends it`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        f.pump.getPumpStatus("first status")

        assertTrue(pump.setNewBasalProfile(PROFILE).success)

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 0.0, 80 * 3_600_000L, PumpSync.TemporaryBasalType.PUMP_SUSPEND), stopLine(SENT_AT)),
            pumpSync.calls,
            "Android: PUMP_SUSPEND temp basal for the suspend, stopped by the confirmed program"
        )
    }

    @Test
    fun `a basal program the pod refused leaves the suspend counted`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        f.pump.getPumpStatus("first status")
        // The pod takes the suspend (message 9), then reports an old sequence for the program.
        val normal = f.pod.answerFor
        var commands = 0
        f.pod.answerFor = { command ->
            commands++
            if (commands == 1) normal(command) else DashPumpOnFakePod.defaultStatus(lastProgrammingSequence = 2)
        }

        assertFalse(pump.setNewBasalProfile(PROFILE).success)

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 0.0, 80 * 3_600_000L, PumpSync.TemporaryBasalType.PUMP_SUSPEND)),
            pumpSync.calls,
            "the pod is suspended and runs no basal: the zero temp basal stays"
        )
    }

    private companion object {

        const val SENT_AT = 1_794_564_000_000L
        const val ONE_HOUR = 3_600_000L

        /** The unique id of the SetUniqueId answer of the fixture: 0x02420001. */
        const val SERIAL = "37879809"

        val PROFILE = BasalOnlyProfile(0 to 0.5, 43200 to 1.0)
    }
}
