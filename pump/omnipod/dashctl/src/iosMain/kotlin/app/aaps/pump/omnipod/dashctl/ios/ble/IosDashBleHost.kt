package app.aaps.pump.omnipod.dashctl.ios.ble

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.ble.session.BleRestoreIdentifier
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection
import app.aaps.pump.omnipod.dashctl.comm.connection.DashLinkClient
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The CoreBluetooth side of the Dash driver: the glue only, every rule is in [DashHeldConnection]
 * (commonMain).
 *
 * - its own [CoreBluetoothCentral], with the restore identifier [BleRestoreIdentifier.OMNIPOD_DASH],
 *   so iOS starts the app again for the pod and hands the pod back (state restoration);
 * - [QueuedBleCentral] around it, so the blocking link code runs each call on the radio queue;
 * - one [DashHeldConnection] that gets every event of the central (on the radio queue) and runs the
 *   link set up on [scope]. It runs the connect watchdog on timers of the radio queue
 *   (`CoreBluetoothCentral.postAfter`) and the fault watch scan (filtered on C00A, no duplicates, the
 *   scan iOS keeps running in the background). The rules of both are in [DashHeldConnection].
 *
 * Use: give [connectorFactory] to `IosDashPump(connectorFactory = ...)`, with the pod address the
 * store already holds, then call [open] when the plugin starts. The pod is made known (in
 * [connectorFactory]) before the central opens: iOS gives the restored peripherals during the open,
 * and [DashHeldConnection] keeps (re-attaches) the pod among them and releases the others.
 *
 * Building this host and the connector does not touch the radio: `CoreBluetoothCentral` makes its
 * `CBCentralManager` only in `open()`, and only [open] calls it. So building the app graph does not
 * make iOS ask for the Bluetooth permission. `IosDashDriverHost` (the plugin) makes this host and
 * calls [open] from `onStart`.
 */
class IosDashBleHost(
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher),
) {

    private val central = CoreBluetoothCentral(BleRestoreIdentifier.OMNIPOD_DASH)
    private val queued = QueuedBleCentral(central)

    /** The connector for the pump [client]; [storedPodAddress] is the peripheral of the paired pod, if any. */
    fun connectorFactory(storedPodAddress: String?): (DashLinkClient) -> DashPodConnector = { client ->
        val held = DashHeldConnection(
            central = queued,
            aapsLogger = aapsLogger,
            client = client,
            runOnWorker = { block -> scope.launch { block() } },
            now = { Clock.System.now().toEpochMilliseconds() },
            // The connect watchdog timer: on the radio queue, as OmnipodKit `managerQueue.asyncAfter`.
            // iOS does not run it while the app is suspended, so the held connect stays then.
            schedule = { ms, block -> central.postAfter(ms, block) },
            watchForFaults = true,
            // The watchdog does not cut a link iOS has up before its event comes (OmnipodKit checks
            // the peripheral state the same way).
            peripheralConnected = { id -> central.isPeripheralConnected(id) },
        )
        storedPodAddress?.let { held.setPod(it) }
        central.events = held::onEvent
        held
    }

    /**
     * Starts CoreBluetooth. The first call makes iOS ask for the Bluetooth permission and gives the
     * restored peripherals. Call it after [connectorFactory] ran (the pump is built), so the events
     * have somewhere to go. A second call is safe: the central keeps the manager it made.
     */
    fun open() {
        central.open()
    }
}
