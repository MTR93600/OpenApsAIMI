package app.aaps.plugins.libre3.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import app.aaps.plugins.libre3.Libre3LogMarkers
import app.aaps.plugins.libre3.gatt.Libre3BluetoothUuids
import android.os.ParcelUuid

/**
 * Looks for one known sensor, and stops as soon as it is seen.
 *
 * The search is narrowed by the sensor's own address and by the service every Libre 3 offers, so
 * the phone is not woken by every other device nearby.
 *
 * ⚠️ ASYNC IMPACT: Android answers on a binder thread. The listener is called from there, so it
 * must not do slow work.
 */
@SuppressLint("MissingPermission")
class Libre3BleScannerAndroid : Libre3BleScanner {

    @Volatile
    private var scanning = false

    private var callback: ScanCallback? = null

    override fun startScan(address: String, listener: Libre3ScanListener): Boolean {
        if (scanning) return true
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return false
        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceAddress(address)
                .setServiceUuid(ParcelUuid(Libre3BluetoothUuids.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val scanCallback = object : ScanCallback() {

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                listener.onSensorSeen(
                    Libre3ScanResult(
                        address = result.device.address,
                        name = result.device.name,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                Log.w(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SCAN}: the search failed, code=$errorCode")
            }
        }
        callback = scanCallback
        scanning = true
        scanner.startScan(filters, settings, scanCallback)
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SCAN}: looking for the stored sensor")
        return true
    }

    override fun stopScan() {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        callback?.let { current ->
            try {
                scanner?.stopScan(current)
            } catch (e: Exception) {
                Log.w(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SCAN}: stopping the search failed, ${e.javaClass.simpleName}")
            }
        }
        callback = null
        scanning = false
    }

    override fun isScanning(): Boolean = scanning
}
