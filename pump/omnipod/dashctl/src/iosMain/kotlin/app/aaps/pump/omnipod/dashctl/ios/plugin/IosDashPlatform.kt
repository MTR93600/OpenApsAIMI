package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.omnipod.dashctl.comm.connection.DashLinkClient
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import app.aaps.pump.omnipod.dashctl.history.DashPodHistoryStore
import app.aaps.pump.omnipod.dashctl.ios.ble.IosDashBleHost
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.ios.store.IosDashHistoryStore
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay

/**
 * What the Dash driver host ([IosDashDriverHost]) takes from the iPhone itself: the radio, where the
 * pod state and the pod history are kept, and the timer of the waits of the driver. Everything else
 * of the plugin is the same code on a phone and in a test.
 *
 * On the iPhone this is [CoreBluetoothDashPlatform]. A test graph leaves that one out
 * (`@DependencyGraph(excludes = ...)`) and gives its own, so the real plugin, the real command queue
 * and the real `PumpSync` run against a simulated pod (`:ios:shell`, `DashCommandQueueEndToEndTest`).
 */
interface IosDashPlatform {

    /** Where the pod state is kept. On the iPhone the Keychain ([DashKeychainStateStore]). */
    fun podStateStore(): DashPodStateStore

    /** Where the pod history is kept. On the iPhone the user defaults ([IosDashHistoryStore]). */
    fun historyStore(): DashPodHistoryStore

    /**
     * The BLE side of the pump, for `IosDashPump(connectorFactory = ...)`. [storedPodAddress] is the
     * peripheral of the paired pod, if any. On the iPhone [IosDashBleHost.connectorFactory].
     */
    fun connectorFactory(storedPodAddress: String?): (DashLinkClient) -> DashPodConnector

    /** Starts the radio. On the iPhone the first call makes iOS ask for the Bluetooth permission. */
    fun openRadio()

    /** The timer of the waits of the driver: the bolus wait, the prime and the cannula insertion. */
    suspend fun sleep(ms: Long)
}

/**
 * [IosDashPlatform] on the iPhone: CoreBluetooth ([IosDashBleHost]), the Keychain, the user defaults
 * and the real time. Building it does not touch the radio (see [IosDashBleHost]).
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class CoreBluetoothDashPlatform(private val aapsLogger: AAPSLogger) : IosDashPlatform {

    private val bleHost = IosDashBleHost(aapsLogger)

    override fun podStateStore(): DashPodStateStore = DashKeychainStateStore(aapsLogger = aapsLogger)

    override fun historyStore(): DashPodHistoryStore = IosDashHistoryStore()

    override fun connectorFactory(storedPodAddress: String?): (DashLinkClient) -> DashPodConnector = bleHost.connectorFactory(storedPodAddress)

    override fun openRadio() = bleHost.open()

    override suspend fun sleep(ms: Long) = delay(ms)
}
