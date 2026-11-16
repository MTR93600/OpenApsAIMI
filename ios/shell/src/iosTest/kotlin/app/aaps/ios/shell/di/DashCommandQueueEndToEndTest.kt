package app.aaps.ios.shell.di

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.ios.shell.IosAppStartup
import app.aaps.ios.shell.PluginStoreRegistry
import app.aaps.ios.shell.prefs.TestStorage
import app.aaps.plugins.source.NSClientSourcePlugin
import app.aaps.plugins.sync.nsclientV3.ws.NsSocket
import app.aaps.plugins.sync.nsclientV3.ws.NsSocketFactory
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection
import app.aaps.pump.omnipod.dashctl.comm.connection.DashLinkClient
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.scan.DashPodScanner
import app.aaps.pump.omnipod.dashctl.driver.RunningPodModel
import app.aaps.pump.omnipod.dashctl.driver.SimulatedDashPod
import app.aaps.pump.omnipod.dashctl.history.DashPodHistoryStore
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosDashPlatform
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosDashPluginPump
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosOmnipodDashPlugin
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.shared.clientbindings.ClientGraphBindings
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A pod's whole life on the iPhone through the REAL app: the real `CommandQueueImplementation` and
 * its queue thread (`CommandExecutor`: connect, wait for `isConnected`, the linger and the
 * disconnect), the real `PumpSyncImplementation` on a real database, the real config builder and
 * plugin list, and the real Dash plugin of the app graph ([DashQueueTestGraph]). Only the iPhone side
 * of the plugin is replaced ([SimulatedPodPlatform]): a simulated pod ([SimulatedDashPod]) behind a
 * fake BLE central that behaves as CoreBluetooth, a memory store in place of the Keychain, and short
 * waits in place of the prime, cannula and bolus waits.
 *
 * In order, as a user and the loop would do it:
 * 1. the plugin list starts as the app starts it, and the user selects Dash as the pump;
 * 2. a profile switch: with no pod the queue sets it (Android: success, not enacted), so the
 *    effective profile exists; the status checker writes the "no pod" zero temp basal;
 * 3. the activation, part 1 and part 2, as the wizard calls it: the plugin is then initialized, the
 *    "no pod" temp basal is stopped, the cannula and insulin changes are recorded, and the status read
 *    Android queues after part 2 goes through the queue;
 * 4. through the queue: an SMB, a bolus, a temp basal, its cancel, and a profile change (the pod is
 *    suspended, then gets the new basal program);
 * 5. the deactivation, through the queue as the wizard sends it: then the "no pod" zero temp basal.
 *
 * After each step the records in the database are compared with what Android writes: one record per
 * command, with the time the command was made as its pump id.
 *
 * Real time: about 20 s, most of it the queue thread (1 s steps while it connects).
 */
class DashCommandQueueEndToEndTest {

    /** The real socket is a Swift class the app hands in. Nothing here connects to Nightscout. */
    private object NoSockets : NsSocketFactory {

        override fun create(url: String): NsSocket? = null
    }

    /** The pod state in memory, in place of the Keychain (a test binary has none). */
    private class MemoryPodStateStore : DashPodStateStore {

        private var saved: DashPodState? = null
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
        }

