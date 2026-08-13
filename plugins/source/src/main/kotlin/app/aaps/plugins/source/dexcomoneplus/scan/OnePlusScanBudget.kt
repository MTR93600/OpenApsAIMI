package app.aaps.plugins.source.dexcomoneplus.scan

/**
 * Process-wide LE scan start budget shared by **all** ONE+ slots (production + staging) and the UI
 * discovery scan.
 *
 * Android silently stops delivering results when an app starts more than
 * [MAX_STARTS_PER_WINDOW_PLATFORM] LE scans within [WINDOW_MS] (`ScanManager`
 * "app is scanning too frequently"): no `onScanFailed`, no error — the callback simply never fires.
 * A single slot waiting for an ADV restarts a bounded scan roughly every 8.25 s (~7.3 starts/min),
 * which is under the quota; **two** slots doing that concurrently (~15/min) is over it and blinds
 * both of them at once, which is exactly the failure mode the dual-instance work introduced.
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
     * Milliseconds to wait before another `startScan` may be issued (0 = free slot available now).
     * Pure function of the recorded history — safe to call from any thread.
     */
    @Synchronized
    fun waitMsFor(nowMs: Long): Long {
        prune(nowMs)
        val blocked = (blockedUntilMs - nowMs).coerceAtLeast(0L)
        if (blocked > 0L) return blocked
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
    }

    private fun prune(nowMs: Long) {
        // Also drops entries from a backwards clock jump so a bad timestamp cannot wedge the budget.
        while (starts.isNotEmpty() && (nowMs - starts.first() >= WINDOW_MS || nowMs < starts.first())) {
            starts.removeFirst()
        }
    }
}
