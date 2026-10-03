package app.aaps.plugins.libre3

import app.aaps.core.interfaces.source.SensorSlot
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
 * There can be a second real driver, for the pre-soak slot. It is built only when the user turns
 * the pre-soak on, it reads and writes its own preferences file, and it never feeds the loop until
 * [promoteStagingInstance] gives it the production role. With the pre-soak off, [staging] is never
 * called and this object hands out the same single real instance it always did.
 */
object Libre3CgmDrivers {

    /** Preferences namespace of the pre-soak slot. */
    const val STAGING_NAMESPACE = "staging"

    @Volatile
    var useRealSkeleton: Boolean = false
        private set

    private val lock = Any()

    /**
     * The instances are plain nullable fields and not `by lazy` on purpose.
     *
     * A driver that was shut down can never open a session again, because `shutdown` also stops its
     * executor for good. With `by lazy` the dead instance would be handed out for ever, and after
     * one promotion there would be no working production driver left. A dropped field is rebuilt on
     * the next call, so a second pre-soak and a second promotion still work.
     */
    @Volatile
    private var productionReal: Libre3CgmDriverReal? = null

    @Volatile
    private var stagingReal: Libre3CgmDriverReal? = null

    /** The one definition of a slot's namespace, shared by the drivers and by any UI that opens a store. */
    fun storeNamespace(slot: SensorSlot): String? = when (slot) {
        SensorSlot.PRODUCTION -> null
        SensorSlot.STAGING    -> STAGING_NAMESPACE
    }

    /** The real instance that feeds the loop. Built on first use. */
    fun realProduction(): Libre3CgmDriverReal = synchronized(lock) {
        productionReal ?: Libre3CgmDriverReal(Libre3PairingBlocks.factory()).also { productionReal = it }
    }

    /** The pre-soak instance. Built on first use, and always the real driver. */
    fun staging(): Libre3CgmDriverReal = synchronized(lock) {
        stagingReal ?: Libre3CgmDriverReal(Libre3PairingBlocks.factory(), STAGING_NAMESPACE)
            .also { stagingReal = it }
    }

    /**
     * The pre-soak instance if one was already built, without building one.
     *
     * Used by everything that only has to reach a pre-soak that is really running, for example the
     * radio lease. With the pre-soak switched off this stays null, so no second instance and no
     * second thread is ever created.
     */
    fun stagingOrNull(): Libre3CgmDriverReal? = stagingReal

    /**
     * Frees the pre-soak pointer and hands the instance back, so the caller can stop it.
     *
     * The pointer has to be freed together with the stop, because `Libre3CgmDriverReal.shutdown`
     * also stops the executor for good: a stopped instance that stayed in this field would be
     * handed to the next pre-soak, which could then never open a session.
     *
     * @return the pre-soak instance, for the caller to stop **outside** the lock, or null when
     *   there was none.
     */
    fun releaseStagingInstance(): Libre3CgmDriverReal? = synchronized(lock) {
        stagingReal.also { stagingReal = null }
    }

    /**
     * Makes the pre-soak slot give a sensor up, so the slot that feeds the loop can take it.
     *
     * Only for one case: both preferences files describe the same sensor, which is what a promotion
     * that was cut off half way leaves behind. The claim in `Libre3MacArbiter` then goes to whichever
     * thread asks first, and a refusal is final, so the loop could be left with no sensor at all
     * while the pre-soak tile shows glucose from it.
     *
     * The pre-soak link is really dropped, not only its claim: releasing the claim alone would leave
     * two links on one sensor, which breaks both pairings.
     *
     * @param mac the sensor the caller wants.
     * @param requester the instance that is asking. It is never stopped, even if it somehow is the
     *   pre-soak instance itself.
     * @return true when a pre-soak instance really held that sensor and was stopped.
     */
    fun yieldStagingSensorToProduction(mac: String, requester: Libre3CgmDriverReal): Boolean {
        val presoak = synchronized(lock) {
            val current = stagingReal ?: return false
            if (current === requester) return false
            if (!current.ownsSensor(mac)) return false
            stagingReal = null
            current
        }
        // Outside the lock, because `shutdown` closes a link and must not hold the driver registry.
        runCatching { presoak.shutdown() }
        return true
    }

    /**
     * The driver the plugin should use.
     *
     * The real one is reached only when the engineering switch is on **and** the files the pairing
     * needs really ship with this build. Without those files the real driver could not pair
     * anything, so handing it out would only turn a clear refusal into a confusing failure.
     */
    fun default(): Libre3CgmDriver =
        if (useRealSkeleton && Libre3RuntimeTables.pairingTablesPresent()) realProduction()
        else Libre3CgmDriverStub.instance

    /** Why the real driver is not in use, or null when it is. */
    fun realDriverBlockedReason(): String? = when {
        !useRealSkeleton -> "the engineering switch is off"
        else             -> missingTablesReason()
    }

    /**
     * Why the pre-soak cannot run, or null when it can.
     *
     * The pre-soak is always the real driver, so the engineering switch does not come into it and
     * only the pairing files matter.
     */
    fun stagingBlockedReason(): String? = missingTablesReason()

    /**
     * Hands the pre-soak instance the production role, keeping its live link.
     *
     * Under [lock]: the promoted instance is pointed at the production preferences file, it becomes
     * the production instance, and the pre-soak pointer is freed so the next pre-soak builds a
     * fresh instance.
     *
     * ⚠️ Do not turn these fields back into `by lazy`. A retired instance must be dropped, or the
     * second promotion, days later, would hand out a driver whose executor is already stopped.
     *
     * @return the instance that has just been retired, for the caller to shut down **outside** the
     *   lock, or null when there was no pre-soak instance to promote.
     */
    fun promoteStagingInstance(): Libre3CgmDriverReal? {
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
     * Switch between stub and real driver in one step, moving an optional [watcher] over and
     * stopping the driver that is no longer used.
     *
     * This only ever swaps stub and real for the **production** slot. It must never touch the
     * pre-soak instance: turning the engineering switch off does not end a pre-soak, it only stops
     * production from using the real driver.
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
            // A driver that was shut down cannot open a session again, so it is dropped here and
            // the next call builds a fresh one.
            if (previous === productionReal) productionReal = null
            useRealSkeleton = useReal
            val next = default()
            if (watcher != null) next.addWatcher(watcher)
            return next
        }
    }

    private fun missingTablesReason(): String? =
        if (Libre3RuntimeTables.pairingTablesPresent()) null
        else "this build does not ship the files the pairing needs: " +
            Libre3RuntimeTables.missingTables().joinToString()
}
