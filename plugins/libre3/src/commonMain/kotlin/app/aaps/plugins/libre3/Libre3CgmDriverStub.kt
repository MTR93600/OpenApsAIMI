package app.aaps.plugins.libre3

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import kotlin.concurrent.Volatile

/**
 * Stub driver used until lot A6 ports the real session, and after that whenever the engineering
 * switch `Libre3BooleanKey.UseRealSkeleton` is off. It never opens a radio.
 *
 * It reports FAILED on [connect] so the UI can say that the protocol is not wired yet.
 * It stays the default driver, see `Libre3CgmDrivers.default`.
 *
 * It is shared code, not Android code: an Apple build has no real driver yet, and this is what it
 * gets. It never reports a reading and never claims a session, so nothing downstream can mistake
 * it for a working sensor.
 */
class Libre3CgmDriverStub : Libre3CgmDriver {

    /**
     * Watchers, copied before every walk.
     *
     * Was a `CopyOnWriteArrayList`, which is JVM only. The list is read far more often than it is
     * written, so a lock around the two writes plus a copy on read is the same bargain.
     */
    private val watcherLock = AapsLock()
    private val watchers = mutableListOf<Libre3GlucoseWatcher>()

    @Volatile
    private var platform: Libre3Platform? = null

    @Volatile
    private var warmup = Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE)

    @Volatile
    private var sessionUp = false

    override fun attach(platform: Libre3Platform) {
        this.platform = platform
    }

    override fun addWatcher(watcher: Libre3GlucoseWatcher) = watcherLock.withLock {
        if (!watchers.contains(watcher)) watchers.add(watcher)
        Unit
    }

    override fun removeWatcher(watcher: Libre3GlucoseWatcher) = watcherLock.withLock {
        watchers.remove(watcher)
        Unit
    }

    private fun watcherSnapshot(): List<Libre3GlucoseWatcher> = watcherLock.withLock { watchers.toList() }

    override fun connect(deviceAddress: String) {
        val message = "${Libre3LogMarkers.STUB}: the Libre 3 session is not ported yet (lot A6)"
        warmup = Libre3WarmupState(
            phase = Libre3WarmupState.Phase.FAILED,
            message = message
        )
        sessionUp = false
        Libre3Log.i("${Libre3LogMarkers.SESSION}: stub connect, result FAILED")
        Libre3Log.i("${Libre3LogMarkers.WARMUP}: phase=${warmup.phase} remainingMs=null")
        Libre3Log.e("${Libre3LogMarkers.ERROR}: $message fatal=false")
        val listeners = watcherSnapshot()
        listeners.forEach { it.onWarmup(warmup) }
        listeners.forEach { it.onError(message, false) }
        listeners.forEach { it.onSession(false, message) }
    }

    override fun disconnect() {
        sessionUp = false
        warmup = Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE)
        Libre3Log.i("${Libre3LogMarkers.SESSION}: stub disconnect")
        watcherSnapshot().forEach { it.onSession(false, "disconnect") }
    }

    override fun shutdown() {
        disconnect()
        watcherLock.withLock { watchers.clear() }
        Libre3Log.i("${Libre3LogMarkers.SESSION}: stub shutdown")
    }

    override fun warmupState(): Libre3WarmupState = warmup

    override fun isSessionUp(): Boolean = sessionUp

    companion object {

        val instance: Libre3CgmDriverStub by lazy { Libre3CgmDriverStub() }
    }
}
