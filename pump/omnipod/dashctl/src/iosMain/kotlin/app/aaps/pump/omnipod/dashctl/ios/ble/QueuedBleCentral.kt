package app.aaps.pump.omnipod.dashctl.ios.ble

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral

/**
 * A [BleCentral] that runs every call of [central] on its radio queue ("app.aaps.ble").
 *
 * [CoreBluetoothCentral] keeps its peripherals and characteristics in plain maps that only the
 * radio queue may touch, and CoreBluetooth wants its calls on the queue the manager was made
 * with. `DashBleLink` makes its calls from a worker thread, so give it this wrapper, never the
 * [CoreBluetoothCentral] itself. It uses [CoreBluetoothCentral.sync] and
 * [CoreBluetoothCentral.syncValue], the same calls as `IosBleTransport`.
 *
 * Each call waits until the radio queue has run it. An exception thrown by [central] is caught on
 * the queue and thrown again on the calling thread, so it never crosses the dispatch block.
 *
 * Do not use it from the radio queue itself. The call would still work there (`sync` runs the
 * block at once when it is already on the queue), but the code that calls it, like the blocking
 * functions of `DashBleLink`, would then hold the queue: no event could come in while it waits,
 * and each wait could only end by its timeout.
 */
class QueuedBleCentral(private val central: CoreBluetoothCentral) : BleCentral {

    override fun adapterState(): BleAdapterState = onRadio { central.adapterState() }
    override fun startScan() = onRadio { central.startScan() }
    override fun startScan(serviceUuids: List<String>) = onRadio { central.startScan(serviceUuids) }
    override fun stopScan() = onRadio { central.stopScan() }
    override fun connect(id: String): Boolean = onRadio { central.connect(id) }
    override fun connect(id: String, startDelaySeconds: Int): Boolean = onRadio { central.connect(id, startDelaySeconds) }
    override fun cancel(id: String) = onRadio { central.cancel(id) }
    override fun release(id: String) = onRadio { central.release(id) }
    override fun discoverServices(id: String) = onRadio { central.discoverServices(id) }
    override fun discoverCharacteristics(id: String, serviceUuid: String) = onRadio { central.discoverCharacteristics(id, serviceUuid) }

    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean =
        onRadio { central.write(id, characteristicUuid, data, withResponse) }

    override fun canSendWriteWithoutResponse(id: String): Boolean = onRadio { central.canSendWriteWithoutResponse(id) }

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean =
        onRadio { central.enableNotify(id, characteristicUuid, indicate) }

    override fun cachedName(id: String): String? = onRadio { central.cachedName(id) }

    /** Run [block] on the radio queue and wait for it. Its exception is thrown here, not there. */
    private fun <T> onRadio(block: () -> T): T {
        var failure: Throwable? = null
        val value = central.syncValue<T?> {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
                null
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
