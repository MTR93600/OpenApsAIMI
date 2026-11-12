package app.aaps.plugins.sync.garmin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * A few seconds of execution after a Bluetooth wake, so a watch pull that arrives with the glucose
 * can still be answered. iOS suspends the app again when the radio callback returns; this asks to
 * stay up briefly. It is not a wake lock and it does not keep the app up overnight.
 *
 * ⚠️ ASYNC IMPACT: [beginGarminBackgroundTask] may be called from the CoreBluetooth queue.
 * Beginning the task is synchronous, on that queue, so the assertion exists before the callback
 * returns. Ending it is scheduled on the main queue.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun beginGarminBackgroundTask() {
    val app = UIApplication.sharedApplication
    val box = TaskBox()
    box.id = app.beginBackgroundTaskWithName("garmin-http") {
        endGarminBackgroundTask(app, box)
    }
    if (box.id == UIBackgroundTaskInvalid) return
    val delay = dispatch_time(DISPATCH_TIME_NOW, GarminRadioWake.TASK_SECONDS * 1_000_000_000L)
    dispatch_after(delay, dispatch_get_main_queue()) {
        endGarminBackgroundTask(app, box)
    }
}

private class TaskBox {
    var id: ULong = UIBackgroundTaskInvalid
    var ended: Boolean = false
}

private val endGate = GarminLock()

private fun endGarminBackgroundTask(app: UIApplication, box: TaskBox) {
    val id = endGate.withLock {
        if (box.ended) return
        box.ended = true
        val current = box.id
        box.id = UIBackgroundTaskInvalid
        current
    }
    if (id != UIBackgroundTaskInvalid) app.endBackgroundTask(id)
}
