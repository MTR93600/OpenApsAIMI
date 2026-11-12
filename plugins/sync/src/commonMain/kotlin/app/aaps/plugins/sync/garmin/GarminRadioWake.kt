package app.aaps.plugins.sync.garmin

/**
 * What to do when Bluetooth wakes the phone.
 *
 * The HTTP server is checked every time: opening it is cheap when it is already listening, and a
 * suspension can have dropped the socket. The short background task is started at most once per
 * [minGapMs], so a burst of radio callbacks (connect, then the glucose notifications) takes one
 * task of a few seconds instead of one task per packet.
 */
internal class GarminRadioWake(
    private val nowMs: () -> Long,
    private val minGapMs: Long = TASK_GAP_MS,
) {
    private var lastTaskMs: Long? = null

    fun onRadio(ensureServer: () -> Unit, beginTask: () -> Unit) {
        ensureServer()
        val now = nowMs()
        val previous = lastTaskMs
        // A null previous is the first wake. Subtracting from Long.MIN_VALUE overflows and would
        // look like the gap had not elapsed.
        if (previous != null && now - previous < minGapMs) return
        lastTaskMs = now
        beginTask()
    }

    companion object {
        /** Same length as the background task: a second wake inside it does not start another. */
        const val TASK_GAP_MS = 8_000L
        const val TASK_SECONDS = 8
    }
}
