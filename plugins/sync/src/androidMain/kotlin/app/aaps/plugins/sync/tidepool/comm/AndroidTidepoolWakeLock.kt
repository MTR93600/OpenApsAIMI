package app.aaps.plugins.sync.tidepool.comm

import android.content.Context
import android.os.PowerManager
import app.aaps.core.interfaces.logging.AAPSLogger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** A partial wake lock with a timeout, so it can never be held for ever. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidTidepoolWakeLock(
    private val ctx: Context,
    private val aapsLogger: AAPSLogger,
) : TidepoolWakeLock {

    private var wl: PowerManager.WakeLock? = null

    @Synchronized
    override fun extend(ms: Long) {
        if (wl == null) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:TidepoolUploader")
            wl?.acquire(ms)
        } else {
            release() // lets not get too messy
            wl?.acquire(ms)
        }
    }

    @Synchronized
    override fun release() {
        wl?.let {
            if (it.isHeld)
                try {
                    it.release()
                } catch (e: Exception) {
                    aapsLogger.error("Error releasing wakelock: $e")
                }
        }
    }
}
