package app.aaps.plugins.source.dexcomoneplus

import android.content.Context
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanListener

/**
 * Native Dexcom ONE+ BLE driver façade.
 *
 * Default impl: [OnePlusCgmDriverStub]. Real stack: [OnePlusCgmDriverReal] (skeleton until A3).
 * See docs/spikes/ONEPLUS_BLE_PORT_MAP.md.
 *
 * ⚠️ ASYNC IMPACT: Real driver may invoke [OnePlusGlucoseWatcher] / scan listeners off the main
 * thread (bleExecutor / binder). Callers must not assume main; do not run loop or dosing work there.
 */
interface OnePlusCgmDriver {
    fun setContext(context: Context)
    fun addWatcher(watcher: OnePlusGlucoseWatcher)
    fun removeWatcher(watcher: OnePlusGlucoseWatcher)
    fun startScan(listener: OnePlusScanListener)
    fun stopScan()
    fun connect(deviceAddress: String, pairingCode: String)
    fun disconnect()
    fun shutdown()
    fun warmupState(): OnePlusWarmupState
    fun isSessionUp(): Boolean
}
