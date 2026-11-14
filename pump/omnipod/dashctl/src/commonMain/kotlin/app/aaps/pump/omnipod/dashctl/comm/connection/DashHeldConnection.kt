package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.scan.DashPodAdvertisement
import app.aaps.pump.omnipod.dashctl.comm.scan.DashPodScanner
import app.aaps.pump.omnipod.dashctl.comm.scan.DashScanResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * What the pump asks of the BLE side. `IosDashPump` gets one; [DashHeldConnection] is the real one.
 * It is an interface so the pump does not depend on how the connection is made.
 */
interface DashPodConnector {

    /** True while a connection is wanted and no session is ready yet (the queue then waits). */
    val isConnecting: Boolean

    /** True while the BLE link is up and its session is not ready yet. */
    val isHandshakeInProgress: Boolean

    /** The peripheral of the paired pod, known at start (no connection is made). */
    fun setPod(peripheralId: String)

    /** Hold a connection to [peripheralId], made again after every drop. Does not block. */
    fun hold(peripheralId: String, reason: String)

    /** No more connection: the link is closed and nothing connects again until [hold]. */
    fun stopHolding(reason: String)

    /** As [stopHolding], and the pod is forgotten (deactivated or discarded). */
    fun forget(reason: String)

    /**
     * Holds the connection to [peripheralId] and blocks until the link is up and set up, or
     * [timeoutMs] passed. Never call it while holding the command lock of the pump: the set up
     * needs that lock and would only get [DashConnectResult.Busy].
     */
    fun awaitConnection(peripheralId: String, timeoutMs: Long): DashConnectResult

    /**
     * As [awaitConnection], for a caller that already holds the command lock of the pump: the bolus
     * wait, which holds it for the whole bolus. The set up then runs on the calling thread with that
     * lock ([DashPodConnection.connectHoldingLock]), so a link lost during the wait is made again
     * before each status read and each stop, as Android connects before each `getPodStatus` and
     * each `stopBolus` (`observeConnectToPod`). Only call it while holding that lock.
     */
    fun awaitConnectionHoldingLock(peripheralId: String, timeoutMs: Long): DashConnectResult

    /**
     * Scans for a pod that can be paired, gives its peripheral id to [onFound] (to store it), then
     * [awaitConnection] to it. A new pod gives [DashConnectResult.LinkReady].
     */
    fun connectNewPod(timeoutMs: Long, onFound: (String) -> Unit): DashConnectResult
}

/**
 * The held BLE connection to the pod, as OmniBLE keeps it on iOS: a `connect` with no timeout, made
 * again after every drop, and each time the link comes up a new link and a new session
 * ([DashPodConnection.connect]).
 *
 * The rules, all here in commonMain so they run on the JVM tests too:
 * - [hold] asks the central for a connection to the pod, once: CoreBluetooth keeps the request open
 *   with no time limit (also while the app is suspended) until the pod is in range;
 * - each time the link comes up ([BleCentralEvent.Link] connected), [DashPodConnection.connect] runs
 *   on the worker ([runOnWorker]): a new [DashBleLink], HELLO, the indications, then the session from
 *   outer counter 1 (paired pod). When it returns [DashConnectResult.Busy] (a command still holds the
 *   lock), it is tried again every [busyRetryMs]. An unpaired pod gets no set up here: the activation
 *   connects and pairs on its own ([connectNewPod]);
 * - when the link drops, a new held connect is asked at once, but only while the connection is
 *   wanted. After [stopHolding] (the queue disconnects, or the user does) or [forget] (the pod was
 *   deactivated or discarded), nothing connects again;
 * - Bluetooth off: nothing is asked; the link counts as lost (CoreBluetooth does not report the
 *   drop of each peripheral when the radio goes off); when it is on again the held connect is asked
 *   again;
 * - iOS started the app again and handed back the pod ([BleCentralEvent.Restored]): re-attach. The
 *   connection is wanted again and a connect is asked for the restored peripheral; CoreBluetooth
 *   answers it with a connect event (at once when the peripheral is still connected), and that
 *   makes a new link and a new session, because nothing of the old session survived the relaunch.
 *   Restored peripherals that are not this pod (an old pod) are released;
 * - a set up that failed ([DashConnectResult.LinkFailed] or [DashConnectResult.SessionFailed]) cancels
 *   the BLE connection, and the next held connect starts only after
 *   [FAILED_SETUP_CONNECT_DELAY_S] (iOS counts the delay itself). Without it a pod that refuses the
 *   session would be connected again and again without a pause.
 *
 * Not Android: Android has no held connection. It connects with a 30 s timeout before each
 * operation (`observeConnectToPod`) and disconnects when the queue is empty. Here the queue still
 * decides: `IosDashPump.connect` calls [hold], `disconnect` and `stopConnecting` call [stopHolding].
 * Between the two, a drop is made good by itself, as OmniBLE does.
 *
 * Threading: [onEvent] runs on the radio queue and never blocks. The central is never called while
 * [lock] is held: on iOS each call waits for the radio queue, and the radio queue may be waiting for
 * [lock] in [onEvent]. [awaitConnection] and [connectNewPod] block: call them from a worker.
 */
