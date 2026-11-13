package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod

/**
 * One [BleCentral] for the whole test, in front of the central of the current [FakeDashPod] link.
 * The simulated pod makes a new `FakeDashPodCentral` for each connection
 * ([FakeDashPod.reconnect]); a real phone keeps one central. This class plays the real one: every
 * call goes to the current fake central.
 *
 * It also counts [cancel] calls, the way `DashPodConnection` drops a BLE connection.
 */
internal class PodCentralSwitch(private val pod: FakeDashPod) : BleCentral {

    /** The ids given to [cancel], in order. */
    val cancelled = mutableListOf<String>()

    override fun adapterState(): BleAdapterState = pod.central.adapterState()
    override fun startScan() = pod.central.startScan()
    override fun stopScan() = pod.central.stopScan()
    override fun connect(id: String): Boolean = pod.central.connect(id)
    override fun cancel(id: String) {
        cancelled += id
        pod.central.cancel(id)
    }

    override fun release(id: String) = pod.central.release(id)
    override fun discoverServices(id: String) = pod.central.discoverServices(id)
    override fun discoverCharacteristics(id: String, serviceUuid: String) = pod.central.discoverCharacteristics(id, serviceUuid)
    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean =
        pod.central.write(id, characteristicUuid, data, withResponse)

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean =
        pod.central.enableNotify(id, characteristicUuid, indicate)

    override fun cachedName(id: String): String? = pod.central.cachedName(id)
}
