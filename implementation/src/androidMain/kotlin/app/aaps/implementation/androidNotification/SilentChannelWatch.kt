package app.aaps.implementation.androidNotification

/**
 * Watches an alarm that left its whole sound to the player (a silent notification channel).
 *
 * Every [intervalMs] it asks [isAudible]. The first time the answer is no - the player could not
 * start, or another alarm took it - it calls [ringFromChannel] once and stops, so the alarm is never
 * left silent. [stop] ends the watch, for a cancelled alarm or a newer one.
 *
 * The timer is passed in, so a test can run the checks without a real clock.
 */
internal class SilentChannelWatch(
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val intervalMs: Long
) {

    @Volatile
    private var current: Runnable? = null

    fun start(isAudible: () -> Boolean, ringFromChannel: () -> Unit) {
        stop()
        val check = object : Runnable {
            override fun run() {
                if (current !== this) return
                if (isAudible()) {
                    schedule(this, intervalMs)
                    return
                }
                current = null
                ringFromChannel()
            }
        }
        current = check
        schedule(check, intervalMs)
    }

    fun stop() {
        current?.let(unschedule)
        current = null
    }
}
