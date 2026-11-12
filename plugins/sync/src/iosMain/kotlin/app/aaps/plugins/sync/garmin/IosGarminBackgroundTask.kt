package app.aaps.plugins.sync.garmin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * A few seconds of execution so a watch pull, or a Connect IQ push, can finish after iOS would
 * otherwise suspend the process. It is not a wake lock.
 *
 * ⚠️ ASYNC IMPACT: UIKit is only touched on the main queue. [runOnMain] uses `dispatch_async` when
 * the caller is elsewhere. It never uses `dispatch_sync`, so the CoreBluetooth queue cannot block
 * on main and main cannot block on it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun runOnMain(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
}

/**
 * One background task. [ended] is set by whichever of the completion and the expiration handler
 * runs first; the second end is a no-op, which is what UIKit requires.
 */
internal class TaskBox private constructor() {
    var id: ULong = UIBackgroundTaskInvalid
    var ended: Boolean = false
    private val gate = GarminLock()

    fun end() {
        val current = gate.withLock {
            if (ended) {
                UIBackgroundTaskInvalid
            } else {
                ended = true
                val value = id
                id = UIBackgroundTaskInvalid
                value
            }
        }
        if (current != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(current)
    }

    companion object {
        fun begin(name: String, autoEndSeconds: Int?): TaskBox {
            val box = TaskBox()
            val app = UIApplication.sharedApplication
            box.id = app.beginBackgroundTaskWithName(name) { box.end() }
            if (box.id == UIBackgroundTaskInvalid) {
                box.ended = true
                return box
            }
            if (autoEndSeconds != null) {
                val delay = dispatch_time(DISPATCH_TIME_NOW, autoEndSeconds.toLong() * 1_000_000_000L)
                dispatch_after(delay, dispatch_get_main_queue()) { box.end() }
            }
            return box
        }
    }
}

/** The HTTP pull that arrives with a radio wake. No-op when the caller is not yet on main. */
internal fun beginGarminHttpBackgroundTask() {
    runOnMain {
        TaskBox.begin("garmin-http", autoEndSeconds = GarminRadioWake.TASK_SECONDS)
    }
}

/**
 * One UIKit task covering every Connect IQ send that has not completed yet, whichever watch and
 * whichever app. [release] is the completion, the watchdog giving up, or the expiration handler
 * having already ended the task: [TaskBox.ended] makes the second end harmless.
 */
internal class GarminPushCover {
    private var outstanding = 0
    private var box: TaskBox? = null

    fun cover(count: Int) {
        if (count <= 0) return
        runOnMain { add(count) }
    }

    fun release(count: Int) {
        if (count <= 0) return
        runOnMain { drop(count) }
    }

    private fun add(count: Int) {
        outstanding += count
        val current = box
        if (current == null || current.ended) {
            box = TaskBox.begin("garmin-connectiq", autoEndSeconds = null)
        }
    }

    private fun drop(count: Int) {
        outstanding = (outstanding - count).coerceAtLeast(0)
        if (outstanding == 0) {
            box?.end()
            box = null
        }
    }
}
