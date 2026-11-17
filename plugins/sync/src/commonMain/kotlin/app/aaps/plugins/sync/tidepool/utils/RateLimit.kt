package app.aaps.plugins.sync.tidepool.utils

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class RateLimit(
    private val dateUtil: DateUtil
) {

    private val rateLimits = HashMap<String, Long>()

    // A lock, not @Synchronized: that annotation does nothing on Kotlin/Native.
    private val lock = AapsLock()

    // return true if below rate limit
    fun rateLimit(name: String, seconds: Int): Boolean = lock.withLock {
        // check if over limit
        rateLimits[name]?.let {
            if (dateUtil.now() - it < T.secs(seconds.toLong()).msecs()) {
                //aapsLogger.debug(LTag.TIDEPOOL, "$name rate limited: $seconds seconds")
                return@withLock false
            }
        }
        // not over limit
        rateLimits[name] = dateUtil.now()
        true
    }
}

