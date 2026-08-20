package app.aaps.plugins.libre3

import app.aaps.plugins.libre3.crypto.Libre3RuntimeTables
import app.aaps.plugins.libre3.session.Libre3PairingBlocks

/**
 * Driver selection for the native Libre 3 plugin.
 *
 * The stub is the default. The real driver is only used when the engineering switch
 * `Libre3BooleanKey.UseRealSkeleton` is on, and that switch is off by default.
 *
 * Use [select] instead of writing the flag by hand, so watchers move to the driver that is really
 * active and the driver that is left behind is stopped.
 *
 * There is no second driver for a spare sensor. Pre-soak and staging are out of scope for v1.
 */
object Libre3CgmDrivers {

    @Volatile
    var useRealSkeleton: Boolean = false
        private set

    private val lock = Any()

    private val realInstance: Libre3CgmDriverReal by lazy { Libre3CgmDriverReal(Libre3PairingBlocks.factory()) }

    /**
     * The driver the plugin should use.
     *
     * The real one is reached only when the engineering switch is on **and** the files the pairing
     * needs really ship with this build. Without those files the real driver could not pair
     * anything, so handing it out would only turn a clear refusal into a confusing failure.
     */
    fun default(): Libre3CgmDriver =
        if (useRealSkeleton && Libre3RuntimeTables.pairingTablesPresent()) realInstance
        else Libre3CgmDriverStub.instance

    /** Why the real driver is not in use, or null when it is. */
    fun realDriverBlockedReason(): String? = when {
        !useRealSkeleton                             -> "the engineering switch is off"
        !Libre3RuntimeTables.pairingTablesPresent()  ->
            "this build does not ship the files the pairing needs: " +
                Libre3RuntimeTables.missingTables().joinToString()

        else                                         -> null
    }

    /**
     * Switch between stub and real driver in one step, moving an optional [watcher] over and
     * stopping the driver that is no longer used.
     *
     * ⚠️ ASYNC IMPACT: this may call [Libre3CgmDriver.shutdown], which drops the GATT link.
     */
    fun select(useReal: Boolean, watcher: Libre3GlucoseWatcher? = null): Libre3CgmDriver {
        synchronized(lock) {
            if (useRealSkeleton == useReal) {
                val current = default()
                if (watcher != null) current.addWatcher(watcher)
                return current
            }
            val previous = default()
            if (watcher != null) {
                try {
                    previous.removeWatcher(watcher)
                } catch (_: Throwable) {
                }
            }
            try {
                previous.shutdown()
            } catch (_: Throwable) {
            }
            useRealSkeleton = useReal
            val next = default()
            if (watcher != null) next.addWatcher(watcher)
            return next
        }
    }
}