class DashHeldConnection(
    private val central: BleCentral,
    private val aapsLogger: AAPSLogger,
    private val client: DashLinkClient,
    /** Runs a block on a worker thread, later. Never on the radio queue, never inline. */
    private val runOnWorker: (() -> Unit) -> Unit,
    /** Blocks the calling thread. Tests pass a fake clock. */
    private val sleep: (Long) -> Unit = { ms -> runBlocking { delay(ms) } },
    private val now: () -> Long,
    private val scanner: DashPodScanner = DashPodScanner(central, aapsLogger, sleep),
    private val discoveryTimeoutMs: Long = DashBleLink.MIN_DISCOVERY_TIMEOUT_MS,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
) : DashPodConnector {

    private val lock = AapsLock()

    /** The peripheral of the pod, or null when no pod is known. */
    private var target: String? = null

    /** The connection to [target]; a new one when the pod changes. */
    private var connection: DashPodConnection? = null

    /** True between [hold] and [stopHolding]: a drop is followed by a new connect. */
    private var wanted = false

    /** The last adapter state reported, or null before the first report (then it is read). */
    private var adapterOn: Boolean? = null

    /** True between a connect event and a disconnect event of [target]. */
    private var linkUp = false

    /** True while a held connect was asked and its answer has not come. */
    private var connectAsked = false

    /** True while [runSetup] runs on the worker. */
    private var setupRunning = false

    /** A link came up while [runSetup] ran: run it once more at its end. */
    private var setupAgain = false

    /** Set ups that failed in a row; more than 0 delays the next held connect. */
    private var failedSetups = 0

    /**
     * [DashPodConnection.connect] calls that run now. While one runs no held connect is asked: a set
     * up that fails cancels the link from inside the call, and the next connect must know of that
     * failure (its start delay). It is asked when the call returns ([connectLink]).
     */
    private var connectsRunning = 0

    override val isConnecting: Boolean
        get() = lock.withLock { wanted } && !client.hasSession()

    override val isHandshakeInProgress: Boolean
        get() = lock.withLock { wanted && linkUp } && !client.hasSession()

    /** The connection to the pod, for tests and logs. */
    val podConnection: DashPodConnection? get() = lock.withLock { connection }

    override fun setPod(peripheralId: String) {
        val replaced = lock.withLock { switchTarget(peripheralId) }
        replaced?.let { dropOld(it) }
    }

    override fun hold(peripheralId: String, reason: String) {
        val replaced = lock.withLock {
            val old = switchTarget(peripheralId)
            if (!wanted) aapsLogger.info(LTag.PUMPBTCOMM, "Dash held connection to $peripheralId: $reason")
            wanted = true
            old
        }
        replaced?.let { dropOld(it) }
        connectIfNeeded()
    }

    override fun stopHolding(reason: String) {
        val (id, conn) = lock.withLock {
            wanted = false
            connectAsked = false
            target to connection
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "Dash held connection stopped: $reason")
        conn?.disconnect(reason)
        // Also cancels a held connect that iOS still keeps open.
        id?.let { central.cancel(it) }
    }

    override fun forget(reason: String) {
        stopHolding(reason)
        lock.withLock {
            target = null
            connection = null
            linkUp = false
            failedSetups = 0
        }
    }

    override fun awaitConnection(peripheralId: String, timeoutMs: Long): DashConnectResult =
        await(peripheralId, timeoutMs, lockHeld = false)

    override fun awaitConnectionHoldingLock(peripheralId: String, timeoutMs: Long): DashConnectResult =
        await(peripheralId, timeoutMs, lockHeld = true)

    private fun await(peripheralId: String, timeoutMs: Long, lockHeld: Boolean): DashConnectResult {
        hold(peripheralId, "connection needed")
        val deadline = now() + timeoutMs
        while (true) {
            val (conn, up) = lock.withLock { connection to linkUp }
            if (up && conn != null) {
                val result = connectLink(conn, lockHeld)
                if (result !is DashConnectResult.Busy) return result
            }
            if (now() >= deadline) {
                return DashConnectResult.NotConnected("no link to the pod after $timeoutMs ms")
            }
            sleep(POLL_MS)
        }
    }

    override fun connectNewPod(timeoutMs: Long, onFound: (String) -> Unit): DashConnectResult {
        return when (val scan = scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED)) {
            is DashScanResult.Found    -> {
                aapsLogger.info(LTag.PUMPBTCOMM, "Found a pod to pair: ${scan.id} (lot ${scan.pod.lotNo}, sequence ${scan.pod.sequenceNo})")
                onFound(scan.id)
                awaitConnection(scan.id, timeoutMs)
            }

            is DashScanResult.NotFound -> DashConnectResult.NotConnected("No pod to pair found")
            is DashScanResult.TooMany  -> DashConnectResult.NotConnected("More than one pod to pair found (${scan.ids.size})")
            is DashScanResult.Failed   -> DashConnectResult.NotConnected(scan.reason)
        }
    }

    /** Give EVERY event of the central here (from the radio queue). Never blocks. */
    fun onEvent(event: BleCentralEvent) {
        // The link first, so a drop marks the link of this connection lost before anything else.
        lock.withLock { connection }?.onEvent(event)
        when (event) {
            is BleCentralEvent.ScanResult,
            is BleCentralEvent.ScanFailed      -> scanner.onEvent(event)

            is BleCentralEvent.AdapterState    -> onAdapter(event.state)
            is BleCentralEvent.Link            -> onLink(event)
            is BleCentralEvent.Restored        -> onRestored(event.ids)
            is BleCentralEvent.OperationFailed -> aapsLogger.warn(LTag.PUMPBTCOMM, "Dash central ${event.operation} failed: ${event.message}")

            // Link traffic: only the link of the connection reads it (given above).
            is BleCentralEvent.Services,
            is BleCentralEvent.Characteristics,
            is BleCentralEvent.Written,
            is BleCentralEvent.Value,
            is BleCentralEvent.DescriptorWritten -> Unit
        }
    }

    private fun onAdapter(state: BleAdapterState) {
        val on = state == BleAdapterState.PoweredOn
        val lost = lock.withLock {
            adapterOn = on
            if (on) return@withLock null
            connectAsked = false
            if (!linkUp) return@withLock null
            linkUp = false
            target?.let { id -> connection?.let { it to id } }
        }
        // CoreBluetooth reports no drop for each peripheral when the radio goes off: tell the link.
        lost?.let { (conn, id) -> conn.onEvent(BleCentralEvent.Link(id, connected = false, status = ADAPTER_OFF_STATUS)) }
        if (on) connectIfNeeded()
    }

    private fun onLink(event: BleCentralEvent.Link) {
        val mine = lock.withLock { event.id == target }
        if (!mine) return
        if (event.connected) {
            lock.withLock {
                linkUp = true
                connectAsked = false
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link up (${event.id})")
            startSetup()
        } else {
            lock.withLock {
                linkUp = false
                connectAsked = false
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link down (${event.id}, status ${event.status})")
            connectIfNeeded()
        }
    }

    private fun onRestored(ids: List<String>) {
        val (mine, others) = lock.withLock {
            val id = target
            val mine = id != null && id in ids
            if (mine) {
                // Re-attach: iOS kept this connection for us. Nothing of the session survived.
                wanted = true
                linkUp = false
                connectAsked = false
            }
            mine to ids.filter { it != id }
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "iOS started the app again: ${ids.size} peripheral(s), the pod is ${if (mine) "one of them" else "not one of them"}")
        others.forEach { central.release(it) }
        if (mine) connectIfNeeded()
    }

    /** Asks for a held connect when one is wanted and none is up or asked. */
    private fun connectIfNeeded() {
        val known = lock.withLock { adapterOn }
        val on = known ?: (central.adapterState() == BleAdapterState.PoweredOn)
        val ask = lock.withLock {
            val id = target
            if (id == null || !wanted || linkUp || connectAsked || connectsRunning > 0 || !on) return@withLock null
            connectAsked = true
            id to (if (failedSetups > 0) FAILED_SETUP_CONNECT_DELAY_S else 0)
        } ?: return
        val (id, delaySeconds) = ask
        // No timeout: CoreBluetooth keeps the request until the pod is in range.
        val accepted = if (delaySeconds > 0) central.connect(id, delaySeconds) else central.connect(id)
        if (!accepted) {
            lock.withLock { connectAsked = false }
            aapsLogger.warn(LTag.PUMPBTCOMM, "Dash held connect to $id refused by the central")
        }
    }

    private fun startSetup() {
        val start = lock.withLock {
            if (setupRunning) {
                setupAgain = true
                false
            } else {
                setupRunning = true
                true
            }
        }
        if (start) runOnWorker { runSetup() }
    }

    /** On the worker: [DashPodConnection.connect] for the link that came up, again while it is Busy. */
    private fun runSetup() {
        var busyTries = 0
        while (true) {
            val conn = lock.withLock { if (wanted && linkUp) connection else null }
            val result = if (conn != null && client.isPaired()) connectLink(conn) else null
            if (result is DashConnectResult.Busy && busyTries < MAX_BUSY_RETRIES) {
                busyTries++
                sleep(busyRetryMs)
                continue
            }
            if (result != null) aapsLogger.info(LTag.PUMPBTCOMM, "Dash link set up: $result")
            busyTries = 0
            val again = lock.withLock {
                if (setupAgain) {
                    setupAgain = false
                    true
                } else {
                    setupRunning = false
                    false
                }
            }
            if (!again) return
        }
    }

    /**
     * [DashPodConnection.connect] (or [DashPodConnection.connectHoldingLock] when [lockHeld]), counted
     * in [connectsRunning]; then the held connect a failure needs.
     */
    private fun connectLink(conn: DashPodConnection, lockHeld: Boolean = false): DashConnectResult {
        lock.withLock { connectsRunning++ }
        val result = try {
            if (lockHeld) conn.connectHoldingLock() else conn.connect()
        } finally {
            lock.withLock { connectsRunning-- }
        }
        recordSetup(result)
        connectIfNeeded()
        return result
    }

    private fun recordSetup(result: DashConnectResult) {
        lock.withLock {
            failedSetups = when (result) {
                is DashConnectResult.LinkFailed, is DashConnectResult.SessionFailed -> failedSetups + 1
                is DashConnectResult.Busy, is DashConnectResult.NotConnected        -> failedSetups
                else                                                                -> 0
            }
        }
    }

    /** A new pod (or the first one): a new [DashPodConnection]. Returns the old one to drop. Called with [lock]. */
    private fun switchTarget(peripheralId: String): Pair<String, DashPodConnection?>? {
        if (target == peripheralId) return null
        val old = target?.let { it to connection }
        target = peripheralId
        connection = DashPodConnection(central, peripheralId, aapsLogger, client, discoveryTimeoutMs)
        linkUp = false
        connectAsked = false
        failedSetups = 0
        return old
    }

    private fun dropOld(old: Pair<String, DashPodConnection?>) {
        old.second?.disconnect("another pod")
        central.cancel(old.first)
    }

    companion object {

        /** Pause before the set up is tried again while a command holds the lock. */
        const val BUSY_RETRY_MS = 1_000L

        /** At most this many tries while Busy (30 min at [BUSY_RETRY_MS], longer than any bolus). */
        const val MAX_BUSY_RETRIES = 1_800

        /** How often [awaitConnection] looks again. */
        const val POLL_MS = 200L

        /** The start delay of the held connect after a set up that failed. */
        const val FAILED_SETUP_CONNECT_DELAY_S = 10

        /** Android `BleConnection.DEFAULT_CONNECT_TIMEOUT_MS`: the wait of [awaitConnection] before an operation. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** The status of the link lost given to the link when Bluetooth goes off. */
        const val ADAPTER_OFF_STATUS = -1
    }
}
