package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.RESTORED_LINK_RECONNECT_DELAY_S
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.POD_ID
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.advertisement
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.session.CountingRandomBytes
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.PodKeys
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.BOLUS_BIT
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isBolus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isStopOf
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * The real [IosDashPump] on a [DashHeldConnection] (the held connection of OmniBLE), on a fake
 * central that behaves as CoreBluetooth ([HeldPodCentral]), against the simulated pod: the scan and
 * the pairing inside the activation (Android `pairNewPod`), `connect(reason)`, the new link and
 * session after a drop, the connect before a command (Android `observeConnectToPod`), and no
 * reconnect after a disconnect or a deactivation.
 */
class IosDashPumpHeldConnectionTest {

    private val pod = FakeDashPod(PodKeys.Generated())
    private val central = HeldPodCentral(pod)
    private val model = ActivatingPodModel()
    private val store = MemoryDashPodStateStore()
    private val stateManager = DashPodStateManager(store)
    private val worker = ArrayDeque<() -> Unit>()
    private var nowMs = NOW
    private val sleeps = mutableListOf<Long>()
    private var onSleep: () -> Unit = {}

    /** Runs before each wait of the pump itself (the bolus wait, the activation waits). */
    private var onPumpSleep: () -> Unit = {}
    private var held: DashHeldConnection? = null

    private val pump = IosDashPump(
        aapsLogger = SilentPodLogger,
        onCommandDecided = {},
        stateManager = stateManager,
        newKeyExchange = {
            DashKeyExchange(
                IosX25519Dh(), IosAesCmac(),
                pdmNonce = parityHex(DashPumpOnFakePod.PDM_NONCE), pdmPrivate = parityHex(DashPumpOnFakePod.PDM_PRIVATE)
            )
        },
        randomBytes = CountingRandomBytes(),
        now = { nowMs },
        sleep = { ms ->
            onPumpSleep()
            delay(ms)
        },
        connectorFactory = { client ->
            DashHeldConnection(
                central = central,
                aapsLogger = SilentPodLogger,
                client = client,
                runOnWorker = { worker.addLast(it) },
                sleep = {
                    sleeps += it
                    nowMs += it
                    onSleep()
                },
                now = { nowMs },
                discoveryTimeoutMs = 200,
            ).also { held = it }
        },
    )

