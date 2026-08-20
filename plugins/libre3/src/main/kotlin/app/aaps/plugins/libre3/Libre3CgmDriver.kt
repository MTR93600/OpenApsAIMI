package app.aaps.plugins.libre3

import android.content.Context

/**
 * Native Libre 3 / Libre 3 Plus driver façade.
 *
 * Default implementation: [Libre3CgmDriverStub]. The real BLE stack is added by lot A6.
 *
 * There is no scan step and no typed pairing code here. The sensor is activated over NFC, which
 * gives the BLE address and the PIN. The Start screen persists them and only then calls [connect].
 *
 * ⚠️ ASYNC IMPACT: the real driver calls [Libre3GlucoseWatcher] from its BLE executor thread.
 * Callers must not assume the main thread and must not run dose logic there.
 */
interface Libre3CgmDriver {

    fun setContext(context: Context)

    fun addWatcher(watcher: Libre3GlucoseWatcher)

    fun removeWatcher(watcher: Libre3GlucoseWatcher)

    /** Connect to the sensor whose BLE address and PIN were stored during the NFC step. */
    fun connect(deviceAddress: String)

    /**
     * Drop the GATT link only.
     *
     * The driver must never send a patch control command here. Telling the sensor to shut down
     * would end the sensor for good, so an ordinary disconnect stays a link level action.
     */
    fun disconnect()

    /** Stop everything and forget the watchers. Same rule as [disconnect]: link level only. */
    fun shutdown()

    fun warmupState(): Libre3WarmupState

    fun isSessionUp(): Boolean
}
