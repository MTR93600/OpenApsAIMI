package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener
import kotlin.concurrent.Volatile

/**
 * Stub driver until A6 ports the Direct session stack and A3 GO unlocks Real.
 * Emits FAILED on connect so UI can show "protocol not yet wired".
 * Remains the **default** via [instance] / `OnePlusCgmDrivers.default`.
 *
 * It is shared code, not Android code: the Apple build has no real driver yet, and this is what it
 * gets. It never reports a reading and never claims a session, so nothing downstream can mistake
 * it for a working sensor.
 */
class OnePlusCgmDriverStub : OnePlusCgmDriver {

    /**
     * Watchers, copied before every walk.
     *
     * Was a `CopyOnWriteArrayList`, which is JVM only. The list is read far more often than it is
     * written, so a lock around the two writes plus a copy on read is the same bargain.
     */
    private val watcherLock = AapsLock()
    private val watchers = mutableListOf<OnePlusGlucoseWatcher>()

    @Volatile
    private var platform: OnePlusPlatform? = null

    @Volatile
    private var warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)

    @Volatile
    private var sessionUp = false

    override fun attach(platform: OnePlusPlatform) {
        this.platform = platform
    }

    override fun addWatcher(watcher: OnePlusGlucoseWatcher) = watcherLock.withLock {
        if (!watchers.contains(watcher)) watchers.add(watcher)
        Unit
    }

    override fun removeWatcher(watcher: OnePlusGlucoseWatcher) = watcherLock.withLock {
        watchers.remove(watcher)
        Unit
    }

    private fun watcherSnapshot(): List<OnePlusGlucoseWatcher> = watcherLock.withLock { watchers.toList() }

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
        val current = watcherSnapshot()
        current.forEach { it.onWarmup(warmup) }
        current.forEach { it.onError(msg, fatal = false) }
        current.forEach { it.onSession(false, msg) }
    }

    override fun disconnect() {
        sessionUp = false
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: stub disconnect")
        watcherSnapshot().forEach { it.onSession(false, "disconnect") }
    }

    override fun shutdown() {
        disconnect()
        watcherLock.withLock { watchers.clear() }
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: stub shutdown")
    }

    override fun warmupState(): OnePlusWarmupState = warmup

    override fun isSessionUp(): Boolean = sessionUp

    companion object {
        val instance: OnePlusCgmDriverStub by lazy { OnePlusCgmDriverStub() }
    }
}