        override fun clear() {
            saved = null
        }
    }

    /** The pod history in memory, in place of the user defaults. */
    private class MemoryHistoryStore : DashPodHistoryStore {

        private var saved: ByteArray? = null
        override fun load(): ByteArray? = saved
        override fun save(bytes: ByteArray) {
            saved = bytes
        }
    }

    /**
     * The iPhone side of the plugin for this test. The held connection is the real
     * [DashHeldConnection] of the app, on the fake central of the simulated pod; only its scan is
     * shorter (50 ms, the pod advertises at once). The waits of the driver ([sleep]) are 1 ms: the
     * simulated pod has primed, inserted the cannula and given the bolus when it answers.
     */
    private class SimulatedPodPlatform(private val sim: SimulatedDashPod) : IosDashPlatform {

        private val worker = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val lock = AapsLock()
        private val asked = mutableListOf<Long>()

        /** Every wait the driver asked for, in ms, in order. */
        val sleeps: List<Long> get() = lock.withLock { asked.toList() }

        override fun podStateStore(): DashPodStateStore = MemoryPodStateStore()

        override fun historyStore(): DashPodHistoryStore = MemoryHistoryStore()

        override fun connectorFactory(storedPodAddress: String?): (DashLinkClient) -> DashPodConnector = { client ->
            val blockingSleep: (Long) -> Unit = { ms -> runBlocking { delay(ms) } }
            val held = DashHeldConnection(
                central = sim.central,
                aapsLogger = SilentPodLogger,
                client = client,
                runOnWorker = { block -> worker.launch { block() } },
                sleep = blockingSleep,
                now = { Clock.System.now().toEpochMilliseconds() },
                scanner = DashPodScanner(sim.central, SilentPodLogger, blockingSleep, scanDurationMs = 50),
            )
            storedPodAddress?.let { held.setPod(it) }
            sim.central.listener = held::onEvent
            held
        }

        override fun openRadio() = Unit

        override suspend fun sleep(ms: Long) {
            lock.withLock { asked += ms }
            delay(1)
        }

        fun close() = worker.cancel()
    }

    private val sim = SimulatedDashPod()
    private val platform = SimulatedPodPlatform(sim)

    init {
        TestStorage.install()
    }

    private val graph = createGraphFactory<DashQueueTestGraph.Factory>().create(CoreObjectsGraph, ClientGraphBindings, NoSockets, NoCrashReporter, platform)

    @AfterTest
    fun close() {
        platform.close()
        sim.close()
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** Polls [condition] every 50 ms, at most [timeoutMs]; fails with [what] when it never holds. */
    private suspend fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: suspend () -> Boolean) {
        val deadline = now() + timeoutMs
        while (!condition()) {
            if (now() > deadline) throw AssertionError("timed out waiting for: $what")
            delay(50)
        }
    }

    /** Until the queue has nothing to run, also what a command queued after itself. */
    private suspend fun awaitQueueIdle() {
        waitUntil("the queue to be idle") { graph.commandQueue.size() == 0 && graph.commandQueue.performing() == null }
    }

    private fun profileSwitch(name: String, basal: List<Block>) = PS(
        timestamp = now(),
        basalBlocks = basal,
        isfBlocks = listOf(Block(DAY_MS, 100.0)),
        icBlocks = listOf(Block(DAY_MS, 10.0)),
        targetBlocks = listOf(TargetBlock(DAY_MS, 100.0, 100.0)),
        glucoseUnit = GlucoseUnit.MGDL,
        profileName = name,
        timeshift = 0,
        percentage = 100,
        duration = 0,
        iCfg = ICfg("Rapid", insulinEndTime = 5 * HOUR_MS, insulinPeakTime = 75 * 60_000L)
    )

    /** Stores [ps] as the profile switch dialog does, then waits until the queue has set it and AAPS runs it. */
    private suspend fun switchProfile(ps: PS): Long {
        val stored = graph.persistenceLayer.insertOrUpdateProfileSwitch(ps, Action.PROFILE_SWITCH, Sources.ProfileSwitchDialog, listValues = emptyList())
        val id = stored.inserted.single().id
        waitUntil("the effective profile of switch $id") { graph.persistenceLayer.getEffectiveProfileSwitchActiveAt(now())?.originalPsId == id }
        awaitQueueIdle()
        return id
    }

    private suspend fun boluses(from: Long): List<BS> = graph.persistenceLayer.getBolusesFromTime(from, true).sortedBy { it.timestamp }
    private suspend fun tempBasals(from: Long): List<TB> = graph.persistenceLayer.getTemporaryBasalsStartingFromTime(from, true).sortedBy { it.timestamp }

    @Test
    fun `a pod is activated used by the loop through the real queue and deactivated`() = runBlocking {
        val start = now() - 1_000
        graph.persistenceLayer.clearDatabases()

        // 1. The app starts its plugin list, and the user selects Dash as the pump. The BG source is
        // Nightscout (see withoutOtherRadios), chosen before the start as a user would have chosen it.
        graph.preferences.put(BooleanComposedKey.ConfigBuilderEnabled, "BGSOURCE_NSClientSourcePlugin", value = true)
        IosAppStartup(
            aapsLogger = graph.logger,
            registry = PluginStoreRegistry(graph.pluginStore, graph.configBuilder),
            contributedPlugins = withoutOtherRadios(graph.contributedPlugins),
            apsPlugins = graph.contributedApsPlugins,
            mergeApsPlugins = graph.config.APS,
            startPeriodicWork = {},
            markInitialized = { graph.config.initCompleted() },
        ).run()
        val dash = graph.omnipodDashPump
        graph.configBuilder.confirmPumpPluginSwitch(dash, true, PluginType.PUMP)
        assertSame(dash, graph.activePlugin.activePumpInternal, "Dash is the active pump")
        assertTrue(dash.isEnabled(PluginType.PUMP))
        assertFalse(dash.isInitialized(), "no pod: the loop must not use this pump")
        val queue = graph.commandQueue
        val host = graph.dashHost

        // 2. The first profile. With no pod the queue sets it (Android: success, not enacted).
        switchProfile(profileSwitch("Day", listOf(Block(DAY_MS, 1.0))))
        assertNotNull(graph.profileFunction.getProfile(), "AAPS runs a profile")
        assertEquals(0, sim.pod.commands.size, "with no pod nothing reaches the radio")
        host.rules.statusCheck()
        val noPod = tempBasals(start).single()
        assertNoPodTempBasal(noPod)

        // 3. The activation, as the wizard calls it.
        val part1 = host.wizardPod.activatePart1()
        assertTrue(part1.success, part1.comment)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, host.stateManager.activationProgress)
        assertFalse(dash.isInitialized(), "part 1 only: the pod does not run yet")
        val part2 = host.wizardPod.activatePart2()
        assertTrue(part2.success, part2.comment)
        assertEquals(ActivationProgress.COMPLETED, host.stateManager.activationProgress)
        assertTrue(dash.isInitialized(), "the pod runs: the loop may use the pump")
        assertTrue(sim.activated)
        assertEquals(1, sim.activation.primes, "one prime")
        assertEquals(1, sim.activation.cannulaInsertions, "one cannula insertion")
        assertTrue(platform.sleeps.any { it >= 10_000 }, "the prime wait was asked for: ${platform.sleeps}")
        awaitQueueIdle()
        val serial = assertNotNull(host.stateManager.uniqueId).toString()
        assertEquals(serial, dash.serialNumber())
        val activatedAt = tempBasals(start).single().end
        assertTrue(activatedAt < noPod.timestamp + noPod.duration, "the no-pod temp basal was stopped at part 2")
        val changes = graph.persistenceLayer.getTherapyEventDataFromToTime(start, now()).filter { it.type == TE.Type.CANNULA_CHANGE || it.type == TE.Type.INSULIN_CHANGE }
        assertEquals(listOf(TE.Type.CANNULA_CHANGE, TE.Type.INSULIN_CHANGE), changes.sortedBy { it.type.name }.map { it.type }, "one cannula change and one insulin change")
        changes.forEach {
            assertEquals(serial, it.ids.pumpSerial)
            assertEquals(PumpType.OMNIPOD_DASH, it.ids.pumpType)
        }
        assertEquals(1, sim.pod.commands.count { RunningPodModel.isBasalProgram(it) }, "the program of part 2")
        sim.pod.assertNoProblems()

        // The queue waits after the status read (Pump.waitForDisconnectionInSeconds, 5 s), then
        // disconnects the pod: the next command has to connect again and wait for the session, as on a phone.
        waitUntil("the queue to disconnect the pod", timeoutMs = 20_000) { !dash.isConnected() }
        val connectsBefore = sim.central.connects.size

        // 4. The loop, through the real queue. The SMB first: the queue refuses an SMB within the SMB
        // interval after a bolus.
        val smb = queue.bolus(DetailedBolusInfo().apply {
            insulin = 0.5
            bolusType = BS.Type.SMB
            lastKnownBolusTime = graph.persistenceLayer.getNewestBolus()?.timestamp ?: 0L
            deliverAtTheLatest = now()
        })
        assertTrue(smb.success, smb.comment)
        assertTrue(sim.central.connects.size > connectsBefore, "the queue connected the pod again for the SMB")
        val bolus = queue.bolus(DetailedBolusInfo().apply {
            insulin = 1.0
            bolusType = BS.Type.NORMAL
        })
        assertTrue(bolus.success, bolus.comment)
        awaitQueueIdle()
        val bs = boluses(start)
        assertEquals(listOf(0.5 to BS.Type.SMB, 1.0 to BS.Type.NORMAL), bs.map { it.amount to it.type }, "one record per bolus, the asked amount")
        bs.forEach { assertDashRecord(it.timestamp, it.ids.pumpId, it.ids.pumpType, it.ids.pumpSerial, serial) }
        assertEquals(2, sim.pod.commands.count { RunningPodModel.isBolus(it) } - sim.activation.primes - sim.activation.cannulaInsertions, "two boluses reached the pod")

        val profile = assertNotNull(graph.profileFunction.getProfile())
        val tbr = queue.tempBasalAbsolute(0.5, 30, enforceNew = false, profile = profile, tbrType = PumpSync.TemporaryBasalType.NORMAL)
        assertTrue(tbr.success, tbr.comment)
        awaitQueueIdle()
        val running = tempBasals(activatedAt).single()
        assertEquals(0.5, running.rate)
        assertTrue(running.isAbsolute)
        assertEquals(TB.Type.NORMAL, running.type)
        assertEquals(30 * 60_000L, running.duration, "the asked 30 min")
        assertDashRecord(running.timestamp, running.ids.pumpId, running.ids.pumpType, running.ids.pumpSerial, serial)
        assertTrue(sim.running.tempBasalRunning, "the pod runs the temp basal")

        val cancel = queue.cancelTempBasal(enforceNew = false, autoForced = false)
        assertTrue(cancel.success, cancel.comment)
        awaitQueueIdle()
        val cancelled = tempBasals(activatedAt).single()
        assertTrue(cancelled.duration < 30 * 60_000L, "the temp basal was stopped in AAPS: ${cancelled.duration} ms")
        assertFalse(sim.running.tempBasalRunning, "the pod stopped the temp basal")
        assertEquals(null, graph.persistenceLayer.getTemporaryBasalActiveAt(now()), "no temp basal runs")

        val programsBefore = sim.pod.commands.count { RunningPodModel.isBasalProgram(it) }
        val changeFrom = now()
        switchProfile(profileSwitch("Night", listOf(Block(6 * HOUR_MS, 0.8), Block(18 * HOUR_MS, 1.2))))
        assertEquals(programsBefore + 1, sim.pod.commands.count { RunningPodModel.isBasalProgram(it) }, "one new basal program")
        assertTrue(sim.running.basalRunning, "the pod runs the new program")
        // Android: the suspend before the program is a PUMP_SUSPEND temp basal, ended by the program.
        val suspend = tempBasals(changeFrom).single()
        assertEquals(0.0, suspend.rate)
        assertEquals(TB.Type.PUMP_SUSPEND, suspend.type)
        assertDashRecord(suspend.timestamp, suspend.ids.pumpId, suspend.ids.pumpType, suspend.ids.pumpSerial, serial)
        assertTrue(suspend.duration < PodConstants.MAX_POD_LIFETIME.inWholeMilliseconds, "the program ended the suspend")
        assertEquals(null, graph.persistenceLayer.getTemporaryBasalActiveAt(now()), "no temp basal runs after the program")
        assertEquals(2, boluses(start).size, "nothing more was written for a bolus")
        sim.pod.assertNoProblems()

        // 5. The deactivation, through the queue as the wizard sends it.
        val deactivateFrom = now()
        val deactivation = host.wizardPod.deactivate()
        assertTrue(deactivation.success, deactivation.comment)
        awaitQueueIdle()
        assertFalse(dash.isInitialized(), "no pod any more")
        assertEquals(ActivationProgress.NOT_STARTED, host.stateManager.activationProgress)
        assertEquals(RunningPodModel.DEACTIVATED, sim.running.podStatusCode, "the pod was deactivated")
        val zero = assertNotNull(graph.persistenceLayer.getTemporaryBasalActiveAt(now()), "the no-pod temp basal runs")
        assertNoPodTempBasal(zero)
        assertTrue(zero.timestamp >= deactivateFrom)
        assertEquals(1, tempBasals(deactivateFrom).size, "one no-pod temp basal")

        // The status checker: a zero temp basal runs already, so nothing more is written.
        host.rules.statusCheck()
        assertEquals(1, tempBasals(deactivateFrom).size, "the status checker writes no second one")
        assertEquals(2, boluses(start).size)
        sim.pod.assertNoProblems()

        // No notice of a failed or doubtful command, and none of pump data refused by PumpSync.
        val failures = setOf(NotificationId.WRONG_PUMP_DATA, NotificationId.FAILED_UPDATE_PROFILE, NotificationId.OMNIPOD_TBR_ALERTS)
        assertEquals(emptyList(), graph.systemNotifications.shown.filter { it.id in failures }.map { it.id })
    }

    /**
     * The plugin list of the app without the plugins that open a radio of their own when they start: the
     * only BG source is Nightscout ([NSClientSourcePlugin], switched on by the test), the pumps are Dash
     * and the virtual pump (the default). A test binary may not open CoreBluetooth with state
     * restoration: the ONE+ source, the default BG source of the iPhone, did, and iOS stopped the test.
     */
    private fun withoutOtherRadios(plugins: Map<Int, PluginBase>): Map<Int, PluginBase> = plugins.filterValues {
        when (it.getType()) {
            PluginType.BGSOURCE -> it is NSClientSourcePlugin
            PluginType.PUMP     -> it.isDefault() || it is IosOmnipodDashPlugin
            else                -> true
        }
    }

    /** Android `createFakeTBRWhenNoActivePod`: 0 U/h, PUMP_SUSPEND, the pod lifetime, serial "4241". */
    private fun assertNoPodTempBasal(tb: TB) {
        assertEquals(0.0, tb.rate)
        assertTrue(tb.isAbsolute)
        assertEquals(TB.Type.PUMP_SUSPEND, tb.type)
        assertEquals(PumpType.OMNIPOD_DASH, tb.ids.pumpType)
        assertEquals(IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR, tb.ids.pumpSerial)
        assertEquals(tb.timestamp, tb.ids.pumpId, "Android: the time as the pump id")
    }

    /** A record of a pod command, as Android writes it: Dash, the serial of the pod, the time the command was made as pump id. */
    private fun assertDashRecord(timestamp: Long, pumpId: Long?, pumpType: PumpType?, pumpSerial: String?, serial: String) {
        assertEquals(PumpType.OMNIPOD_DASH, pumpType)
        assertEquals(serial, pumpSerial)
        assertEquals(timestamp, pumpId, "Android: the time the command was made is the pump id")
    }

    private companion object {

        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
