package app.aaps.implementation.ble

import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one radio lease of the process.
 *
 * Metro's [SingleIn], not javax `@Singleton`: the graph that builds a contributed class is
 * generated in `:app`. A second copy would hand the same radio to two owners at once, which is the
 * whole thing this class exists to stop.
 *
 * ⚠️ ASYNC IMPACT: [acquire] and [release] take one lock and do not block on anything else. The
 * self release runs on the application scope, so it still fires while the screen that took the
 * lease is long gone.
 *
 * Shared code, so an iPhone has the same single lease. `@Synchronized` is JVM only and was replaced
 * by one [AapsLock] around the same three methods; it is reentrant and blocking, exactly what the
 * monitor was.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class BleRadioPriorityImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    // Plain CoroutineScope, not @ApplicationScope: that qualifier is javax and cannot appear in
    // commonMain. AppCoroutineBindings.unqualifiedAppScope binds the very same scope without it.
    private val scope: CoroutineScope
) : BleRadioPriority {

    /** Guards the owner and its pending self release - the two must change together. */
    private val lock = AapsLock()

    private val _owner = MutableStateFlow<String?>(null)
    override val owner: StateFlow<String?> = _owner.asStateFlow()

    /** The pending self release of the current lease, cancelled whenever the lease changes. */
    private var expiryJob: Job? = null

    override fun acquire(owner: String, maxHoldMs: Long): Boolean = lock.withLock {
        val current = _owner.value
        if (current != null && current != owner) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "BLE radio lease refused for $owner, held by $current")
            return@withLock false
        }
        val holdMs = maxHoldMs.coerceIn(BleRadioPriority.MIN_HOLD_MS, BleRadioPriority.MAX_HOLD_MS)
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(holdMs)
            releaseExpired(owner, holdMs)
        }
        // Set last, so a collector that reacts at once already sees a lease with a live self release.
        _owner.value = owner
        if (current == null) aapsLogger.info(LTag.PUMPBTCOMM, "BLE radio lease taken by $owner for ${holdMs}ms")
        true
    }

    override fun release(owner: String) = lock.withLock {
        if (_owner.value != owner) return@withLock
        expiryJob?.cancel()
        expiryJob = null
        _owner.value = null
        aapsLogger.info(LTag.PUMPBTCOMM, "BLE radio lease released by $owner")
    }

    /**
     * The safety net: gives the radio back when the owner never did.
     *
     * It checks the owner again because the lease may have been released and taken by somebody else
     * while this was waiting, and that newer lease must not be cut short.
     */
    private fun releaseExpired(owner: String, holdMs: Long) = lock.withLock {
        if (_owner.value != owner) return@withLock
        expiryJob = null
        _owner.value = null
        aapsLogger.warn(LTag.PUMPBTCOMM, "BLE radio lease of $owner ran out after ${holdMs}ms, radio given back")
    }
}
