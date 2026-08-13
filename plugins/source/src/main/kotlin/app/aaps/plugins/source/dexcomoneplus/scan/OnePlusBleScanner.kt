package app.aaps.plugins.source.dexcomoneplus.scan

/**
 * BLE scan façade for ONE+ / G7-family ADV (A6.2).
 *
 * ⚠️ ASYNC IMPACT: Android `ScanCallback` runs on the binder thread;
 * [OnePlusScanListener.onDevice] may be invoked off the main thread — hop to main for UI.
 */
fun interface OnePlusScanListener {
    fun onDevice(result: OnePlusScanResult)
}

/**
 * Outcome of a bounded pre-connect ADV wait.
 *
 * @param target the awaited MAC, or null when it stayed silent for the whole window.
 * @param foreign other ONE+ / G7-family transmitters heard during the same window. A non-empty
 *   [foreign] with a null [target] is the signature of a **stale stored MAC** (sensor replaced, or
 *   started into the other slot): a ONE+ is right there, it is simply not the one we wait for.
 */
data class OnePlusAdvWaitResult(
    val target: OnePlusScanResult? = null,
    val foreign: List<OnePlusScanResult> = emptyList(),
) {

    /** Strongest foreign transmitter heard, for the "wrong MAC?" diagnostic. */
    fun strongestForeign(): OnePlusScanResult? = foreign.maxByOrNull { it.rssi }
}

interface OnePlusBleScanner {
    fun startScan(listener: OnePlusScanListener)
    fun stopScan()
    fun isScanning(): Boolean

    /**
     * Block until [address] is seen in ADV (or timeout), also reporting any other ONE+ family
     * transmitter heard in the same window. Uses a **private** LE `ScanCallback` so UI [stopScan]
     * cannot cancel it.
     *
     * ⚠️ ASYNC IMPACT: blocks caller; binder delivers results.
     */
    fun awaitTarget(address: String, timeoutMs: Long): OnePlusAdvWaitResult = OnePlusAdvWaitResult()
}

/**
 * No-op scanner for unit tests / Stub driver path.
 */
class OnePlusBleScannerStub : OnePlusBleScanner {
    @Volatile
    private var scanning = false

    override fun startScan(listener: OnePlusScanListener) {
        scanning = true
    }

    override fun stopScan() {
        scanning = false
    }

    override fun isScanning(): Boolean = scanning
}
