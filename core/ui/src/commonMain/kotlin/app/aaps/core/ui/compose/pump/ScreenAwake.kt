package app.aaps.core.ui.compose.pump

/**
 * Holds the screen awake and puts back what was there before, for [KeepScreenOnEffect].
 *
 * [read] and [write] are the platform flag (the iOS idle timer), and plain values in a test. Putting
 * back the value from before matters: the overview has its own "Keep screen on" setting, and leaving a
 * wizard must not switch it off.
 */
class ScreenAwake(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {

    private var before: Boolean? = null

    /** Switches the auto-lock off. A second call changes nothing. */
    fun hold() {
        if (before != null) return
        before = read()
        write(true)
    }

    /** Puts back the value from before [hold]. Without a hold it changes nothing. */
    fun release() {
        val previous = before ?: return
        before = null
        write(previous)
    }
}
