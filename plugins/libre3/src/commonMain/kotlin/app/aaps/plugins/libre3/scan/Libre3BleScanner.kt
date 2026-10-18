package app.aaps.plugins.libre3.scan

/**
 * Looks for a sensor on the air.
 *
 * A Libre 3 does not have to be found by looking: the NFC step already gave its address. Looking
 * is only used to answer "is the sensor near and awake" before a connect is tried, so a phone in a
 * pocket does not sit on a connect that can never finish.
 */
interface Libre3BleScanner {

    /** @param address only report this sensor. */
    fun startScan(address: String, listener: Libre3ScanListener): Boolean

    fun stopScan()

    fun isScanning(): Boolean
}
