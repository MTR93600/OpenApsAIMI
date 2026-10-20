package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.source.SensorSlot

/**
 * Driver selection. **Stub remains default** until A3 device GO and Real protocol is filled.
 *
 * Use [select] (not a bare flag flip) so watchers stay on the active instance when the
 * engineering pref changes.
 *
 * The instances are plain nullable fields and not `by lazy` on purpose.
 *
 * A driver that was shut down can never open a session again, because `shutdown` also stops its
 * executor for good. With `by lazy` the dead instance would be handed out for ever, and after one
 * promotion there would be no working production driver left. A dropped field is rebuilt on the
 * next call, so a second pre-soak and a second promotion still work. Mirrors `Libre3CgmDrivers`.
 */
object OnePlusCgmDrivers {

    @Volatile
    var useRealSkeleton: Boolean = false
        private set

    /** SharedPreferences namespace for the staging slot's sensor store. */
    const val STAGING_NAMESPACE = "staging"

    private val lock = Any()

    @Volatile
    private var productionReal: OnePlusCgmDriverReal? = null

    /**
     * Second, independent Real driver for the STAGING (pre-soak) sensor — its own BLE session,
     * executor, scanner and namespaced sensor store, so it can run concurrently with production
     * without sharing identity / key / ingest markers. Always Real (staging is a real second sensor);
     * the Stub path only applies to the production default.
     * See docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md.
     */
    @Volatile
    private var stagingReal: OnePlusCgmDriverReal? = null

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

    /** The real production instance. Built on first use. */
    fun realProduction(): OnePlusCgmDriverReal = synchronized(lock) {
        productionReal ?: OnePlusCgmDriverReal().also { productionReal = it }
    }

    /** The dedicated STAGING driver instance (pre-soak second sensor). Built on first use. */
    fun staging(): OnePlusCgmDriverReal = synchronized(lock) {
        stagingReal ?: OnePlusCgmDriverReal(storeNamespace = STAGING_NAMESPACE).also { stagingReal = it }
    }

    /** Production / Phase-A path — Stub unless engineering Real skeleton is enabled. */
    fun default(): OnePlusCgmDriver =
        if (useRealSkeleton) realProduction() else OnePlusCgmDriverStub.instance

    /** Explicit Real skeleton (same instance as when [useRealSkeleton] is true). */
    fun realSkeleton(): OnePlusCgmDriver = realProduction()

    /**
     * Hands the staging instance the production role, keeping its live BLE link.
     *
     * Under [lock]: the promoted instance is pointed at the production preferences file and becomes
     * the production instance; the staging pointer is freed so the next pre-soak builds a fresh
     * instance. Without this swap, `default()` kept handing out the OLD (shut down) production
     * instance for ever after a promotion — every screen that reads it directly (Status, Warmup)
     * showed stale state, and the reconnect watchdog (which also reads `default()`) silently stopped
     * protecting the promoted sensor.
     *
     * ⚠️ Do not turn these fields back into `by lazy`. A retired instance must be dropped, or the
     * second promotion, days later, would hand out a driver whose executor is already stopped.
     *
     * @return the instance that has just been retired, for the caller to shut down **outside** the
     *   lock, or null when there was no staging instance to promote.
     */
    fun promoteStagingInstance(): OnePlusCgmDriverReal? {
        synchronized(lock) {
            val promoted = stagingReal ?: return null
            val retired = productionReal
            promoted.rebindStore(null)
            productionReal = promoted
            stagingReal = null
            return retired
        }
    }

    /**
     * Atomically switch Stub ↔ Real, migrating an optional [watcher] and shutting down the
     * previous driver so sessions / scanners do not linger on the unused instance.
     *
     * This only ever swaps stub and real for the **production** slot. It must never touch the
     * staging instance: turning the engineering switch off does not end a pre-soak, it only stops
     * production from using the real driver.
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
            // A driver that was shut down cannot open a session again, so it is dropped here and
            // the next call builds a fresh one.
            if (previous === productionReal) productionReal = null
            useRealSkeleton = useReal
            val next = default()
            if (watcher != null) {
                next.addWatcher(watcher)
            }
            return next
        }
    }
}
