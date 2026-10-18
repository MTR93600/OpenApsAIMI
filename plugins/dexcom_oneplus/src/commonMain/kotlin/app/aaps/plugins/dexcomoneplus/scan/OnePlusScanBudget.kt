package app.aaps.plugins.dexcomoneplus.scan

/**
 * Process-wide LE scan start budget shared by **all** ONE+ slots (production + staging) and the UI
 * discovery scan.
 *
 * Android silently stops delivering results when an app starts more than
 * [MAX_STARTS_PER_WINDOW_PLATFORM] LE scans within [WINDOW_MS] (`ScanManager`
 * "app is scanning too frequently"): no `onScanFailed`, no error — the callback simply never fires.
 *
 * The quota counts scan **registrations**, not scan time, so one long window is far cheaper than
 * several short ones. The persistent ADV wait therefore uses
 * `OnePlusBleSessionCyclePolicy.PERSISTENT_ADV_SCAN_MS` (20 s) rather than the per-OEM
 * `OemDeviceProfile.preConnectScanMs`: about 3 starts/min per slot, so even two slots plus the UI
 * discovery scan stay inside the platform's 10/min.
 *
 * ⚠️ This used to read "roughly every 8.25 s (~7.3 starts/min), which is under the quota". That
 * only ever held for the Samsung profile (`preConnectScanMs` 8 s). Generic used 3 s and Pixel 2 s,
 * giving 3.25 s / 2.25 s cycles — 18 and 27 starts/min, i.e. a *single* slot structurally over the
 * quota. The CUBOT field log of 2026-08-20 measured 18.3 starts/min, 13 platform throttle events,
 * and the budget deferring its own scans for 54 % of the wait. Keep any future window/delay change
 * consistent with the arithmetic above.
 *
 * This budget keeps one slot free for the UI discovery scan, so opening the Start screen can never
 * be starved by the background waits either.
 *
 * ⚠️ ASYNC IMPACT: [waitMsFor] / [record] are synchronized and non-blocking; the *caller* decides
 * whether it may sleep (background BLE executor: yes — UI thread: never, it only [record]s).
 */
object OnePlusScanBudget {

    /** Platform accounting window for the "scanning too frequently" throttle. */
    const val WINDOW_MS = 30_000L

    /** What the platform actually allows per [WINDOW_MS]. */
    const val MAX_STARTS_PER_WINDOW_PLATFORM = 5

    /** What we allow ourselves — one slot kept in reserve for the UI discovery scan. */
    const val MAX_STARTS_PER_WINDOW = MAX_STARTS_PER_WINDOW_PLATFORM - 1

    /** Longest single sleep a waiting caller does before re-checking (keeps `stop` responsive). */
    const val MAX_SLEEP_SLICE_MS = 500L

    private val starts = ArrayDeque<Long>()

    /** Elapsed-time until which no start is granted at all — see [blockFor]. */
    private var blockedUntilMs = 0L

    /** Elapsed-time until which the radio belongs to another job — see [lendRadioOut]. */
    private var lentOutUntilMs = 0L

    /**
     * Hold every scan start back for [durationMs], on top of the normal quota.
     *
     * Used when a caller has good reason to believe the OS is silently refusing our scans: the model
     * of the platform quota is then known to be too optimistic on this device, and the only safe
     * reaction is to scan less until the platform window has certainly rolled over.
     */
    @Synchronized
    fun blockFor(nowMs: Long, durationMs: Long) {
        val until = nowMs + durationMs.coerceAtLeast(0L)
        if (until > blockedUntilMs) blockedUntilMs = until
    }

    /**
     * Hold every scan start back while the radio is lent to another job, which today means a pump
     * setup — see [app.aaps.core.interfaces.ble.BleRadioPriority].
     *
     * Kept apart from [blockFor] on purpose. That one is the answer to the platform refusing our
     * scans and it has to run its whole window; this one ends the moment the other job says it is
     * done. One variable for both reasons would let either one cut the other short.
     *
     * @param maxDurationMs a safety net only: the hold also ends on [takeRadioBack], and it must
     *   never outlive the longest lease.
     */
    @Synchronized
    fun lendRadioOut(nowMs: Long, maxDurationMs: Long) {
        lentOutUntilMs = nowMs + maxDurationMs.coerceAtLeast(0L)
    }

    /** The other job has given the radio back. Any [blockFor] hold is left alone. */
    @Synchronized
    fun takeRadioBack() {
        lentOutUntilMs = 0L
    }

    /**
     * Milliseconds to wait before another `startScan` may be issued (0 = free slot available now).
     * Pure function of the recorded history — safe to call from any thread.
     */
    @Synchronized
    fun waitMsFor(nowMs: Long): Long {
        prune(nowMs)
        val blocked = (blockedUntilMs - nowMs).coerceAtLeast(0L)
        val lentOut = (lentOutUntilMs - nowMs).coerceAtLeast(0L)
        val held = maxOf(blocked, lentOut)
        if (held > 0L) return held
        if (starts.size < MAX_STARTS_PER_WINDOW) return 0L
        val oldest = starts.first()
        return (WINDOW_MS - (nowMs - oldest)).coerceAtLeast(0L)
    }

    /** Book a scan start at [nowMs]. Callers must invoke this for **every** `startScan`. */
    @Synchronized
    fun record(nowMs: Long) {
        prune(nowMs)
        starts.addLast(nowMs)
    }

    /**
     * Atomically take a start slot: returns 0 with the start booked, or the milliseconds to wait
     * before retrying. Checking [waitMsFor] and then [record] separately would let two slots racing
     * on their own BLE executors both pass the same free slot.
     */
    @Synchronized
    fun reserve(nowMs: Long): Long {
        val wait = waitMsFor(nowMs)
        if (wait > 0L) return wait
        starts.addLast(nowMs)
        return 0L
    }

    /** Recorded starts still inside the platform window (diagnostics / tests). */
    @Synchronized
    fun startsInWindow(nowMs: Long): Int {
        prune(nowMs)
        return starts.size
    }

    /** Test seam — drops the recorded history. */
    @Synchronized
    fun reset() {
        starts.clear()
        blockedUntilMs = 0L
        lentOutUntilMs = 0L
    }

    private fun prune(nowMs: Long) {
        // Also drops entries from a backwards clock jump so a bad timestamp cannot wedge the budget.
        while (starts.isNotEmpty() && (nowMs - starts.first() >= WINDOW_MS || nowMs < starts.first())) {
            starts.removeFirst()
        }
    }
}
