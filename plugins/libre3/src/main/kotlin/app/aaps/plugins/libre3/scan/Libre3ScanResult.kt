package app.aaps.plugins.libre3.scan

/**
 * A sensor seen on the air.
 *
 * @param address the Bluetooth address, in the form Android writes it.
 * @param rssi how strong the signal is. Less negative is closer.
 */
data class Libre3ScanResult(val address: String, val name: String?, val rssi: Int)

/** Told about sensors as they are seen. */
fun interface Libre3ScanListener {

    fun onSensorSeen(result: Libre3ScanResult)
}
