package app.aaps.core.interfaces.pump.ble.session

import app.aaps.core.interfaces.pump.ble.BleAdapter
import app.aaps.core.interfaces.pump.ble.BleGatt
import app.aaps.core.interfaces.pump.ble.BleScanner
import app.aaps.core.interfaces.pump.ble.BleTransport
import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.PairingState
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * [BleTransport] on CoreBluetooth.
 *
 * Not registered with Metro and not referenced by the iOS shell. `PUMPDRIVERS` stays false: this
 * class existing does not talk to a pump. A caller has to construct it, and no caller in this lot does.
 *
 * [timeoutMillis] defaults to null, which is what Dana, Medtrum and Equil do inside their Android
 * transports (they do not time out a GATT operation here). When it is set, [poll] runs on the
 * CoreBluetooth queue from the wall clock.
 */
class IosBleTransport(
    profile: BleProfile,
    private val timeoutMillis: Long? = null,
    private val central: CoreBluetoothCentral = CoreBluetoothCentral()
) : BleTransport {

    private val session = BleSession(
        central = central,
        profile = profile,
        timeoutMillis = timeoutMillis,
        post = { block -> central.post(block) },
        clock = { wallClockMillis() }
    )

    init {
        central.events = { event -> session.onEvent(event) }
        central.open()
        if (timeoutMillis != null) {
            session.poll(wallClockMillis())
            armClock()
        }
    }

    override val adapter: BleAdapter = Adapter()
    override val scanner: BleScanner = Scanner()
    override val gatt: BleGatt = Gatt()

    private val pairing = MutableStateFlow(PairingState())
    override val pairingState: StateFlow<PairingState> = pairing.asStateFlow()

    override fun updatePairingState(state: PairingState) {
        pairing.value = state
    }

    override fun setListener(listener: BleTransportListener?) {
        central.sync { session.setListener(listener) }
    }

    private fun armClock() {
        val budget = timeoutMillis ?: return
        central.postAfter(budget) {
            session.poll(wallClockMillis())
            armClock()
        }
    }

    private inner class Adapter : BleAdapter {
        override fun enable() {
            central.sync { session.enable() }
        }

        override fun getDeviceName(address: String): String? =
            central.syncValue { session.deviceName(address) }

        override fun isDeviceBonded(address: String): Boolean =
            central.syncValue { session.isBonded(address) }

        override fun createBond(address: String): Boolean =
            central.syncValue { session.createBond(address) }

        override fun removeBond(address: String) {
            central.sync { session.removeBond(address) }
        }
    }

    private inner class Scanner : BleScanner {
        override val scannedDevices: SharedFlow<ScannedDevice> = session.scannedDevices

        override fun startScan() {
            central.sync { session.startScan() }
        }

        override fun stopScan() {
            central.sync { session.stopScan() }
        }
    }

    private inner class Gatt : BleGatt {
        override fun connect(address: String): Boolean =
            central.syncValue { session.connect(address) }

        override fun disconnect() {
            central.sync { session.disconnect() }
        }

        override fun close() {
            central.sync { session.close() }
        }

        override fun discoverServices() {
            central.sync { session.discoverServices() }
        }

        override fun findCharacteristics(): Boolean =
            central.syncValue { session.findCharacteristics() }

        override fun enableNotifications() {
            central.sync { session.enableNotifications() }
        }

        override fun writeCharacteristic(data: ByteArray) {
            central.sync { session.write(data) }
        }
    }
}

private fun wallClockMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
