package app.aaps.core.interfaces.maintenance

import app.aaps.core.data.ue.Sources
import kotlinx.coroutines.flow.StateFlow

/**
 * Stops AAPS on this phone, and starts it again, both by hand.
 *
 * Stopped means: no command reaches the pump, the loop does not run, the sensor drivers let their
 * sensor go, and the pump unreachable and missed readings alarms stay quiet. The app itself stays
 * open, so the user can look at the history and start it again. It is the safe state for handing the
 * pump and the sensor to another phone, and it is kept across a restart, so the phone does not take
 * them back on its own.
 *
 * "Exit" is not the same: on Android the app is started again by its own keep-alive, and an iPhone
 * app may not close itself at all.
 */
interface AapsStop {

    /** True while AAPS is stopped on this phone. */
    val isStopped: StateFlow<Boolean>

    /** Stops AAPS. Does nothing when it is already stopped. */
    suspend fun stop(source: Sources)

    /** Starts AAPS again. Does nothing when it is not stopped. */
    suspend fun resume(source: Sources)
}

/**
 * A plugin that holds a radio link of its own, outside the pump command queue: a sensor driver.
 *
 * [AapsStop] calls [onAapsStop] on every enabled plugin of this kind when AAPS is stopped, and
 * [onAapsResume] when it is started again. While AAPS is stopped such a plugin must not connect, also
 * when the app is started again.
 *
 * Not for a pump driver. Its link is the command queue, which the stop already closes, and stopping
 * the driver itself could cut a command that is still running, maybe a bolus.
 */
interface StopsWithAaps {

    suspend fun onAapsStop()

    suspend fun onAapsResume()
}
