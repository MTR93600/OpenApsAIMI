package app.aaps.plugins.dexcomoneplus

/**
 * The one thread the driver runs its blocking work on.
 *
 * A session is written as a blocking cycle - connect, authenticate, read a value, wait, go again -
 * so it needs a thread it can hold for minutes. It has to be exactly one thread: two cycles at once
 * on the same sensor would run two handshakes over the same link, which kills both.
 *
 * This is the slice of `Executors.newSingleThreadExecutor` the driver uses and nothing more,
 * because Apple has no such class.
 */
internal interface OnePlusBleExecutor {

    /**
     * Queues [block] on this executor's thread, behind everything queued before it.
     *
     * Throws when the executor has already been stopped by [shutdownNow]. That is what the JVM
     * executor did, so every call site in the driver already runs inside a `try`.
     */
    fun execute(block: () -> Unit)

    /**
     * Stops the executor for good. Nothing queued runs after this and nothing new is accepted.
     *
     * ⚠️ This is **not** how a running session is stopped, on either platform. The driver stops the
     * session itself first, and that is the signal the cycle reads. Android additionally interrupts
     * the thread, which cuts a wait short; iOS cannot interrupt a thread at all, so there a cycle
     * already running finishes its current wait before it sees that the session is over. Nothing
     * may be built on the interrupt.
     *
     * A stopped executor is never reused: the driver builds a fresh one in its place.
     */
    fun shutdownNow()
}

/** A new executor, stopped only by [OnePlusBleExecutor.shutdownNow]. */
internal expect fun onePlusBleExecutor(): OnePlusBleExecutor
