package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.ui.DashOverviewAction
import app.aaps.pump.omnipod.dashctl.ui.DashPodWizardModel
import app.aaps.pump.omnipod.dashctl.ui.DashWizardActionState
import app.aaps.pump.omnipod.dashctl.ui.DashWizardStep
import app.aaps.pump.omnipod.dashctl.ui.FakeWizardHost
import app.aaps.pump.omnipod.dashctl.ui.NameResolver
import app.aaps.pump.omnipod.dashctl.ui.dashOverviewButtons
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The whole life of a pod on the iPhone, on the real driver against the simulated pod, with the
 * plugin parts wired as `IosDashDriverHost` wires them (the `PumpSync` recorder, the plugin rules,
 * the [IosDashPluginPump] the plugin delegates `Pump` to, and the wizard pod):
 *
 * 1. no pod: the plugin is not initialized and the status checker writes the "no pod" zero temp basal;
 * 2. the activation through the wizard state ([DashPodWizardModel], part 1 then part 2), as the
 *    screen drives it: then the plugin is initialized, the "no pod" temp basal is stopped and the
 *    pod is registered as Android does;
 * 3. what the loop sends, through [LoopQueue]: each queue call does what the queue command of
 *    `:implementation` does with `activePlugin.activePump` (`CommandBolus.deliverTreatment`,
 *    `CommandTempBasalAbsolute.setTempBasalAbsolute`, `CommandCancelTempBasal.cancelTempBasal`,
 *    `CommandSetProfile` (`isThisProfileSet`, then `setNewBasalProfile`), `CommandCustomCommand`,
 *    `CommandReadStatus`), after the connect of `QueueThread`. Each one is in `PumpSync` once, as Android;
 * 4. the deactivation through the wizard (queued, as Android): the pod is gone, the zero temp basal
 *    of a pod that delivers nothing runs, and the status checker adds nothing more.
 *
 * The real `CommandQueueImplementation` is not used here, on virtual time; the same life through the
 * real queue, the real `PumpSync` and the plugin of the app graph is `DashCommandQueueEndToEndTest` in
 * `:ios:shell`. The pump calls are the same ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashPodEndToEndTest {

    private val f = DashPumpOnFakePod()
    private val activating = ActivatingPodModel()
    private val pumpSync = RecordingDashPumpSync()
    private val notices = RecordingNotificationManager(NameTextResolver)
    private val launched = mutableListOf<suspend () -> Unit>()

    private val recorder = DashPumpSyncRecorder(
        pumpSync = pumpSync,
        aapsLogger = SilentPodLogger,
        countAsTakenWhileUnknown = f.stateManager::countAsTakenWhileUnknown,
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
    )

    private val queue = LoopQueue()

    private val rules = DashPluginRules(
        driver = f.pump,
        stateManager = f.stateManager,
        pumpSync = pumpSync,
        notificationManager = notices,
        readBoolean = { key: BooleanNonPreferenceKey -> key.defaultValue },
        readInt = { key: IntNonPreferenceKey -> key.defaultValue },
        rh = NameTextResolver,
        aapsLogger = SilentPodLogger,
        commandQueue = { queue },
        rxBus = { RecordingRxBus() },
        uiInteraction = { RecordingUiInteraction() },
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
        launch = { launched += it },
        now = { f.nowMs },
    )

    /** The `Pump` of the plugin, the active pump of this test. */
    private val activePump: Pump = IosDashPluginPump(f.pump, f.stateManager, recorder, rules, SilentPodLogger, now = { f.nowMs })

    /** The pod work on the test thread, so it runs on the virtual time of the test (the app uses the IO dispatcher). */
    private val wizardPod = IosDashWizardPod(rules = rules, stateManager = f.stateManager, commandQueue = { queue }, openRadio = {}, podContext = EmptyCoroutineContext)

    init {
        f.onOutcome = {
            recorder.onOutcome(it)
            rules.onOutcome(it)
        }
        f.onSent = recorder::onCommandSent
        f.nowMs = START
        f.answerAsActivatingPod(activating)
        pumpSync.expectedProfile = PROFILE
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
    }

    /**
     * The queue as the loop reaches it: each call connects as `QueueThread` does, then makes the pump
     * call of the matching queue command, on [activePump]. Every call is kept in [queued].
     */
    private inner class LoopQueue : RecordingCommandQueue() {

        private suspend fun run(name: String, block: suspend () -> PumpEnactResult): PumpEnactResult {
            queued += name
            activePump.connect(name)
            assertTrue(activePump.isConnected(), "the queue waits for the connection before $name")
            return block()
        }

        override suspend fun bolus(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
            run("bolus") { activePump.deliverTreatment(detailedBolusInfo) }

        /**
         * `tempBasalAbsolute` without its `Profile` argument, which `CommandTempBasalAbsolute` does not
         * give to the pump either (the loop passes `enforceNew = false`).
         */
        suspend fun tempBasal(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
            run("tempBasalAbsolute") { activePump.setTempBasalAbsolute(absoluteRate, durationInMinutes, enforceNew, tbrType) }

        override suspend fun cancelTempBasal(enforceNew: Boolean, autoForced: Boolean): PumpEnactResult =
            run("cancelTempBasal") { activePump.cancelTempBasal(enforceNew) }

        override suspend fun customCommand(customCommand: CustomCommand): PumpEnactResult =
            run("customCommand(${customCommand.statusDescription})") { activePump.executeCustomCommand(customCommand) ?: DashPumpEnactResult(success = false) }

        override suspend fun readStatus(reason: String): PumpEnactResult = run("readStatus($reason)") {
            activePump.getPumpStatus(reason)
            DashPumpEnactResult(success = true)
        }

        /** `CommandSetProfile`: nothing when the profile is already set, else the basal program. */
        suspend fun setProfile(profile: PumpProfile): PumpEnactResult = run("setProfile") {
            if (activePump.isThisProfileSet(profile)) DashPumpEnactResult(success = true, enacted = false)
            else activePump.setNewBasalProfile(profile)
        }
    }

    private suspend fun runLaunched() {
        val work = launched.toList()
        launched.clear()
        work.forEach { it() }
    }

    private fun TestScope.wizard() = DashPodWizardModel(wizardPod, FakeWizardHost(), NameResolver, SilentPodLogger, this)

    /**
     * The action of the page shown, run to its end; then Next. The activation waits for the prime and
     * the cannula on virtual time, so the clock moves by a bounded window (never to idle).
     */
    private fun TestScope.runActionAndGoOn(model: DashPodWizardModel, step: DashWizardStep) {
        assertEquals(step, model.currentStep.value)
        model.executeAction()
        advanceTimeBy(ACTION_WINDOW_MS)
        runCurrent()
        assertEquals(DashWizardActionState.Success, model.actionState.value, "$step")
        model.moveToNext()
    }

    private fun bolusRecords() = pumpSync.calls.filter { it.startsWith("bolus(") }
    private fun tempBasalRecords() = pumpSync.calls.filter { it.startsWith("tempBasal(") }

    @Test
    fun `a pod is activated from the wizard runs the loop commands once each and is deactivated`() = runTest {
        // 1. No pod.
        assertFalse(activePump.isInitialized(), "no pod: the loop must not use this pump")
        rules.statusCheck()
        assertEquals(
            listOf("tempBasal(timestamp=$START, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$START, pumpType=OMNIPOD_DASH, serial=4241)"),
            pumpSync.calls,
            "the no-pod zero temp basal of Android"
        )
        val noPod = dashOverviewButtons(f.stateManager, queueEmpty = true, nowMs = f.nowMs) { false }.associateBy { it.action }
        assertTrue(noPod.getValue(DashOverviewAction.ACTIVATE_POD).visible)

        // 2. The activation, as the screen drives the wizard.
        val activation = wizard()
        activation.prepare()
        activation.startActivation()
        assertEquals(DashWizardStep.START_POD_ACTIVATION, activation.currentStep.value)
        activation.moveToNext()
        f.nowMs = START + 60_000
        runActionAndGoOn(activation, DashWizardStep.INITIALIZE_POD)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, f.stateManager.activationProgress)
        assertEquals(DashWizardStep.ATTACH_POD, activation.currentStep.value)
        activation.moveToNext()
        f.nowMs = START + 120_000
        runActionAndGoOn(activation, DashWizardStep.INSERT_CANNULA)
        assertEquals(DashWizardStep.POD_ACTIVATED, activation.currentStep.value)
        activation.completeActivation()
        runCurrent()
        assertTrue(activation.finished.value)

        assertEquals(ActivationProgress.COMPLETED, f.stateManager.activationProgress)
        assertTrue(activePump.isInitialized(), "the pod runs: the loop may use the pump")
        // Note: as on Android, a new pod gets the id controller + 1 (`Ids`), which is 4241, the same
        // text as the "no pod" serial. So the serials below cannot tell the two apart; the order and
        // the times of the records do.
        val serial = f.stateManager.uniqueId.toString()
        assertEquals(serial, activePump.serialNumber())
        assertEquals(
            listOf(
                "stopTempBasal(timestamp=${START + 120_000}, endPumpId=${START + 120_000}, pumpType=OMNIPOD_DASH, serial=4241)",
                "connectNewPump",
                "therapyEvent(timestamp=${START + 120_000}, type=CANNULA_CHANGE, serial=$serial)",
                "therapyEvent(timestamp=${START + 120_000}, type=INSULIN_CHANGE, serial=$serial)",
            ),
            pumpSync.calls.drop(1),
            "after part 2, as Android"
        )
        // The status read Android queues after part 2.
        runLaunched()
        assertEquals(listOf("readStatus(omnipod_dash_ios_pod_activated)"), queue.queued)

        // From here the pod answers as a running pod, with the sequence the activation left.
        f.lastProgrammingSequence = activating.lastProgrammingSequence
        f.basalRunning = true
        f.answerWithStatus()
        pumpSync.calls.clear()

        // 3. The loop.
        f.nowMs = START + 180_000
        val bolus = queue.bolus(DetailedBolusInfo().apply {
            insulin = 0.5
            bolusType = BS.Type.SMB
        })
        assertTrue(bolus.success, bolus.comment)
        assertEquals(listOf(bolusLine(START + 180_000, 0.5, BS.Type.SMB, serial)), bolusRecords(), "one SMB record, at the asked amount")
        assertEquals(1, pumpSync.bolusRecords.size)

        f.nowMs = START + 240_000
        val tbr = queue.tempBasal(1.5, 30, false, PumpSync.TemporaryBasalType.NORMAL)
        assertTrue(tbr.success, tbr.comment)
        assertEquals(
            listOf("tempBasal(timestamp=${START + 240_000}, rate=1.5, duration=1800000, isAbsolute=true, type=NORMAL, pumpId=${START + 240_000}, pumpType=OMNIPOD_DASH, serial=$serial)"),
            tempBasalRecords(),
            "one temp basal record"
        )

        f.nowMs = START + 300_000
        f.expectedTempBasalRunning = true
        val cancel = queue.cancelTempBasal(enforceNew = false, autoForced = false)
        assertTrue(cancel.success, cancel.comment)
        assertEquals(
            listOf("stopTempBasal(timestamp=${START + 300_000}, endPumpId=${START + 300_000}, pumpType=OMNIPOD_DASH, serial=$serial)"),
            pumpSync.calls.filter { it.startsWith("stopTempBasal(") },
            "one stop"
        )

        f.nowMs = START + 360_000
        val newProfile = BasalOnlyProfile(0 to 0.8, 6 * 3600 to 1.2)
        val profile = queue.setProfile(newProfile)
        assertTrue(profile.success, profile.comment)
        assertTrue(activePump.isThisProfileSet(newProfile), "the pod runs the new program")
        val programs = f.pod.commands.count { DashPumpOnFakePod.isBasalProgram(it) }
        assertEquals(2, programs, "the program of part 2, then the new profile")
        assertTrue(queue.setProfile(newProfile).success)
        assertEquals(programs, f.pod.commands.count { DashPumpOnFakePod.isBasalProgram(it) }, "a profile already set is not sent again")

        // The whole loop part, as Android writes it: the profile change suspends the pod first (a
        // PUMP_SUSPEND temp basal when the suspend is sent) and the confirmed program ends it.
        val t = START + 360_000
        assertEquals(
            listOf(
                bolusLine(START + 180_000, 0.5, BS.Type.SMB, serial),
                "tempBasal(timestamp=${START + 240_000}, rate=1.5, duration=1800000, isAbsolute=true, type=NORMAL, pumpId=${START + 240_000}, pumpType=OMNIPOD_DASH, serial=$serial)",
                "stopTempBasal(timestamp=${START + 300_000}, endPumpId=${START + 300_000}, pumpType=OMNIPOD_DASH, serial=$serial)",
                "tempBasal(timestamp=$t, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$t, pumpType=OMNIPOD_DASH, serial=$serial)",
                "stopTempBasal(timestamp=$t, endPumpId=$t, pumpType=OMNIPOD_DASH, serial=$serial)",
            ),
            pumpSync.calls,
            "each command once, nothing written twice"
        )

        // 4. The deactivation, through the wizard and the queue.
        val running = dashOverviewButtons(f.stateManager, queueEmpty = true, nowMs = f.nowMs) { false }.associateBy { it.action }
        assertTrue(running.getValue(DashOverviewAction.DEACTIVATE_POD).visible)
        pumpSync.calls.clear()
        f.nowMs = START + 420_000
        val deactivation = wizard()
        deactivation.prepare()
        deactivation.startDeactivation()
        deactivation.moveToNext()
        runActionAndGoOn(deactivation, DashWizardStep.DEACTIVATE_POD)
        assertEquals(DashWizardStep.POD_DEACTIVATED, deactivation.currentStep.value)
        assertTrue(queue.queued.any { it.startsWith("customCommand(") }, "the deactivation goes through the queue: ${queue.queued}")

        assertFalse(activePump.isInitialized(), "no pod any more")
        assertEquals(ActivationProgress.NOT_STARTED, f.stateManager.activationProgress)
        val zero = pumpSync.expectedTemporaryBasal
        assertEquals(0.0, zero?.rate, "${pumpSync.calls}")
        assertEquals(PumpSync.TemporaryBasalType.PUMP_SUSPEND, zero?.type, "${pumpSync.calls}")
        assertEquals(1, tempBasalRecords().size, "${pumpSync.calls}")

        // The status checker: a zero temp basal runs already, so nothing more is written.
        f.nowMs = START + 480_000
        rules.statusCheck()
        assertEquals(1, tempBasalRecords().size, "${pumpSync.calls}")
    }

    private fun bolusLine(at: Long, units: Double, type: BS.Type, serial: String) =
        "bolus(timestamp=$at, amount=$units, type=$type, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$serial)"

    private companion object {

        const val START = 1_700_000_000_000L

        /** Longer than every wait of an activation step (the prime waits about one minute). */
        const val ACTION_WINDOW_MS = 10 * 60_000L

        /** The profile AAPS expects; part 2 and the resume read it. */
        val PROFILE = BasalOnlyProfile(0 to 1.0)
    }
}
