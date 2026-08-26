package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.source.SensorSlot

/**
 * Driver selection. **Stub remains default** until A3 device GO and Real protocol is filled.
 *
 * Use [select] (not a bare flag flip) so watchers stay on the active instance when the
 * engineering pref changes.
 */
object OnePlusCgmDrivers {

    @Volatile
    var useRealSkeleton: Boolean = false
        private set

    private val realInstance: OnePlusCgmDriverReal by lazy { OnePlusCgmDriverReal() }

    /**
     * Second, independent Real driver for the STAGING (pre-soak) sensor — its own BLE session,
     * executor, scanner and namespaced sensor store, so it can run concurrently with production
     * without sharing identity / key / ingest markers. Always Real (staging is a real second sensor);
     * the Stub path only applies to the production default.
     * See docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md.
     */
    private val stagingInstance: OnePlusCgmDriverReal by lazy { OnePlusCgmDriverReal(storeNamespace = STAGING_NAMESPACE) }

    /** SharedPreferences namespace for the staging slot's sensor store. */
    const val STAGING_NAMESPACE = "staging"

    /**
     * Sensor-store namespace of a slot — the ONE definition, shared by the drivers and by any UI that
     * opens a store (Start screen). null = production's original single-sensor file (non-breaking).
     *
     * Using the production store for the staging slot is what made a pre-soak silently adopt the
     * sensor already in use: same stored MAC, same PIN, same KEKS key, so no second sensor was ever
     * started and no new PIN could stick.
     */
    fun storeNamespace(slot: SensorSlot): String? = when (slot) {
        SensorSlot.PRODUCTION -> null
        SensorSlot.STAGING    -> STAGING_NAMESPACE
    }

    private val lock = Any()

    /** Production / Phase-A path — Stub unless engineering Real skeleton is enabled. */
    fun default(): OnePlusCgmDriver =
        if (useRealSkeleton) realInstance else OnePlusCgmDriverStub.instance

    /** Explicit Real skeleton (same singleton as when [useRealSkeleton] is true). */
    fun realSkeleton(): OnePlusCgmDriver = realInstance

    /** The dedicated STAGING driver instance (pre-soak second sensor). */
    fun staging(): OnePlusCgmDriverReal = stagingInstance

    /**
     * Atomically switch Stub ↔ Real, migrating an optional [watcher] and shutting down the
     * previous driver so sessions / scanners do not linger on the unused instance.
     *
     * ⚠️ ASYNC IMPACT: may call [OnePlusCgmDriver.shutdown] which disconnects GATT.
     */
    fun select(useReal: Boolean, watcher: OnePlusGlucoseWatcher? = null): OnePlusCgmDriver {
        synchronized(lock) {
            if (useRealSkeleton == useReal) {
                val current = default()
                if (watcher != null) {
                    current.addWatcher(watcher)
                }
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
            if (watcher != null) {
                next.addWatcher(watcher)
            }
            return next
        }
    }
}
