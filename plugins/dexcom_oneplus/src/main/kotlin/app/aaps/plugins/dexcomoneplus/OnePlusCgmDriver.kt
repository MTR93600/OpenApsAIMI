package app.aaps.plugins.dexcomoneplus

import android.content.Context
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener

/**
 * Native Dexcom ONE+ BLE driver façade.
 *
 * Default impl: [OnePlusCgmDriverStub]. Real stack: [OnePlusCgmDriverReal] (skeleton until A3).
 * See docs/spikes/ONEPLUS_BLE_PORT_MAP.md.
 *
 * ⚠️ ASYNC IMPACT: Real driver may invoke [OnePlusGlucoseWatcher] / scan listeners off the main
 * thread (bleExecutor / binder). Callers must not assume main; do not run AIMI dose logic there.
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

    /**
     * Take a smaller share of the radio, or give the usual share back.
     *
     * Asked for while another job on the same radio must not be disturbed, which today means a pump
     * setup — see [app.aaps.core.interfaces.ble.BleRadioPriority]. While it is on, the driver asks
     * the platform for a slower connection interval and holds its scans back. The link is kept and
     * readings keep arriving: this is not a disconnect.
     *
     * Default is a no-op, which is right for a driver with no radio of its own.
     */
    fun setRadioBackOff(backOff: Boolean) = Unit
    fun warmupState(): OnePlusWarmupState
    fun isSessionUp(): Boolean
}
