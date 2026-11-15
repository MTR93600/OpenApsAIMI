package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.scan.DashFaultAdvertisement
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

    /**
     * [listener] is called when the paired pod shows a fault in its advertisement while it is not
     * connected (the fault watch of [DashHeldConnection]). It runs on a worker, never on the radio
     * queue, so it may queue a status read. The last listener set is the one called.
     */
    fun setFaultListener(listener: () -> Unit)
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
 * The connect watchdog (only with [schedule]), as OmnipodKit (Trio) `BluetoothManager.armConnectWatchdog`.
 * Deliberate difference from Android (a user action lost): a connect that iOS keeps open and that never
 * comes up while the pod is in range (OmnipodKit found such wedged connects with some pods and iPhones;
 * the pod then stops advertising) would leave every command and bolus waiting until the queue gives up.
 * So, while the connection is wanted:
 * - a connect with no link [CONNECT_WATCHDOG_MS] after it was asked (after its start delay, when it has
 *   one) is cancelled, and at once a new one is asked with a start delay of
 *   [CONNECT_WATCHDOG_RESTART_DELAY_S]. Deliberate difference from OmnipodKit, which waits 0.2 s on a
 *   timer and then connects (a user action lost): iOS does not run that timer while the app is
 *   suspended, so an app suspended in the pause had no connect open, and the pod coming back did not
 *   wake it. With the start delay iOS counts the pause itself and a connect is open at all times;
 * - before the cancel, [peripheralConnected] (when given) is asked. Deliberate difference from Android
 *   (a user action lost), as OmnipodKit (`guard target.state != .connected`): a link that came up just
 *   before the timer, whose event still waits on the radio queue, is not cut. Cutting it would make
 *   the set up fail and give the next connect the delay of a failed set up;
 * - this goes on for at most [CONNECT_WATCHDOG_BUDGET_MS] from the first connect asked, counted again
 *   after each link that came up and after each new [hold] or restore. After the budget the connect is
 *   left open with no time limit, as before: the held rule of OmniBLE (iOS wakes the app when the pod
 *   comes in range) stays the last word;
 * - the fault watch scan (below) is stopped while the watchdog runs, as OmnipodKit `eagerConnect`
 *   ("a concurrent scan starves connection completion on iOS"). Without that a slow connect would trip
 *   the 2 s watchdog on a healthy pod again and again while a bolus waits (a user action lost);
 * - the watchdog runs for every pod. OmnipodKit runs it only for InPlay pods on some iPhones; a false
 *   trip here costs one cancel and [CONNECT_WATCHDOG_RESTART_DELAY_S];
 * - the timer is [schedule], on iOS a timer of the radio queue that iOS does not run while the app is
 *   suspended. A suspended app keeps the connect that is open, so iOS still wakes it for the pod.
 *
 * The fault watch (only with [watchForFaults]), as OmnipodKit `BluetoothManager.startScanning` (the
 * low-power scan) and `omnipodDidDetectAlert`. Deliberate difference from Android (insulin counted
 * wrong): a pod that faults stops all delivery; without this the app learns it only at the next status
 * read, and meanwhile the loop counts basal that the pod does not give. So:
 * - while the paired pod is known and not connected (and Bluetooth is on, no pairing scan runs and no
 *   connect watchdog runs), a scan runs filtered on [DashFaultAdvertisement.FAULT_SERVICE_UUID] only,
 *   with no duplicates: iOS reports, and wakes the app for, only a pod that changed to the fault
 *   advertisement. Beside a held connect with no watchdog (after its budget) it runs, as OmnipodKit
 *   keeps this light scan beside its delayed connect (`issueDelayedConnectProbe`);
 * - an advertisement of THIS pod (never another pod: the filter is not pod specific) with the fault
 *   UUID calls the listener of [setFaultListener] on a worker (never on the radio queue: OmnipodKit
 *   issue 126, the listener reads a status), and the scan stops for this pod;
 * - the report is used up only when a link to the pod came up after it (the status read, or any
 *   command, then reached the pod), or when a connection was asked after it and stopped with no link
 *   (the status read never reached the pod). Then the scan runs again. Deliberate difference from a
 *   report used once per pod (insulin counted wrong): a report the plugin could not act on (no
 *   activated pod yet) or a status read that failed would otherwise leave the fault unseen until the
 *   next regular status read. A pod still in fault is reported again then, at most once for each
 *   connection tried;
 * - the scan stops while the link is up: a connected pod does not advertise, and the status reads see
 *   the fault.
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
    /**
     * Runs a block once after a delay in ms, without blocking the caller: the timer of the connect
     * watchdog. On iOS `CoreBluetoothCentral.postAfter` (the radio queue). Null: no connect watchdog,
     * the connect is only held (tests of the other rules).
     */
    private val schedule: ((delayMs: Long, block: () -> Unit) -> Unit)? = null,
    /** True: the fault watch scan runs (see the class KDoc). False: no scan except the pairing scan. */
    private val watchForFaults: Boolean = false,
    /**
     * True when iOS has the link to this peripheral up now, even if its connect event did not come yet
     * (on iOS the `CBPeripheral` state). Asked by the connect watchdog before it cancels. Null: not asked.
     */
    private val peripheralConnected: ((String) -> Boolean)? = null,
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

    /** Counts the connects asked; a watchdog timer acts only for the connect it was armed for. */
    private var connectGeneration = 0

    /** When the first connect of this watchdog budget was asked, or null when a new budget starts. */
    private var watchdogBudgetStartMs: Long? = null

    /** True while the connect watchdog watches the open connect (within its budget): no fault scan then. */
    private var watchdogActive = false

    /**
     * True when a connection was asked ([hold]) after the fault report of [target] and no link came
     * since: a [stopHolding] then means the status read never reached the pod, and the report is given
     * again.
     */
    private var faultReadTried = false

    /** True while the fault watch scan was started and not stopped. */
    private var faultScanOn = false

    /** True while [connectNewPod] scans: the pairing scan owns the scanner of the central. */
    private var pairingScanRunning = false

    /** The pod whose fault advertisement was given to the listener already (once per pod). */
    private var faultReportedFor: String? = null

    /** See [setFaultListener]. */
    private var faultListener: (() -> Unit)? = null

    override val isConnecting: Boolean
        get() = lock.withLock { wanted } && !client.hasSession()

    override val isHandshakeInProgress: Boolean
        get() = lock.withLock { wanted && linkUp } && !client.hasSession()

    /** The connection to the pod, for tests and logs. */
    val podConnection: DashPodConnection? get() = lock.withLock { connection }

    override fun setPod(peripheralId: String) {
        val replaced = lock.withLock { switchTarget(peripheralId) }
        replaced?.let { dropOld(it) }
        updateFaultWatch()
    }

    override fun hold(peripheralId: String, reason: String) {
        val replaced = lock.withLock {
            val old = switchTarget(peripheralId)
            if (!wanted) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Dash held connection to $peripheralId: $reason")
                watchdogBudgetStartMs = null
            }
            wanted = true
            if (faultReportedFor != null && faultReportedFor == target) faultReadTried = true
            old
        }
        replaced?.let { dropOld(it) }
        connectIfNeeded()
        updateFaultWatch()
    }

    override fun stopHolding(reason: String) {
        val (id, conn) = lock.withLock {
            wanted = false
            connectAsked = false
            watchdogActive = false
            if (faultReadTried) {
                // A connection was asked after the fault report and no link came: the status read
                // never reached the pod. Give the report again (see the class KDoc).
                faultReadTried = false
                faultReportedFor = null
            }
            target to connection
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "Dash held connection stopped: $reason")
        conn?.disconnect(reason)
        // Also cancels a held connect that iOS still keeps open.
        id?.let { central.cancel(it) }
        updateFaultWatch()
    }

    override fun forget(reason: String) {
        stopHolding(reason)
        lock.withLock {
            target = null
            connection = null
            linkUp = false
            failedSetups = 0
            faultReportedFor = null
            faultReadTried = false
        }
        updateFaultWatch()
    }

    override fun setFaultListener(listener: () -> Unit) {
        lock.withLock { faultListener = listener }
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
        // The central has one scan: the fault watch stops while the pairing scan runs.
        lock.withLock { pairingScanRunning = true }
        updateFaultWatch()
        val scan = try {
            scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED)
        } finally {
            lock.withLock { pairingScanRunning = false }
            updateFaultWatch()
        }
        return when (scan) {
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
            is BleCentralEvent.ScanResult      -> {
                scanner.onEvent(event)
                onFaultAdvertisement(event)
            }

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
            watchdogActive = false
            if (!linkUp) return@withLock null
            linkUp = false
            target?.let { id -> connection?.let { it to id } }
        }
        // CoreBluetooth reports no drop for each peripheral when the radio goes off: tell the link.
        lost?.let { (conn, id) -> conn.onEvent(BleCentralEvent.Link(id, connected = false, status = ADAPTER_OFF_STATUS)) }
        if (on) connectIfNeeded()
        // iOS stops every scan when Bluetooth goes off: the fault scan starts again when it is on.
        if (!on) lock.withLock { faultScanOn = false }
        updateFaultWatch()
    }

    private fun onLink(event: BleCentralEvent.Link) {
        val mine = lock.withLock { event.id == target }
        if (!mine) return
        if (event.connected) {
            lock.withLock {
                linkUp = true
                connectAsked = false
                // A link came up: the next connect that does not come up gets a new watchdog budget.
                watchdogBudgetStartMs = null
                watchdogActive = false
                // The status read (or any command) reaches the pod now: a fault report is used up,
                // and the scan after this link reports a pod still in fault again.
                faultReportedFor = null
                faultReadTried = false
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link up (${event.id})")
            updateFaultWatch()
            startSetup()
        } else {
            lock.withLock {
                linkUp = false
                connectAsked = false
            }
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link down (${event.id}, status ${event.status})")
            connectIfNeeded()
            updateFaultWatch()
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
                watchdogBudgetStartMs = null
            }
            mine to ids.filter { it != id }
        }
        aapsLogger.info(LTag.PUMPBTCOMM, "iOS started the app again: ${ids.size} peripheral(s), the pod is ${if (mine) "one of them" else "not one of them"}")
        others.forEach { central.release(it) }
        if (mine) connectIfNeeded()
        // A new process: no scan of this process runs yet (iOS may have kept the old one; starting it
        // again replaces it).
        lock.withLock { faultScanOn = false }
        updateFaultWatch()
    }

    /** Asks for a held connect when one is wanted and none is up or asked. */
    private fun connectIfNeeded() {
        val known = lock.withLock { adapterOn }
        val on = known ?: (central.adapterState() == BleAdapterState.PoweredOn)
        val ask = lock.withLock {
            val id = target
            if (id == null || !wanted || linkUp || connectAsked || connectsRunning > 0 || !on) return@withLock null
            connectAsked = true
            connectGeneration++
            if (watchdogBudgetStartMs == null) watchdogBudgetStartMs = now()
            // The watchdog will watch this connect: the fault scan stops before it (see the class KDoc).
            watchdogActive = schedule != null && watchdogBudgetLeft()
            ConnectAsk(id, if (failedSetups > 0) FAILED_SETUP_CONNECT_DELAY_S else 0, connectGeneration)
        } ?: return
        updateFaultWatch()
        // No timeout: CoreBluetooth keeps the request until the pod is in range.
        val accepted = if (ask.delaySeconds > 0) central.connect(ask.id, ask.delaySeconds) else central.connect(ask.id)
        if (!accepted) {
            lock.withLock {
                if (ask.generation == connectGeneration) {
                    connectAsked = false
                    watchdogActive = false
                }
            }
            aapsLogger.warn(LTag.PUMPBTCOMM, "Dash held connect to ${ask.id} refused by the central")
            updateFaultWatch()
            return
        }
        armConnectWatchdog(ask)
    }

    private class ConnectAsk(val id: String, val delaySeconds: Int, val generation: Int)

    /** True while a watchdog restart still fits in the budget. Called with [lock]. */
    private fun watchdogBudgetLeft(): Boolean {
        val start = watchdogBudgetStartMs ?: return true
        return now() - start + CONNECT_WATCHDOG_RESTART_DELAY_S * 1_000L < CONNECT_WATCHDOG_BUDGET_MS
    }

    /** True while [ask] is the connect that is open and wanted, with no link. Called with [lock]. */
    private fun isOpen(ask: ConnectAsk): Boolean =
        ask.generation == connectGeneration && ask.id == target && wanted && !linkUp && connectAsked

    /**
     * Arms the connect watchdog for [ask] (see the class KDoc), unless there is no [schedule], the
     * link already came up (the central may answer inside `connect`) or the budget is over.
     */
    private fun armConnectWatchdog(ask: ConnectAsk) {
        val timer = schedule ?: return
        val pending = lock.withLock { isOpen(ask) && watchdogActive }
        if (!pending) return
        timer(ask.delaySeconds * 1_000L + CONNECT_WATCHDOG_MS) { onConnectWatchdog(ask) }
    }

    /**
     * The watchdog time of [ask] is over: when it is still open, cancel it and at once ask a new one
     * with a start delay, so a connect is open at all times (see the class KDoc).
     */
    private fun onConnectWatchdog(ask: ConnectAsk) {
        var budgetOver = false
        val open = lock.withLock {
            if (!isOpen(ask)) return@withLock false
            if (!watchdogBudgetLeft()) {
                budgetOver = true
                watchdogActive = false
                return@withLock false
            }
            true
        }
        if (budgetOver) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Dash connect watchdog: no link within the budget, the connect to ${ask.id} stays held with no limit")
            updateFaultWatch()
            return
        }
        if (!open) return
        // iOS may have the link up already, with its event still waiting on the radio queue: do not cut it.
        if (peripheralConnected?.invoke(ask.id) == true) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash connect watchdog: ${ask.id} is connected, its event is on the way; not cancelled")
            armConnectWatchdog(ConnectAsk(ask.id, 0, ask.generation))
            return
        }
        val restart = lock.withLock {
            if (!isOpen(ask)) return@withLock null
            connectGeneration++
            // connectAsked stays true: nothing else asks a connect between the cancel and the new one.
            ConnectAsk(ask.id, CONNECT_WATCHDOG_RESTART_DELAY_S, connectGeneration)
        } ?: return
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash connect watchdog: no link ${CONNECT_WATCHDOG_MS} ms after the connect to ${ask.id}, cancelled and asked again")
        central.cancel(restart.id)
        val accepted = central.connect(restart.id, restart.delaySeconds)
        // A stop (or another pod) that came between the two calls: that connect is not wanted.
        val unwanted = lock.withLock {
            val current = restart.generation == connectGeneration
            if (!accepted && current) {
                connectAsked = false
                watchdogActive = false
            }
            // Not when a newer connect to this pod is wanted: the cancel would end that one too.
            accepted && (!wanted || restart.id != target)
        }
        if (unwanted) central.cancel(restart.id)
        if (!accepted) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Dash held connect to ${restart.id} refused by the central")
            updateFaultWatch()
            return
        }
        armConnectWatchdog(restart)
    }

    /**
     * Starts or stops the fault watch scan so that it runs exactly while it should (see the class
     * KDoc). The central is called outside [lock]. A scan call that throws (the central is not open
     * yet) leaves the scan counted as stopped, so the next change tries again.
     */
    private fun updateFaultWatch() {
        if (!watchForFaults) return
        val paired = client.isPaired()
        val known = lock.withLock { adapterOn }
        val on = known ?: (central.adapterState() == BleAdapterState.PoweredOn)
        val start = lock.withLock {
            val id = target
            val wantScan = id != null && paired && on && !linkUp && !pairingScanRunning && !watchdogActive && faultReportedFor != id
            if (wantScan == faultScanOn) return@withLock null
            faultScanOn = wantScan
            wantScan
        } ?: return
        try {
            if (start) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Dash fault watch: scan for ${DashFaultAdvertisement.FAULT_SERVICE_UUID16} while the pod is not connected")
                central.startScan(listOf(DashFaultAdvertisement.FAULT_SERVICE_UUID))
            } else {
                aapsLogger.info(LTag.PUMPBTCOMM, "Dash fault watch: scan stopped")
                central.stopScan()
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Dash fault watch: scan call failed", e)
            lock.withLock { faultScanOn = false }
        }
    }

    /** An advertisement: the fault of THIS pod goes to the listener, on a worker, once per pod. */
    private fun onFaultAdvertisement(event: BleCentralEvent.ScanResult) {
        if (!watchForFaults || !DashFaultAdvertisement.isFault(event.serviceUuids)) return
        val listener = lock.withLock {
            if (event.id != target || faultReportedFor == event.id) return@withLock null
            val l = faultListener ?: return@withLock null
            faultReportedFor = event.id
            l
        }
        if (listener == null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash fault advertisement of ${event.id} not used (another pod, already given, or no listener)")
            return
        }
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash pod ${event.id} advertises a fault: reading the pod status")
        updateFaultWatch()
        runOnWorker { listener() }
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
        faultReportedFor = null
        faultReadTried = false
        watchdogBudgetStartMs = null
        watchdogActive = false
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

        /**
         * OmnipodKit `eagerConnectWatchdogSeconds` (2 s): a connect with no link after this is taken as
         * wedged. A healthy connect to a pod in range comes up in less than 1 s.
         */
        const val CONNECT_WATCHDOG_MS = 2_000L

        /**
         * The start delay of the connect asked right after a watchdog cancel: the pause in which the
         * cancelled connect is torn down on air. OmnipodKit `eagerConnectTeardownSeconds` is 0.2 s on
         * a timer; here iOS counts it (so a connect stays open while the app is suspended), and
         * CoreBluetooth takes only whole seconds, so it is 1 s.
         */
        const val CONNECT_WATCHDOG_RESTART_DELAY_S = 1

        /** OmnipodKit `eagerConnectBudgetSeconds` (40 s): how long the watchdog cancels and connects again. */
        const val CONNECT_WATCHDOG_BUDGET_MS = 40_000L
    }
}
