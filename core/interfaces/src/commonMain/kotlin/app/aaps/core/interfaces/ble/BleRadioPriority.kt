package app.aaps.core.interfaces.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Who has the first claim on the Bluetooth radio right now.
 *
 * One phone radio is shared by every in process Bluetooth driver: the pump and, on this build, the
 * native CGM drivers. Most of the time they live together well enough. A pump setup is the one job
 * that does not: it has to find a base that has just been powered on, so it scans at the fastest
 * setting the platform offers, and it asks for the fastest connection interval. A CGM link that
 * keeps its normal share of the radio through that is enough to make the setup fail near the end.
 *
 * So a setup takes a lease. While it is held, every driver that is not the owner backs off: it
 * asks the platform for a slower connection interval on its own link and it starts no new scan.
 * A back off is **not** a disconnect. The CGM link stays up and its readings keep arriving, so a
 * pump change does not cost the user their glucose.
 *
 * **The lease can never go missing.** It lives in memory only, so a process restart always starts
 * with a free radio, and it ends by itself after the hold time even when nobody releases it. Both
 * are on purpose: a crashed or forgotten owner must not be able to leave a CGM degraded.
 *
 * ⚠️ ASYNC IMPACT: [owner] is a state flow read from any thread. A driver that reacts to it must
 * do its radio work on its own BLE executor, never on the collecting thread.
 */
interface BleRadioPriority {

    /** Name of the owner while a lease is held, or null when the radio is free. */
    val owner: StateFlow<String?>

    /**
     * Take the radio for [owner].
     *
     * Asking again while the same owner already holds it renews the hold time, so a caller may
     * simply ask on every step of a wizard without keeping track of what it asked before.
     *
     * @param maxHoldMs how long the lease may live at most. It is clamped to
     *   [MIN_HOLD_MS]..[MAX_HOLD_MS] and it ends by itself when it runs out.
     * @return true when the lease is held by [owner] now. False when somebody else holds it, in
     *   which case the caller must carry on without it rather than wait.
     */
    fun acquire(owner: String, maxHoldMs: Long = DEFAULT_HOLD_MS): Boolean

    /** Give the radio back. Does nothing when [owner] is not the current owner. */
    fun release(owner: String)

    companion object {

        /** Shortest hold that is worth taking. */
        const val MIN_HOLD_MS = 30_000L

        /**
         * Longest hold, whatever the caller asks for.
         *
         * A pump setup that is going well takes one to two minutes. Six covers three tries with
         * room to spare, and it is short enough that a lease left behind by a bug is a nuisance
         * rather than a danger.
         */
        const val MAX_HOLD_MS = 6L * 60L * 1000L

        /** What a caller gets when it does not name a hold time. */
        const val DEFAULT_HOLD_MS = MAX_HOLD_MS
    }
}
