package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.rx.events.EventProfileChangeRequested
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeliverBasalCorrection
import app.aaps.pump.omnipod.dashctl.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.queue.command.CommandUpdateAlertConfiguration
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The [IosDashPluginPump] the iOS plugin gives to the app, on the real driver against the simulated
 * pod, with the `PumpSync` recorder and [DashPluginRules] wired as `IosDashDriverHost` wires them.
 * Each case is a rule of Android `OmnipodDashPumpPlugin`: what it answers itself, what it writes to
 * `PumpSync` and when, and what it does around a command.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7 the first command and 8 its ACK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosDashPluginPumpTest {

    private val f = DashPumpOnFakePod()
    private val pumpSync = RecordingDashPumpSync()
    private val notices = RecordingNotificationManager(NameTextResolver)
    private val queue = RecordingCommandQueue()
    private val bus = RecordingRxBus()
    private val ui = RecordingUiInteraction()

    /** The user settings by key; a key that is not here reads its default, as `Preferences`. */
    private val settings = mutableMapOf<String, Any>()

    /** Queue work the rules started on their own (Android `pluginScope.launch`); run by [runLaunched]. */
    private val launched = mutableListOf<suspend () -> Unit>()

    private val recorder = DashPumpSyncRecorder(
        pumpSync = pumpSync,
        aapsLogger = SilentPodLogger,
        countAsTakenWhileUnknown = f.stateManager::countAsTakenWhileUnknown,
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
    )

    private val rules = DashPluginRules(
        driver = f.pump,
        stateManager = f.stateManager,
        pumpSync = pumpSync,
        notificationManager = notices,
        readBoolean = { key: BooleanNonPreferenceKey -> settings[key.key] as? Boolean ?: key.defaultValue },
        readInt = { key: IntNonPreferenceKey -> settings[key.key] as? Int ?: key.defaultValue },
        rh = NameTextResolver,
        aapsLogger = SilentPodLogger,
        commandQueue = { queue },
        rxBus = { bus },
        uiInteraction = { ui },
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
        launch = { launched += it },
        now = { f.nowMs },
    )

    /** The wall clock [IosDashPluginPump] reads for the basal rate; the driver reads `f.nowMs`. */
    private var clockMs = SENT_AT

    private val pump = IosDashPluginPump(f.pump, f.stateManager, recorder, rules, SilentPodLogger, now = { clockMs })

    init {
        f.onOutcome = {
            recorder.onOutcome(it)
            rules.onOutcome(it)
        }
        f.onSent = recorder::onCommandSent
        f.nowMs = SENT_AT
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private suspend fun runLaunched() {
        val work = launched.toList()
        launched.clear()
        work.forEach { it() }
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
        pump.getPumpStatus("first status")
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

    // ---------------------------------------------------------------- PumpSync: bolus

    @Test
    fun `a confirmed SMB is written once when sent at the asked amount with its type`() = runTest {
        f.pairAndConnect()

        val result = pump.deliverTreatment(bolus(0.5, BS.Type.SMB))

        assertTrue(result.success, result.comment)
        assertEquals(listOf(bolusLine(SENT_AT, 0.5, BS.Type.SMB)), pumpSync.calls)
        assertEquals(mapOf(SENT_AT to (0.5 to BS.Type.SMB)), pumpSync.bolusRecords)
    }

    @Test
    fun `a bolus the pod refused is written when sent then set to 0 U as Android`() = runTest {
        f.pairAndConnect()
        f.pod.answerFor = { DashPumpOnFakePod.defaultStatus(lastProgrammingSequence = 9) }

        assertFalse(pump.deliverTreatment(bolus(2.0)).success)

        assertEquals(listOf(bolusLine(SENT_AT, 2.0, BS.Type.NORMAL), bolusLine(SENT_AT, 0.0, null)), pumpSync.calls)
        assertEquals(mapOf(SENT_AT to (0.0 to BS.Type.NORMAL)), pumpSync.bolusRecords, "one record, nothing counted")
    }

    @Test
    fun `a bolus not confirmed is counted from its send and its late SUCCESS adds nothing`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        assertTrue(pump.deliverTreatment(bolus(1.5, BS.Type.SMB)).success)
        assertEquals(listOf(bolusLine(SENT_AT, 1.5, BS.Type.SMB)), pumpSync.calls, "Android: written when sent")

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open bolus")

        assertEquals(listOf(bolusLine(SENT_AT, 1.5, BS.Type.SMB)), pumpSync.calls)
    }

    @Test
    fun `a late SUCCESS after a crash is recorded once`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertTrue(pump.deliverTreatment(bolus(1.5, BS.Type.SMB)).success)

        // The app stops: a new recorder knows nothing of the send. The bolus is decided late after it.
        val afterRestart = DashPumpSyncRecorder(pumpSync, SilentPodLogger, f.stateManager::countAsTakenWhileUnknown, serialNumber = { SERIAL })
        f.onOutcome = afterRestart::onOutcome
        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open bolus")

        assertEquals(mapOf(SENT_AT to (1.5 to BS.Type.SMB)), pumpSync.bolusRecords, "one record at the send time, its type kept")
    }

    @Test
    fun `a refused bolus call does not change the type of the open bolus`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertTrue(pump.deliverTreatment(bolus(3.0, BS.Type.NORMAL)).success)

        // The open bolus cannot be decided with the link down, so the SMB is refused.
        assertFalse(pump.deliverTreatment(bolus(0.4, BS.Type.SMB)).success)

        assertEquals(listOf(bolusLine(SENT_AT, 3.0, BS.Type.NORMAL)), pumpSync.calls, "the SMB was never sent nor written")
        assertEquals(1, f.bolusCommands())
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
        assertEquals(mapOf(SENT_AT to (0.5 to BS.Type.NORMAL)), pumpSync.bolusRecords)
    }

    @Test
    fun `a pod discarded with an open bolus keeps it counted at the asked amount`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertTrue(pump.deliverTreatment(bolus(3.0, BS.Type.SMB)).success)

        rules.discardPod()

        assertEquals(listOf(bolusLine(SENT_AT, 3.0, BS.Type.SMB)), pumpSync.calls, "Android wrote it when it was sent")
        assertEquals("4241", pump.serialNumber(), "the pod is forgotten after the record")
        assertTrue("dismiss(OMNIPOD_POD_FAULT)" in notices.calls)
    }

    @Test
    fun `an SMB not confirmed gets the notice of Android with the sound setting`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        settings[DashSoundKey.SoundUncertainSmbNotification.key] = false

        assertTrue(pump.deliverTreatment(bolus(0.35, BS.Type.SMB)).success)

        assertEquals(listOf("post(OMNIPOD_UNCERTAIN_SMB, omnipod_dash_ios_smb_uncertain[format_insulin_units[0.35]], sound=null)"), notices.posts())
        assertTrue(ui.alarms.isEmpty())
    }

    @Test
    fun `a normal bolus not confirmed runs the alarm of Android`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        assertTrue(pump.deliverTreatment(bolus(1.0)).success)

        assertEquals(listOf("alarm(omnipod_dash_ios_bolus_uncertain, omnipod_dash_ios_error, sound=BOLUS_ERROR)"), ui.alarms)
    }

    // ---------------------------------------------------------------- PumpSync: temp basal, suspend

    @Test
    fun `a temp basal is written when sent with its type and a cancel stops it`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("first status")

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
        assertTrue(ui.alarms.isEmpty(), "AAPS and the pod agree: verifyPumpState is quiet")
    }

    @Test
    fun `a temp basal the pod refused is invalidated`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("first status")
        // The temp basal goes with sequence 2; the pod reports another one as its last programming command.
        f.pod.answerFor = { DashPumpOnFakePod.defaultStatus(lastProgrammingSequence = 9) }

        assertFalse(pump.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL).success)

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 1.0, 30 * 60_000L, PumpSync.TemporaryBasalType.NORMAL), "invalidateTemporaryBasalWithPumpId($SENT_AT)"),
            pumpSync.calls
        )
    }

    @Test
    fun `a zero temp basal not confirmed is not counted until the pod confirms it at its send time`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("first status")
        // Status 7-8, then the temp basal is message 9.
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(pump.setTempBasalAbsolute(0.0, 60, false, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND).success)
        // Deliberate difference from Android (OmnipodKit, assume more insulin): not counted while unknown.
        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())
        assertEquals(listOf("post(OMNIPOD_TBR_ALERTS, omnipod_dash_ios_tbr_unconfirmed, sound=BOLUS_ERROR)"), notices.posts())

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open temp basal")

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 0.0, 60 * 60_000L, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)),
            pumpSync.calls,
            "the late SUCCESS writes it at its send time with the type of its call"
        )
        assertTrue("dismiss(OMNIPOD_TBR_ALERTS)" in notices.calls)
    }

    /**
     * Deliberate difference from Android (OmnipodKit `resolveAnyPendingCommandWithUncertainty`): a zero
     * temp basal left open when the pod is discarded is never counted, so the basal the pod may have
     * given stays counted. Fails before the fix: the zero temp basal was written when it was sent.
     */
    @Test
    fun `a zero temp basal left open when the pod is discarded is not counted`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("first status")
        f.pod.dropLinkWhileAnswering = 9 to 0
        assertFalse(pump.setTempBasalAbsolute(0.0, 60, false, PumpSync.TemporaryBasalType.NORMAL).success)

        rules.discardPod()

        assertTrue(pumpSync.calls.none { it.startsWith("tempBasal(timestamp=$SENT_AT") }, pumpSync.calls.toString())
    }

    @Test
    fun `a new basal program writes the suspend when sent then ends it and turns the suspend alert off`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("first status")
        queue.running = Command.CommandType.BASAL_PROFILE

        assertTrue(pump.setNewBasalProfile(PROFILE).success)

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 0.0, 80 * 3_600_000L, PumpSync.TemporaryBasalType.PUMP_SUSPEND), stopLine(SENT_AT)),
            pumpSync.calls,
            "Android: PUMP_SUSPEND temp basal for the suspend, stopped by the confirmed program"
        )
        runLaunched()
        assertEquals(listOf("customCommand(DISABLE SUSPEND ALERTS)"), queue.queued)
        assertTrue(bus.events.none { it is EventProfileChangeRequested }, "the queue runs this program: no late confirmation")
    }

    @Test
    fun `a basal program confirmed late asks for the profile again`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("first status")
        // Status 7-8, suspend 9-10, the program is message 11.
        f.pod.dropLinkWhileAnswering = 11 to 0
        assertFalse(pump.setNewBasalProfile(PROFILE).success)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open program")

        assertTrue(bus.events.any { it is EventProfileChangeRequested }, "Android: we are late-confirming this command")
    }

    /**
     * Android `failWhenUnconfirmed`: a program that stays open gives Android's text as the comment, and
     * the queue shows it in the FAILED_UPDATE_PROFILE notice.
     */
    @Test
    fun `a basal program not confirmed gives the text of Android`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("first status")
        // Status 7-8, suspend 9-10, the program is message 11.
        f.pod.dropLinkWhileAnswering = 11 to 0

        val result = pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success)
        assertEquals("omnipod_dash_ios_basal_profile_might_have_failed", result.comment)
    }

    /**
     * Android stores the program of a time change as SET_TIME and never asks for a profile change when
     * a status confirms it later (`handleCommandConfirmation` checks the history type).
     */
    @Test
    fun `a time change program confirmed late asks for no profile change as Android`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")
        pumpSync.expectedProfile = PROFILE
        // Status 7-8, suspend 9-10, the program is message 11: its answer is lost.
        f.pod.dropLinkWhileAnswering = 11 to 0
        assertFalse(pump.executeCustomCommand(CommandHandleTimeChange(requestedByUser = true))!!.success)
        assertEquals(DashCommandKind.BASAL_PROGRAM, f.stateManager.activeCommand?.kind)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        pump.getPumpStatus("decide the open program")

        assertNull(f.stateManager.activeCommand, "the program is decided")
        assertTrue(bus.events.none { it is EventProfileChangeRequested }, "Android SET_TIME, also when confirmed late")
    }

    @Test
    fun `a basal program the pod refused leaves the suspend counted`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("first status")
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
        runLaunched()
        assertTrue(queue.queued.isEmpty(), "no suspend alert to turn off: the program was refused")
    }

    // ---------------------------------------------------------------- after each command

    @Test
    fun `a temp basal AAPS expects that the pod does not run is stopped in AAPS`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pumpSync.expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = SENT_AT - ONE_HOUR, duration = 2 * ONE_HOUR, rate = 2.0, isAbsolute = true,
            type = PumpSync.TemporaryBasalType.NORMAL, id = 77L, pumpId = 5L
        )

        pump.getPumpStatus("status")

        assertEquals(listOf("stopTempBasal(timestamp=$SENT_AT, endPumpId=77, pumpType=OMNIPOD_DASH, serial=$SERIAL)"), pumpSync.calls)
        assertEquals(listOf("alarm(omnipod_dash_ios_tbr_out_of_sync, omnipod_dash_ios_error, sound=BOLUS_ERROR)"), ui.alarms)
    }

    @Test
    fun `a temp basal the pod runs that AAPS does not expect is stopped on the pod`() = runTest {
        f.pairAndConnect()
        f.tempBasalRunning = true

        pump.getPumpStatus("status")

        assertFalse(f.tempBasalRunning, "Android verifyPumpState cancels it on the pod")
        assertEquals(listOf("stopTempBasal(timestamp=$SENT_AT, endPumpId=$SENT_AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)"), pumpSync.calls)
        assertEquals(1, ui.alarms.size)
    }

    @Test
    fun `a faulted pod gets the zero temp basal and one fault notice and announcement`() = runTest {
        f.pairAndConnect()
        f.faultAlarmType = DashPumpOnFakePod.OCCLUDED

        pump.getPumpStatus("status")
        f.nowMs = SENT_AT + 60_000L
        pump.getPumpStatus("status again")

        assertEquals(
            listOf(
                "tempBasal(timestamp=$SENT_AT, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$SENT_AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)",
                "announcement(omnipod_dash_ios_pod_fault_with_ref[020, ALARM_OCCLUDED, $OCCLUDED_REF])"
            ),
            pumpSync.calls,
            "once: the zero temp basal runs, and the fault is synced"
        )
        assertEquals(listOf("post(OMNIPOD_POD_FAULT, omnipod_dash_ios_pod_fault_with_ref[020, ALARM_OCCLUDED, $OCCLUDED_REF], sound=BOLUS_ERROR)"), notices.posts())
        assertTrue(f.stateManager.alarmSynced)
    }

    /**
     * Deliberate difference from Android (insulin counted wrong), as OmnipodKit (Trio)
     * `setDeliveryStoppedAt(faultEventTimeSinceActivation)`: the phone sees the fault two hours late, so
     * the zero temp basal starts at the fault time the pod gives, not now. Fails before the fix: it
     * started now, and two hours of basal the pod never gave were counted.
     */
    @Test
    fun `a fault seen late gets the zero temp basal from the fault time of the pod`() = runTest {
        f.pairAndConnect()
        f.faultAlarmType = DashPumpOnFakePod.OCCLUDED
        // The alarm status reports 0x0195 minutes since activation; the fault came 120 minutes before.
        f.faultAlarmMinutes = 0x0195 - 120
        f.nowMs = SENT_AT + 2 * ONE_HOUR

        pump.getPumpStatus("status")

        assertEquals(
            "tempBasal(timestamp=$SENT_AT, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$SENT_AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)",
            pumpSync.calls.first()
        )
    }

    /** `PumpSync` ends a running temp basal only at a later time: the zero one never starts before it. */
    @Test
    fun `the zero temp basal of a fault starts after a temp basal AAPS started after the fault`() = runTest {
        f.pairAndConnect()
        val tbrStart = SENT_AT + 30 * 60_000L
        pumpSync.expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = tbrStart, duration = ONE_HOUR, rate = 2.0, isAbsolute = true, type = PumpSync.TemporaryBasalType.NORMAL, id = 9L, pumpId = tbrStart
        )
        f.faultAlarmType = DashPumpOnFakePod.OCCLUDED
        f.faultAlarmMinutes = 0x0195 - 120
        f.nowMs = SENT_AT + 2 * ONE_HOUR

        pump.getPumpStatus("status")

        val at = tbrStart + 1
        assertEquals(
            "tempBasal(timestamp=$at, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)",
            pumpSync.calls.first()
        )
    }

    /**
     * Deliberate difference from Android, as OmnipodKit `PodState.updateDeliveryStatus`: a bolus the pod
     * delivers that the app does not track is written once, at the units left, and a fault that ends it
     * corrects that one record. Fails before the fix: no record, the bolus was never counted.
     */
    @Test
    fun `a bolus the pod reports that the app does not track is written once and a fault corrects it`() = runTest {
        f.pairAndConnect()
        f.bolusRunning = true
        f.bolusPulsesRemaining = 30

        pump.getPumpStatus("a bolus the app did not send")
        pump.getPumpStatus("again")
        assertEquals(mapOf(SENT_AT to (1.5 to BS.Type.NORMAL)), pumpSync.bolusRecords)

        f.bolusPulsesRemaining = 10
        f.faultAlarmType = DashPumpOnFakePod.OCCLUDED
        f.nowMs = SENT_AT + 60_000L
        pump.getPumpStatus("fault")

        assertEquals(mapOf(SENT_AT to (1.0 to BS.Type.NORMAL)), pumpSync.bolusRecords, "one record, corrected to what went in")
    }

    /**
     * As above, but the first write to `PumpSync` fails. The mark of the bolus stays, so the next
     * status writes it. Fails before the fix: the mark was cleared anyway and the bolus was never counted.
     */
    @Test
    fun `an untracked bolus whose write failed is written at the next status`() = runTest {
        f.pairAndConnect()
        f.bolusRunning = true
        f.bolusPulsesRemaining = 30
        pumpSync.bolusWritesToFail = 1

        pump.getPumpStatus("a bolus the app did not send, PumpSync fails")
        assertTrue(pumpSync.bolusRecords.isEmpty())
        assertEquals(SENT_AT, f.stateManager.untrackedBolusToRecord()?.createdAtMs, "the mark stays")

        pump.getPumpStatus("again")
        assertEquals(mapOf(SENT_AT to (1.5 to BS.Type.NORMAL)), pumpSync.bolusRecords)
        assertNull(f.stateManager.untrackedBolusToRecord(), "written: the mark is cleared")
    }

    @Test
    fun `no fault notice while a deactivation waits in the queue`() = runTest {
        f.pairAndConnect()
        f.faultAlarmType = DashPumpOnFakePod.OCCLUDED
        queue.inQueue += CommandDeactivatePod::class

        pump.getPumpStatus("status")

        assertTrue(notices.posts().isEmpty())
        assertTrue(pumpSync.calls.any { it.startsWith("announcement") }, "the announcement is still written, as Android")
    }

    @Test
    fun `a stop of an unexpected temp basal that is not confirmed gets the notice of Android`() = runTest {
        f.pairAndConnect()
        f.tempBasalRunning = true
        // Status 7-8, the stop of the temp basal is message 9: its answer is lost.
        f.pod.dropLinkWhileAnswering = 9 to 0

        pump.getPumpStatus("status")

        assertTrue(
            "post(OMNIPOD_TBR_ALERTS, omnipod_dash_ios_cancel_tbr_before_unconfirmed, sound=BOLUS_ERROR)" in notices.posts(),
            "Android observeNoActiveTempBasal: cancelling the temp basal might have failed. Posts: ${notices.posts()}"
        )
    }

    @Test
    fun `a status read skipped before the activation is completed runs no check as Android`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("first status")
        // Part 2 programmed the basal, but the cannula step was not done.
        f.stateManager.overwriteForTest { it.copy(activationProgress = ActivationProgress.PROGRAMMED_BASAL) }
        pumpSync.expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = SENT_AT - ONE_HOUR, duration = 80 * ONE_HOUR, rate = 0.0, isAbsolute = true,
            type = PumpSync.TemporaryBasalType.PUMP_SUSPEND, id = 12L, pumpId = 5L
        )
        val commands = f.pod.commands.size

        pump.getPumpStatus("KeepAlive")

        assertEquals(commands, f.pod.commands.size, "nothing read")
        assertTrue(ui.alarms.isEmpty(), "Android returns before verifyPumpState: no alarm")
        assertTrue(pumpSync.calls.isEmpty(), "the zero temp basal of no pod is not ended")
    }

    @Test
    fun `a bolus ended by a fault inside the bolus wait gets the zero temp basal and the fault notice at once`() = runTest {
        f.pairAndConnect()
        f.stateManager.overwriteForTest { it.copy(reportedPodStatus = PodStatus.ALARM, alarmType = AlarmType.ALARM_OCCLUDED) }
        val bolus = DashLastBolus(startTimeMs = SENT_AT, requestedUnits = 2.0, bolusUnitsRemaining = 1.5, deliveryComplete = true, createdAtMs = SENT_AT)

        // Android runs checkPodKaput at each status read of waitForBolusDeliveryToComplete.
        rules.onOutcome(DashBolusEndedByFault(bolus, AlarmType.ALARM_OCCLUDED, SENT_AT))

        assertEquals(
            listOf(
                tempBasalLine(SENT_AT, 0.0, 80 * ONE_HOUR, PumpSync.TemporaryBasalType.PUMP_SUSPEND),
                "announcement(omnipod_dash_ios_pod_fault[020, ALARM_OCCLUDED])"
            ),
            pumpSync.calls
        )
        assertEquals(listOf("post(OMNIPOD_POD_FAULT, omnipod_dash_ios_pod_fault[020, ALARM_OCCLUDED], sound=BOLUS_ERROR)"), notices.posts())
    }

    /** A running pod with its time zone stored, a first status read, then one pulse less basal than expected. */
    private suspend fun podThatGaveTooLittleBasal() {
        f.pairAndConnect()
        f.basalRunning = true
        f.expectedTempBasalRunning = false
        f.stateManager.updateTimeZone(f.nowMs)
        pump.getPumpStatus("status")
        f.stateManager.overwriteForTest { it.copy(basalExpected = it.basalExpected!! + 0.05) }
    }

    @Test
    fun `a confirmed temp basal queues a basal correction when the pod gave too little basal`() = runTest {
        podThatGaveTooLittleBasal()

        assertTrue(pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        runLaunched()

        assertEquals(listOf("customCommand(BASAL CORRECTION)"), queue.queued, "Android SET_TEMPORARY_BASAL confirmation")
    }

    @Test
    fun `a confirmed temp basal queues no second basal correction while one waits in the queue`() = runTest {
        podThatGaveTooLittleBasal()
        queue.inQueue += CommandDeliverBasalCorrection::class

        assertTrue(pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        runLaunched()

        assertTrue(queue.queued.isEmpty())
    }

    @Test
    fun `a confirmed cancel queues a basal correction when the pod gave too little basal`() = runTest {
        podThatGaveTooLittleBasal()
        f.tempBasalRunning = true
        f.expectedTempBasalRunning = true

        assertTrue(pump.cancelTempBasal(true).success)
        runLaunched()

        assertEquals(listOf("customCommand(BASAL CORRECTION)"), queue.queued, "Android CANCEL_TEMPORARY_BASAL confirmation")
    }

    @Test
    fun `no basal correction is queued when the basal given is as expected`() = runTest {
        podThatGaveTooLittleBasal()
        f.stateManager.overwriteForTest { it.copy(basalExpected = it.basalExpected!! - 0.05) }

        assertTrue(pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)
        runLaunched()

        assertTrue(queue.queued.isEmpty())
    }

    @Test
    fun `the basal correction is one 0_05 U bolus that is never written to PumpSync`() = runTest {
        podThatGaveTooLittleBasal()
        val commands = f.pod.commands.size

        val result = rules.executeCustomCommand(CommandDeliverBasalCorrection())

        assertTrue(result.success, result.comment)
        assertEquals(0.05, result.bolusDelivered)
        assertTrue(f.pod.commands.drop(commands).any { DashPumpOnFakePod.isBolus(it) }, "the pulse went to the pod")
        assertTrue(pumpSync.calls.none { it.startsWith("bolus") }, "Android never syncs a BASAL_CORRECTION: ${pumpSync.calls}")
        assertEquals(SENT_AT, f.stateManager.lastBasalCorrectionTime, "the cooldown starts before the send, as Android")
        assertTrue(f.stateManager.lastBolus?.basalCorrection == true)
        assertFalse(f.stateManager.basalCorrectionInProgress)
    }

    /**
     * The check and the cooldown of the basal correction write the state. A session that the held
     * connection sets up writes it too, with the command lock held. So the correction waits for the
     * lock before it checks: nothing is written while another holds it.
     */
    @Test
    fun `the basal correction check and cooldown wait for the command lock`() = runTest {
        podThatGaveTooLittleBasal()
        assertTrue(f.pump.tryLockLink())

        val correction = async { f.pump.deliverBasalCorrection() }
        runCurrent()
        assertNull(f.stateManager.lastBasalCorrectionTime, "no cooldown written while another holds the lock")
        assertFalse(correction.isCompleted)

        f.pump.unlockLink()
        val result = correction.await()

        assertTrue(result.enacted, result.comment)
        assertEquals(SENT_AT, f.stateManager.lastBasalCorrectionTime)
    }

    @Test
    fun `a basal correction no longer needed sends nothing`() = runTest {
        podThatGaveTooLittleBasal()
        f.stateManager.overwriteForTest { it.copy(basalExpected = it.basalExpected!! - 0.05) }
        val commands = f.pod.commands.size

        val result = rules.executeCustomCommand(CommandDeliverBasalCorrection())

        assertTrue(result.success)
        assertFalse(result.enacted, "Android: basal correction no longer appropriate")
        assertEquals(commands, f.pod.commands.size)
    }

    @Test
    fun `a second basal correction within 2 minutes sends nothing`() = runTest {
        podThatGaveTooLittleBasal()
        assertTrue(rules.executeCustomCommand(CommandDeliverBasalCorrection()).enacted)
        f.stateManager.overwriteForTest { it.copy(basalExpected = it.basalExpected!! + 0.05) }
        f.nowMs = SENT_AT + 60_000L
        val commands = f.pod.commands.size

        val result = rules.executeCustomCommand(CommandDeliverBasalCorrection())

        assertFalse(result.enacted, "Android: 2 minutes cooldown")
        assertEquals(commands, f.pod.commands.size)
    }

    @Test
    fun `a running pod in another time zone gets the time zone notice as Android`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")
        f.stateManager.updateTimeZone(f.nowMs)
        f.stateManager.overwriteForTest { it.copy(timeZoneOffsetMs = it.timeZoneOffsetMs!! + 3_600_000) }

        rules.updatePodWarnings()

        assertEquals(listOf("post(OMNIPOD_TIME_OUT_OF_SYNC, omnipod_dash_ios_time_zone_different, sound=null)"), notices.posts())
    }

    @Test
    fun `a running pod in the time zone of the phone gets no time zone notice`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")
        f.stateManager.updateTimeZone(f.nowMs)

        rules.updatePodWarnings()

        assertTrue(notices.posts().isEmpty(), "${notices.posts()}")
    }

    @Test
    fun `a basal program the pod took stores the time zone of the phone`() = runTest {
        f.pairAndConnect()
        pumpSync.expectedProfile = PROFILE
        assertNull(f.stateManager.timeZoneOffsetMs)

        assertTrue(pump.setNewBasalProfile(PROFILE).success)

        assertEquals(SENT_AT, f.stateManager.timeZoneUpdatedMs)
        assertTrue(f.stateManager.sameTimeZone(SENT_AT))
    }

    // ---------------------------------------------------------------- queue commands

    @Test
    fun `a resume confirmed by the pod ends the suspend and turns the suspend alert off`() = runTest {
        f.pairAndConnect(basalOfThisSession = false)
        pump.getPumpStatus("status: delivery suspended")
        pumpSync.expectedProfile = PROFILE

        val result = pump.executeCustomCommand(CommandResumeDelivery())

        assertTrue(result!!.success, result.comment)
        assertEquals(listOf(stopLine(SENT_AT)), pumpSync.calls)
        runLaunched()
        assertEquals(listOf("customCommand(DISABLE SUSPEND ALERTS)"), queue.queued)
        assertTrue("dismiss(OMNIPOD_POD_SUSPENDED)" in notices.calls)
    }

    @Test
    fun `a resume with no profile fails with nothing sent`() = runTest {
        f.pairAndConnect()
        val commands = f.pod.commands.size

        assertFalse(pump.executeCustomCommand(CommandResumeDelivery())!!.success)
        assertEquals(commands, f.pod.commands.size)
    }

    @Test
    fun `a time change sends the program again and asks for no profile change`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")
        pumpSync.expectedProfile = PROFILE

        assertTrue(pump.executeCustomCommand(CommandHandleTimeChange(requestedByUser = true))!!.success)

        assertEquals(mapProfileToBasalProgram(PROFILE, PumpType.OMNIPOD_DASH), f.stateManager.basalProgram)
        assertTrue(bus.events.none { it is EventProfileChangeRequested }, "Android SET_TIME")
    }

    @Test
    fun `the test beep goes to the pod`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("status")

        assertTrue(pump.executeCustomCommand(CommandPlayTestBeep())!!.success)
        assertEquals(DashCommandKind.PROGRAM_BEEPS, f.decided.last().command.kind)
    }

    @Test
    fun `the alert configuration reads the Android keys`() = runTest {
        f.pairAndConnect(basalOfThisSession = false)
        f.stateManager.updateExpirationAlertSettings(rules.alertSettings().expiration)
        f.stateManager.updateLowReservoirAlertSettings(rules.alertSettings().lowReservoir)

        val same = pump.executeCustomCommand(CommandUpdateAlertConfiguration())!!
        assertTrue(same.success && !same.enacted, "the defaults are what the pod has: nothing sent")

        settings[DashAlertIntKey.LowReservoirAlertUnits.key] = 30
        val changed = pump.executeCustomCommand(CommandUpdateAlertConfiguration())!!
        assertFalse(changed.success, "a changed setting must go to the pod; this fixture has no pod expiry, so it fails closed")
    }

    @Test
    fun `an unknown queue command fails as Android`() {
        val result = pump.executeCustomCommand(object : CustomCommand {
            override val statusDescription = "SUSPEND DELIVERY"
        })!!

        assertFalse(result.success)
    }

    @Test
    fun `a confirmed deactivation dismisses the fault notice`() = runTest {
        f.pairAndConnect()
        pump.getPumpStatus("status")

        assertTrue(pump.executeCustomCommand(CommandDeactivatePod())!!.success)
        assertTrue("dismiss(OMNIPOD_POD_FAULT)" in notices.calls)
        assertEquals("4241", pump.serialNumber())
    }

    @Test
    fun `a confirmed deactivation writes the PUMP_SUSPEND temp basal of the pod before the reset`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")

        assertTrue(pump.executeCustomCommand(CommandDeactivatePod())!!.success)

        assertEquals(
            listOf(tempBasalLine(SENT_AT, 0.0, 80 * ONE_HOUR, PumpSync.TemporaryBasalType.PUMP_SUSPEND)),
            pumpSync.calls,
            "Android checkPodKaput runs before podStateManager.reset(): no basal is counted from the deactivation on"
        )
    }

    @Test
    fun `a time change whose suspend is not confirmed gets the notice of Android`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        pump.getPumpStatus("status")
        pumpSync.expectedProfile = PROFILE
        // Status 7-8, the suspend is message 9: its answer is lost.
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(pump.executeCustomCommand(CommandHandleTimeChange(requestedByUser = true))!!.success)

        assertTrue(
            "post(FAILED_UPDATE_PROFILE, omnipod_dash_ios_suspend_unconfirmed, sound=BOLUS_ERROR)" in notices.posts(),
            "Android suspendDeliveryIfActive: suspend delivery is unconfirmed. Posts: ${notices.posts()}"
        )
    }

    // ---------------------------------------------------------------- status checker

    @Test
    fun `an open command with an idle queue gets a status read`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertTrue(pump.deliverTreatment(bolus(1.0)).success)

        rules.statusCheck()
        runLaunched()

        assertEquals(listOf("readStatus(omnipod_dash_ios_unconfirmed_command)"), queue.queued)
    }

    /**
     * Deliberate difference from Android (insulin counted wrong): a pod fault seen in the advertisement
     * (the fault watch of `DashHeldConnection`) queues a status read at once, as OmnipodKit
     * `omnipodDidDetectAlert`, so the alarm is handled now and not at the next loop.
     */
    @Test
    fun `a pod fault seen in the advertisement queues a status read at once`() = runTest {
        f.pairAndConnect()

        rules.onPodFaultAdvertised()
        runLaunched()

        assertEquals(listOf("readStatus(omnipod_dash_ios_pod_fault_seen)"), queue.queued)
    }

    @Test
    fun `a fault advertisement without an activated pod queues nothing`() = runTest {
        rules.onPodFaultAdvertised()
        runLaunched()

        assertTrue(queue.queued.isEmpty())
    }

    @Test
    fun `no status read while the queue is busy or no command is open`() = runTest {
        f.pairAndConnect()
        rules.refreshStatusOnUnacknowledgedCommands()
        f.pod.dropLinkWhileAnswering = 7 to 0
        assertTrue(pump.deliverTreatment(bolus(1.0)).success)
        queue.size = 1
        rules.refreshStatusOnUnacknowledgedCommands()
        runLaunched()

        assertTrue(queue.queued.isEmpty())
    }

    @Test
    fun `the pod warnings come at most every 15 minutes`() = runTest {
        rules.updatePodWarnings()
        f.nowMs = SENT_AT + 14 * 60_000L
        rules.updatePodWarnings()
        f.nowMs = SENT_AT + 16 * 60_000L
        rules.updatePodWarnings()

        assertEquals(
            listOf("post(OMNIPOD_POD_NOT_ATTACHED, omnipod_dash_ios_no_active_pod, sound=null)", "post(OMNIPOD_POD_NOT_ATTACHED, omnipod_dash_ios_no_active_pod, sound=null)"),
            notices.posts()
        )
    }

    @Test
    fun `a suspended pod gets the suspended notice with its sound setting`() = runTest {
        f.pairAndConnect(basalOfThisSession = false)
        pump.getPumpStatus("status: delivery suspended")
        settings[DashSoundKey.SoundDeliverySuspendedNotification.key] = false

        rules.updatePodWarnings()

        assertEquals(listOf("post(OMNIPOD_POD_SUSPENDED, omnipod_dash_ios_delivery_suspended, sound=null)"), notices.posts())
    }

    private companion object {

        const val SENT_AT = 1_794_564_000_000L
        const val ONE_HOUR = 3_600_000L

        /** The unique id of the SetUniqueId answer of the fixture: 0x02420001. */
        const val SERIAL = "37879809"

        val PROFILE = BasalOnlyProfile(0 to 0.5, 43200 to 1.0)

        /**
         * The PDM reference of the alarm status of the fixture with ALARM_OCCLUDED: category 17, byte 19 0,
         * alarm time 0 so 405 min / 60 = 6 h, 445 pulses = 22 U, 1023 pulses = 51 U, fault 020.
         */
        const val OCCLUDED_REF = "17-00006-02251-020"
    }
}