    private val program = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))

    init {
        central.listener = assertNotNull(held)::onEvent
        pod.answerFor = model::answer
    }

    @AfterTest
    fun closePod() {
        pod.close()
    }

    private fun runWorker() {
        while (worker.isNotEmpty()) worker.removeFirst().invoke()
    }

    private suspend fun activate() {
        central.advertisements += advertisement(POD_ID)
        val part1 = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))
        assertTrue(part1.success, part1.comment)
        val part2 = pump.activatePodPart2(program, userConfiguredExpirationReminderHours = 4, userConfiguredExpirationAlarmHours = 7)
        assertTrue(part2.success, part2.comment)
        runWorker()
        pod.assertNoProblems()
    }

    private fun statusReads(): Int = pod.commands.count { it[6] == CommandType.GET_STATUS.value }

    @Test
    fun `a new pod is found by the scan then paired and activated inside the activation`() = runTest {
        assertFalse(pump.isPaired())

        activate()

        assertEquals(1, central.scans.size, "one scan, filtered on the Dash service")
        assertEquals(POD_ID, stateManager.bluetoothAddress, "the peripheral found is stored")
        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(CommandType.GET_VERSION.value, pod.commands.first()[6], "the first pod command goes on the new pairing")
        assertTrue(pump.isPaired())
        assertTrue(pump.isConnected())
        assertEquals(ActivationProgress.COMPLETED, stateManager.activationProgress)
        assertEquals(1, model.primes)
        assertEquals(1, model.cannulaInsertions)
    }

    @Test
    fun `no pod found means nothing is paired and nothing is sent`() = runTest {
        val part1 = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))

        assertFalse(part1.success)
        assertTrue(central.connects.isEmpty())
        assertTrue(pod.commands.isEmpty())
        assertNull(stateManager.bluetoothAddress)
        assertFalse(pump.isBusy(), "the activation did not start")
    }

    @Test
    fun `connect holds the connection to the stored pod and gives a session`() = runTest {
        activate()
        pump.disconnect("Queue empty")
        assertFalse(pump.isConnected())

        pump.connect("Connection needed")
        assertTrue(pump.isHandshakeInProgress(), "the link is up, the session comes from the worker")
        runWorker()

        assertTrue(pump.isConnected())
        assertFalse(pump.isConnecting())
        assertEquals(listOf(POD_ID, POD_ID), central.connects)
    }

    @Test
    fun `after a drop the pump connects again by itself and the next status goes on a new session`() = runTest {
        activate()
        val before = statusReads()

        central.podGoesAway()
        assertFalse(pump.isConnected())
        assertTrue(pump.isConnecting(), "the held connect waits for the pod")

        central.podComesInRange()
        runWorker()
        assertTrue(pump.isConnected())

        pump.getPumpStatus("test")
        assertEquals(before + 1, statusReads())
        pod.assertNoProblems()
    }

    @Test
    fun `a command without a session first waits for the held connection`() = runTest {
        activate()
        central.podGoesAway()
        val before = statusReads()
        onSleep = { central.podComesInRange() }

        pump.getPumpStatus("test")

        assertTrue(sleeps.contains(DashHeldConnection.POLL_MS), "it waited for the link")
        assertEquals(before + 1, statusReads(), "the status went on the new session")
        pod.assertNoProblems()
    }

    @Test
    fun `a command fails closed when the pod does not come back in time`() = runTest {
        activate()
        central.podGoesAway()
        val before = pod.commands.size

        pump.getPumpStatus("test")

        assertEquals(before, pod.commands.size, "nothing sent")
        assertTrue(nowMs - NOW >= DashHeldConnection.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `no reconnect after a disconnect`() = runTest {
        activate()

        pump.disconnect("Queue empty")
        central.podComesInRange()
        runWorker()

        assertEquals(listOf(POD_ID), central.connects)
        assertFalse(pump.isConnected())
        assertFalse(pump.isConnecting())
    }

    @Test
    fun `no reconnect after a deactivation and a later restore releases the old pod`() = runTest {
        activate()

        val result = pump.deactivatePod()
        assertTrue(result.success, result.comment)
        held?.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf(POD_ID), central.released)
        assertNull(stateManager.bluetoothAddress)
        assertFalse(pump.isConnecting())
    }

    /**
     * Android `isConnected()` is `!isPodRunning || CONNECTED`: with no running pod the queue runs the
     * command at once instead of calling `connect` again and again until it gives up and clears the
     * queue. The command connects itself when it needs the pod.
     */
    @Test
    fun `with no running pod the queue sees a connected pump and nothing is connected as Android`() = runTest {
        assertTrue(pump.isConnected(), "no pod at all")

        activate()
        assertTrue(pump.isConnected())
        central.podGoesAway()
        assertFalse(pump.isConnected(), "a running pod without a session is not connected")
        central.podComesInRange()
        runWorker()

        val result = pump.deactivatePod()
        assertTrue(result.success, result.comment)
        val connects = central.connects.size
        val commands = pod.commands.size

        assertTrue(pump.isConnected(), "the pod is deactivated, so no pod runs")
        val cancel = pump.cancelTempBasal(enforceNew = true)

        assertFalse(cancel.success, "no session: fails closed")
        assertEquals(connects, central.connects.size, "nothing connected")
        assertEquals(commands, pod.commands.size, "nothing sent")
    }

    /**
     * Android checks the pod state before it connects (`observePodRunning`, `observeUniqueIdSet`,
     * `observePodReadyForActivationPart2`): a command the state refuses makes no BLE contact and no
     * new session.
     */
    @Test
    fun `a command the pod state refuses makes no connection as Android`() = runTest {
        stateManager.updateBluetoothAddress(POD_ID)

        val cancel = pump.cancelTempBasal(enforceNew = true)
        val part2 = pump.activatePodPart2(program, userConfiguredExpirationReminderHours = 4, userConfiguredExpirationAlarmHours = 7)
        pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)

        assertFalse(cancel.success)
        assertFalse(part2.success)
        assertTrue(part2.comment.contains("incorrect state"), part2.comment)
        assertTrue(central.connects.isEmpty(), "no connection: ${central.connects}")
        assertTrue(sleeps.isEmpty(), "no wait for a connection")
        assertTrue(pod.commands.isEmpty())
    }

    /**
     * Android `pairNewPod` calls `disconnect(false)` when it fails. Without it the phone keeps the
     * connect to the new pod, the pod connects later and stops advertising, and the scan of the next
     * try does not find it ("No pod to pair found").
     */
    @Test
    fun `a retry after a failed connect in part 1 finds the same pod again`() = runTest {
        central.advertisements += advertisement(POD_ID)
        central.podInRange = false

        val first = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))
        assertFalse(first.success, first.comment)
        assertFalse(pump.isPaired())
        assertFalse(pump.isConnecting(), "the connect to the unpaired pod is stopped")

        central.podComesInRange()
        runWorker()
        val second = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))

        assertTrue(second.success, second.comment)
        assertTrue(pump.isPaired())
        assertEquals(2, central.scans.size)
        assertEquals(1, model.primes)
        pod.assertNoProblems()
    }

    /**
     * Android: the address setter throws when another address is stored, so a retry of part 1
     * never pairs another pod than the one the first try found. The user discards the pod first.
     */
    @Test
    fun `a retry of part 1 never switches to another pod as Android`() = runTest {
        central.advertisements += advertisement(POD_ID)
        central.podInRange = false
        assertFalse(pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200)).success)
        central.advertisements.clear()
        central.advertisements += advertisement(OTHER_POD)
        central.podInRange = true

        val retry = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))

        assertFalse(retry.success, retry.comment)
        assertTrue(retry.comment.contains("already set"), retry.comment)
        assertEquals(POD_ID, stateManager.bluetoothAddress)
        assertFalse(central.connects.any { it.startsWith(OTHER_POD) }, "never connected: ${central.connects}")
        assertFalse(pump.isPaired())
        assertTrue(pod.commands.isEmpty())
    }

    /**
     * The link drops during the bolus wait and the user stops the bolus. Android connects again
     * before each stop (`stopBolus` starts with `observeConnectToPod`), so the stop reaches the pod.
     * The wait holds the command lock here, so it must connect itself, or the whole bolus goes in.
     */
    @Test
    fun `a stop after a drop during the bolus wait still reaches the pod on a new session`() = runTest {
        activate()
        val activationBoluses = pod.commands.count { isBolus(it) }
        var dropped = false
        onPumpSleep = {
            if (!dropped && pod.commands.count { isBolus(it) } > activationBoluses) {
                dropped = true
                central.podGoesAway()
                pump.stopBolusDelivering()
                onSleep = { central.podComesInRange() }
            }
        }

        val result = pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 })

        assertTrue(dropped)
        assertTrue(result.success, result.comment)
        assertEquals(1, pod.commands.count { isStopOf(it, BOLUS_BIT) }, "the stop went to the pod")
        assertEquals(activationBoluses + 1, pod.commands.count { isBolus(it) }, "the bolus went once")
        assertTrue(pump.isConnected())
        pod.assertNoProblems()
    }

    /**
     * Android `deliverTreatment` sets `bolusDeliveryInProgress` first and connects later, inside the
     * bolus command (`observeConnectToPod`). A stop pressed while the connection is made is kept, and
     * `observeDeliveryNotCanceled` then sends nothing. Before the fix the flag was set after the
     * connect, the stop was dropped and the whole bolus went in.
     */
    @Test
    fun `a stop pressed while the bolus call connects sends no bolus as Android`() = runTest {
        activate()
        val boluses = pod.commands.count { isBolus(it) }
        central.podGoesAway()
        var stopped = false
        onSleep = {
            if (!stopped) {
                stopped = true
                pump.stopBolusDelivering()
            }
            central.podComesInRange()
        }

        val result = pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 })

        assertTrue(stopped, "the stop was pressed during the connect")
        assertTrue(result.success, result.comment)
        assertFalse(result.enacted)
        assertEquals(boluses, pod.commands.count { isBolus(it) }, "no bolus reached the pod")
        pod.assertNoProblems()
    }

    /**
     * Android adds `observeConnectToPod` after the prime wait ("connection can time out while
     * waiting"). The link drops during the wait: the status check must connect again first.
     */
    @Test
    fun `a drop during the prime wait connects again before the status check as Android`() = runTest {
        central.advertisements += advertisement(POD_ID)
        var dropped = false
        onPumpSleep = {
            if (!dropped) {
                dropped = true
                central.podGoesAway()
                onSleep = { central.podComesInRange() }
            }
        }

        val part1 = pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200))

        assertTrue(dropped, "the link dropped during the prime wait")
        assertTrue(part1.success, part1.comment)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, stateManager.activationProgress)
        assertEquals(1, pod.commands.count { isBolus(it) }, "the prime went once")
        pod.assertNoProblems()
    }

    /** The same after the cannula wait of part 2. */
    @Test
    fun `a drop during the cannula wait connects again before the status check as Android`() = runTest {
        central.advertisements += advertisement(POD_ID)
        assertTrue(pump.activatePodPart1(AlertTrigger.ReservoirVolumeTrigger(200)).success)
        var dropped = false
        onPumpSleep = {
            if (!dropped) {
                dropped = true
                central.podGoesAway()
                onSleep = { central.podComesInRange() }
            }
        }

        val part2 = pump.activatePodPart2(program, userConfiguredExpirationReminderHours = 4, userConfiguredExpirationAlarmHours = 7)

        assertTrue(dropped, "the link dropped during the cannula wait")
        assertTrue(part2.success, part2.comment)
        assertEquals(ActivationProgress.COMPLETED, stateManager.activationProgress)
        assertEquals(2, pod.commands.count { isBolus(it) }, "the prime and the cannula went once each")
        pod.assertNoProblems()
    }

    /**
     * iOS ended the app after the pod took a bolus and before its answer was written, and kept the
     * BLE link. At the relaunch the link is dropped first, as Android (process death closes the
     * GATT): the open bolus is decided by a status read on a fresh link and session, never sent again.
     */
    @Test
    fun `a restore of a pod still connected decides an open bolus on a fresh link and sends it once`() = runTest {
        activate()
        val boluses = pod.commands.count { isBolus(it) }
        assertTrue(pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 }).success)
        assertEquals(boluses + 1, pod.commands.count { isBolus(it) })
        val open = store.history.last { it.activeCommand?.kind == DashCommandKind.BOLUS && it.activeCommand?.sent == true }
        val relaunchedStore = MemoryDashPodStateStore().apply { saved = open }
        val relaunchedState = DashPodStateManager(relaunchedStore)
        val decided = mutableListOf<DashCommandOutcome>()
        var again: DashHeldConnection? = null
        val relaunched = IosDashPump(
            aapsLogger = SilentPodLogger,
            onCommandDecided = { if (it is DashCommandOutcome) decided += it },
            stateManager = relaunchedState,
            newKeyExchange = {
                DashKeyExchange(
                    IosX25519Dh(), IosAesCmac(),
                    pdmNonce = parityHex(DashPumpOnFakePod.PDM_NONCE), pdmPrivate = parityHex(DashPumpOnFakePod.PDM_PRIVATE)
                )
            },
            randomBytes = CountingRandomBytes(),
            now = { nowMs },
            sleep = { ms -> delay(ms) },
            connectorFactory = { client ->
                DashHeldConnection(
                    central = central,
                    aapsLogger = SilentPodLogger,
                    client = client,
                    runOnWorker = { worker.addLast(it) },
                    sleep = {
                        sleeps += it
                        nowMs += it
                        onSleep()
                    },
                    now = { nowMs },
                    discoveryTimeoutMs = 200,
                    schedule = { _, _ -> },
                    peripheralConnected = { central.linkUp },
                ).also {
                    it.setPod(POD_ID)
                    again = it
                }
            },
        )
        val restored = assertNotNull(again)
        central.listener = restored::onEvent
        assertTrue(central.linkUp, "iOS kept the link of the app it ended")
        val cancelsBefore = central.cancelled.size
        val messagesBefore = pod.commands.size

        restored.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(cancelsBefore + 1, central.cancelled.size, "the restored link is dropped first")
        assertFalse(central.linkUp)
        assertEquals("$POD_ID delay=$RESTORED_LINK_RECONNECT_DELAY_S", central.connects.last(), "then a fresh connect")
        assertEquals(messagesBefore, pod.commands.size, "nothing sent on the restored link")

        onSleep = { central.podComesInRange() }
        relaunched.getPumpStatus("after the restore")

        assertNull(relaunchedState.activeCommand)
        val outcome = decided.single()
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertTrue(outcome.decidedLate)
        assertEquals(1.0, outcome.command.requestedBolusUnits)
        assertEquals(boluses + 1, pod.commands.count { isBolus(it) }, "the bolus went to the pod once")
        assertEquals(CommandType.GET_STATUS.value, pod.commands.last()[6], "decided by a status read")
        pod.assertNoProblems()
    }

    /**
     * The connector is made in the constructor and may call back into the pump at once (a state
     * restore can start a link set up while the central opens). The pump must be fully built then.
     */
    @Test
    fun `the connector may use the pump while the pump is made`() {
        var locked: Boolean? = null
        var session: Boolean? = null
        IosDashPump(
            aapsLogger = SilentPodLogger,
            onCommandDecided = {},
            stateManager = DashPodStateManager(MemoryDashPodStateStore()),
            connectorFactory = { client ->
                locked = client.tryLockLink()
                if (locked == true) client.unlockLink()
                session = client.hasSession()
                DashHeldConnection(central = central, aapsLogger = SilentPodLogger, client = client, runOnWorker = {}, now = { nowMs })
            },
        )

        assertEquals(true, locked)
        assertEquals(false, session)
    }

    // -- Connection quality counters (Android `connect`, `stopConnecting`) ----------------------

    @Test
    fun `the activation counts no connection as Android pairNewPod`() = runTest {
        activate()

        assertEquals(0, stateManager.successfulConnectionAttemptsAfterRetries)
        assertEquals(0, stateManager.failedConnectionsAfterRetries)
    }

    @Test
    fun `one queue connect counts one success also when the link comes back by itself`() = runTest {
        activate()
        pump.disconnect("Queue empty")
        pump.connect("Connection needed")
        runWorker()
        assertTrue(pump.isConnected())
        assertEquals(1, stateManager.successfulConnectionAttemptsAfterRetries)

        // A drop and a new session inside the same held connect: Android counts one connect.
        central.podGoesAway()
        central.podComesInRange()
        runWorker()
        assertTrue(pump.isConnected())
        pump.getPumpStatus("test")

        assertEquals(1, stateManager.successfulConnectionAttemptsAfterRetries)
    }

    @Test
    fun `a second queue connect while the first waits counts nothing more`() = runTest {
        activate()
        pump.disconnect("Queue empty")
        pump.connect("Connection needed")
        pump.connect("Connection needed")
        runWorker()

        assertEquals(1, stateManager.successfulConnectionAttemptsAfterRetries)
    }

    @Test
    fun `a queue connect that finds the session up counts one success as Android AlreadyConnected`() = runTest {
        activate()
        assertTrue(pump.isConnected())

        pump.connect("Connection needed")
        pump.getPumpStatus("test")

        assertEquals(1, stateManager.successfulConnectionAttemptsAfterRetries)
    }

    @Test
    fun `stop connecting counts one failure and no success`() = runTest {
        activate()
        pump.disconnect("Queue empty")
        central.podGoesAway()
        pump.connect("Connection needed")
        pump.stopConnecting()

        // The pod comes back for the next queue connect: that one is a success, the first one is not.
        central.podComesInRange()
        pump.connect("Connection needed")
        runWorker()
        assertTrue(pump.isConnected())

        assertEquals(1, stateManager.failedConnectionsAfterRetries)
        assertEquals(1, stateManager.successfulConnectionAttemptsAfterRetries)
    }

    /**
     * [DashPodStateManager] is not thread-safe. The queue calls stopConnecting on its own thread, often
     * while the held connection sets up a session with the command lock held and writes the EAP SQN.
     * The failure count must not be written then, or one of the two writes is lost.
     */
    @Test
    fun `stop connecting does not write the state while the command lock is held`() = runTest {
        activate()
        val before = stateManager.currentState()
        assertTrue(pump.tryLockLink())
        try {
            pump.stopConnecting()
            assertEquals(before, stateManager.currentState(), "nothing written while another holds the lock")
        } finally {
            pump.unlockLink()
        }

        pump.connect("Connection needed")
        runWorker()

        assertEquals(1, stateManager.failedConnectionsAfterRetries, "written with the next session")
        assertTrue(stateManager.currentState().eapAkaSequenceNumber > before.eapAkaSequenceNumber, "the session SQN is kept")
    }

    private companion object {

        const val NOW = 1_700_000_000_000L

        /** Another pod in range, not the one the first try found. */
        const val OTHER_POD = "11111111-2222-3333-4444-555555555555"
    }
}
