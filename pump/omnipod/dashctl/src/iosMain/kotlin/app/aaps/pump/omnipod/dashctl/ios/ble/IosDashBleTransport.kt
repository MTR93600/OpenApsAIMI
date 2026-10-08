package app.aaps.pump.omnipod.dashctl.ios.ble

import app.aaps.core.interfaces.pump.ble.BleTransport
import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSUUID

/**
 * BLE transport for the Omnipod Dash pod, on top of [BleTransport] (PR #146).
 *
 * Dash uses a custom GATT layout (not the standard BLE UART):
 * - Service: `1a7e4024-e3ed-4464-8b7e-751e03d0dc5f`
 * - CMD characteristic: `1a7e2441-e3ed-4464-8b7e-751e03d0dc5f` (write)
 * - DATA characteristic: `1a7e2442-e3ed-4464-8b7e-751e03d0dc5f` (notify)
 *
 * The transport writes command frames to CMD and reads response frames from
 * DATA notifications. `BleTransport.findCharacteristics()` / `enableNotifications()`
 * are expected to have located the Dash characteristics (the iOS GATT session
 * filters by the Dash service UUID).
 *
 * Incoming DATA notifications are queued; [receive] dequeues with a timeout.
 * All GATT calls are synchronous on the CoreBluetooth queue (`central.sync`).
 */
class IosDashMessageTransport(
    private val bleTransport: BleTransport
) : DashMessageTransport, BleTransportListener {

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        bleTransport.setListener(this)
    }

    // -- DashMessageTransport -------------------------------------------------

    override fun send(data: ByteArray): Boolean {
        return try {
            bleTransport.gatt.writeCharacteristic(data)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun receive(): ByteArray? {
        return runBlocking {
            withTimeoutOrNull(RECEIVE_TIMEOUT_MILLIS) {
                incoming.receive()
            }
        }
    }

    // -- BleTransportListener ---------------------------------------------------

    override fun onConnectionStateChanged(connected: Boolean) = Unit

    override fun onServicesDiscovered(success: Boolean) = Unit

    override fun onDescriptorWritten() = Unit

    override fun onCharacteristicChanged(data: ByteArray) {
        incoming.trySend(data)
    }

    override fun onCharacteristicWritten() = Unit

    // -- Connection helpers -----------------------------------------------------

    /**
     * Scans for a Dash pod advertising the Dash service UUID and connects.
     * Returns the BLE address, or null on timeout.
     */
    fun scanAndConnect(timeoutMillis: Long = 30_000): String? {
        bleTransport.scanner.startScan()
        return try {
            runBlocking {
                withTimeoutOrNull(timeoutMillis) {
                    bleTransport.scanner.scannedDevices.first { device ->
                        device.isDashPod()
                    }.address.also { address ->
                        bleTransport.scanner.stopScan()
                        bleTransport.gatt.connect(address)
                        bleTransport.gatt.discoverServices()
                        bleTransport.gatt.findCharacteristics()
                        bleTransport.gatt.enableNotifications()
                    }
                }
            }
        } finally {
            runBlocking { bleTransport.scanner.stopScan() }
        }
    }

    fun disconnect() {
        bleTransport.gatt.disconnect()
    }

    private fun ScannedDevice.isDashPod(): Boolean {
        // The Dash pod advertises the custom service UUID; the name starts with "POD".
        return name.startsWith("POD", ignoreCase = true)
    }

    companion object {
        const val RECEIVE_TIMEOUT_MILLIS: Long = 30_000

        /** Dash GATT service UUID. */
        val SERVICE_UUID: NSUUID = NSUUID("1A7E4024-E3ED-4464-8B7E-751E03D0DC5F")

        /** CMD characteristic UUID (write). */
        val CMD_CHARACTERISTIC_UUID: NSUUID = NSUUID("1A7E2441-E3ED-4464-8B7E-751E03D0DC5F")

        /** DATA characteristic UUID (notify). */
        val DATA_CHARACTERISTIC_UUID: NSUUID = NSUUID("1A7E2442-E3ED-4464-8B7E-751E03D0DC5F")

        /** Creates a transport over the given [BleTransport]. */
        fun create(bleTransport: BleTransport): IosDashMessageTransport =
            IosDashMessageTransport(bleTransport)
    }
}
