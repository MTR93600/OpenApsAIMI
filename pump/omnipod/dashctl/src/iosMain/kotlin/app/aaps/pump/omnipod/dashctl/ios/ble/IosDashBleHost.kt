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
 *   link set up on [scope].
 *
 * Use: give [connectorFactory] to `IosDashPump(connectorFactory = ...)`, with the pod address the
 * store already holds. [connectorFactory] opens the central, so call it once, when the pump is made.
 * The pod is made known before the central opens: iOS gives the restored peripherals during the
 * open, and [DashHeldConnection] keeps (re-attaches) the pod among them and releases the others.
 *
 * Not wired into the app yet: the plugin (phase 4) makes the pump and this host.
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
        )
        storedPodAddress?.let { held.setPod(it) }
        central.events = held::onEvent
        central.open()
        held
    }
}
