package app.aaps.plugins.source.dexcomoneplus

import android.content.Context
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stub driver until A6 ports the Direct session stack and A3 GO unlocks Real.
 * Emits FAILED on connect so UI can show "protocol not yet wired".
 * Remains the **default** via [instance] / [OnePlusCgmDrivers.default].
 */
class OnePlusCgmDriverStub : OnePlusCgmDriver {

    private val watchers = CopyOnWriteArrayList<OnePlusGlucoseWatcher>()
    private var context: Context? = null
    private var warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)
    private var sessionUp = false

    override fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    override fun addWatcher(watcher: OnePlusGlucoseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    override fun removeWatcher(watcher: OnePlusGlucoseWatcher) {
        watchers.remove(watcher)
    }

    override fun startScan(listener: OnePlusScanListener) {
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: stub start (no LE scan)")
    }

    override fun stopScan() {
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: stub stop")
    }

    override fun connect(deviceAddress: String, pairingCode: String) {
        val msg = "${OnePlusLogMarkers.STUB}: BLE session not ported yet (agent A6; A3 device GO required)"
        warmup = OnePlusWarmupState(
            phase = OnePlusWarmupState.Phase.FAILED,
            message = msg,
        )
        sessionUp = false
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: stub connect → FAILED")
        OnePlusLog.i(
            "${OnePlusLogMarkers.WARMUP}: phase=${warmup.phase} remainingMs=null msg=${warmup.message}",
        )
        OnePlusLog.e("${OnePlusLogMarkers.ERROR}: $msg fatal=false")
        watchers.forEach { it.onWarmup(warmup) }
        watchers.forEach { it.onError(msg, fatal = false) }
        watchers.forEach { it.onSession(false, msg) }
    }

    override fun disconnect() {
        sessionUp = false
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: stub disconnect")
        watchers.forEach { it.onSession(false, "disconnect") }
    }

    override fun shutdown() {
        disconnect()
        watchers.clear()
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: stub shutdown")
    }

    override fun warmupState(): OnePlusWarmupState = warmup

    override fun isSessionUp(): Boolean = sessionUp

    companion object {
        val instance: OnePlusCgmDriverStub by lazy { OnePlusCgmDriverStub() }
    }
}
