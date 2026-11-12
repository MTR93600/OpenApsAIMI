package app.aaps.plugins.sync.garmin

/**
 * A restored watch can be reported connected without `deviceCharacteristicsDiscovered`.
 *
 * Push waits for [ConnectIqDeviceStatus.READY]. If that callback never comes, the watch would stay
 * silent. After [WAIT_MS] still connected, [shouldReregister] is true once, so the caller registers
 * the device events again and logs one line. A later [ConnectIqDeviceStatus.READY], or any other
 * status, cancels the wait.
 */
internal class ConnectIqReadyNudge(
    private val nowMs: () -> Long,
    private val waitMs: Long = WAIT_MS,
) {
    private class Watch(val sinceMs: Long, var retried: Boolean)

    private val watches = HashMap<String, Watch>()

    /** True when [status] is the first connected report for [uuid], so the caller should wait. */
    fun onStatus(uuid: String, status: ConnectIqDeviceStatus): Boolean {
        if (status != ConnectIqDeviceStatus.CONNECTED) {
            watches.remove(uuid)
            return false
        }
        if (watches.containsKey(uuid)) return false
        watches[uuid] = Watch(nowMs(), retried = false)
        return true
    }

    /** True once, and only once, when [uuid] is still connected after [waitMs]. */
    fun shouldReregister(uuid: String): Boolean {
        val watch = watches[uuid] ?: return false
        if (watch.retried) return false
        if (nowMs() - watch.sinceMs < waitMs) return false
        watch.retried = true
        return true
    }

    companion object {
        const val WAIT_MS = 15_000L
    }
}
