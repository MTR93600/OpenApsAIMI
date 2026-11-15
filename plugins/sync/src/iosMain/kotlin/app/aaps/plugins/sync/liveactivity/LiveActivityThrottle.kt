package app.aaps.plugins.sync.liveactivity

/**
 * At most one Live Activity update every [minIntervalMs], but a new reading always goes out at once.
 *
 * A 1-minute CGM and a loop result a few seconds after each reading would otherwise update the lock
 * screen twice a minute. A reading is never held back: a new one (or a reading that disappeared) is
 * what the user looks at, and holding it would make the shown value older than it needs to be.
 *
 * Not thread safe. [LiveActivityPublisher] calls it under its lock.
 */
class LiveActivityThrottle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastSentAt: Long? = null
    private var lastSentBg: Long? = null

    /**
     * How long to wait before this state may go out. 0 means send now.
     *
     * @param bgTimestamp time of the reading in the state, null without one.
     * @param force true for a send the user or iOS asked for (switch on, restart, app became active).
     */
    fun waitBeforeSend(now: Long, bgTimestamp: Long?, force: Boolean): Long {
        val sentAt = lastSentAt ?: return 0
        if (force) return 0
        if (bgTimestamp != lastSentBg) return 0
        // A clock that went back must not block updates for a long time.
        if (now < sentAt) return 0
        return (sentAt + minIntervalMs - now).coerceAtLeast(0)
    }

    fun sent(now: Long, bgTimestamp: Long?) {
        lastSentAt = now
        lastSentBg = bgTimestamp
    }

    fun reset() {
        lastSentAt = null
        lastSentBg = null
    }

    companion object {

        const val MIN_INTERVAL_MS = 30_000L
    }
}
