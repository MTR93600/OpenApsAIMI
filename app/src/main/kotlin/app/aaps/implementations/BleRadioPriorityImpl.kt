package app.aaps.implementations

import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one radio lease of the process.
 *
 * `@Singleton` and not `@Reusable`: a second copy would hand the same radio to two owners at once,
 * which is the whole thing this class exists to stop.
 *
 * ⚠️ ASYNC IMPACT: [acquire] and [release] are synchronized and do not block. The self release runs
 * on the application scope, so it still fires while the screen that took the lease is long gone.
 */
@Singleton
class BleRadioPriorityImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    @ApplicationScope private val scope: CoroutineScope
) : BleRadioPriority {

    private val _owner = MutableStateFlow<String?>(null)
    override val owner: StateFlow<String?> = _owner.asStateFlow()

    /** The pending self release of the current lease, cancelled whenever the lease changes. */
    private var expiryJob: Job? = null

    @Synchronized
    override fun acquire(owner: String, maxHoldMs: Long): Boolean {
        val current = _owner.value
        if (current != null && current != owner) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "BLE radio lease refused for $owner, held by $current")
            return false
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
        return true
    }

    @Synchronized
    override fun release(owner: String) {
        if (_owner.value != owner) return
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
    @Synchronized
    private fun releaseExpired(owner: String, holdMs: Long) {
        if (_owner.value != owner) return
        expiryJob = null
        _owner.value = null
        aapsLogger.warn(LTag.PUMPBTCOMM, "BLE radio lease of $owner ran out after ${holdMs}ms, radio given back")
    }
}
