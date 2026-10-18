package app.aaps.plugins.libre3

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stub driver used until lot A6 ports the real session, and after that whenever the engineering
 * switch `Libre3BooleanKey.UseRealSkeleton` is off. It never opens a radio.
 *
 * It reports FAILED on [connect] so the UI can say that the protocol is not wired yet.
 * It stays the default driver, see [Libre3CgmDrivers.default].
 */
class Libre3CgmDriverStub : Libre3CgmDriver {

    private val watchers = CopyOnWriteArrayList<Libre3GlucoseWatcher>()
    private var context: Context? = null
    private var warmup = Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE)
    private var sessionUp = false

    override fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    override fun addWatcher(watcher: Libre3GlucoseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    override fun removeWatcher(watcher: Libre3GlucoseWatcher) {
        watchers.remove(watcher)
    }

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
        watchers.forEach { it.onWarmup(warmup) }
        watchers.forEach { it.onError(message, false) }
        watchers.forEach { it.onSession(false, message) }
    }

    override fun disconnect() {
        sessionUp = false
        warmup = Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE)
        Libre3Log.i("${Libre3LogMarkers.SESSION}: stub disconnect")
        watchers.forEach { it.onSession(false, "disconnect") }
    }

    override fun shutdown() {
        disconnect()
        watchers.clear()
        Libre3Log.i("${Libre3LogMarkers.SESSION}: stub shutdown")
    }

    override fun warmupState(): Libre3WarmupState = warmup

    override fun isSessionUp(): Boolean = sessionUp

    companion object {

        val instance: Libre3CgmDriverStub by lazy { Libre3CgmDriverStub() }
    }
}
