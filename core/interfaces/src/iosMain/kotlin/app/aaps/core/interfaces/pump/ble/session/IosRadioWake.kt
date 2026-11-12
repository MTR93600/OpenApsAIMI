package app.aaps.core.interfaces.pump.ble.session

/**
 * A Bluetooth event that woke, or is waking, the process.
 *
 * iOS does not run a suspended app for a local TCP connection. The Garmin HTTP server can only
 * answer a watch pull while a radio wake has the process up, so the Garmin plugin listens here and
 * takes a short background task. The listener is optional. A throw is swallowed: a failure in that
 * plugin must not break the sensor or the pump session that delivered the event.
 *
 * ⚠️ ASYNC IMPACT: [ping] runs on the CoreBluetooth queue, inside the delegate callback. The
 * listener has to return quickly.
 */
object IosRadioWake {

    var listener: (() -> Unit)? = null

    fun ping() {
        val current = listener ?: return
        try {
            current()
        } catch (_: Throwable) {
        }
    }
}
