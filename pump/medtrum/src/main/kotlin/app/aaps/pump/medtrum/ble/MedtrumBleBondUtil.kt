package app.aaps.pump.medtrum.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * Medtrum Nano patches use BLE GATT only. They do **not** support Android classic pairing
 * ([BluetoothDevice.createBond]). Calling createBond on Samsung (One UI 8.5 / Android 16) shows
 * a system toast like "association with device MT is impossible" and blocks connection.
 *
 * Connection must proceed with scan + [android.bluetooth.BluetoothGatt] only.
 * If the user previously bonded MT-… in system Bluetooth settings, remove that entry.
 */
internal object MedtrumBleBondUtil {

    @SuppressLint("MissingPermission")
    fun logBondStateIfRelevant(device: BluetoothDevice, aapsLogger: AAPSLogger) {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "MT device ${device.address} is paired in Android Bluetooth settings. " +
                    "Medtrum does not use classic pairing — remove it in Settings → Bluetooth if connection fails."
            )
            BluetoothDevice.BOND_BONDING -> aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "MT device ${device.address} bonding in progress — cancel pairing in system settings"
            )
            else -> Unit
        }
    }
}
