package app.aaps.core.interfaces.pump.ble.session

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * A Bluetooth event that woke, or is waking, the process.
 *
 * iOS does not run a suspended app for a local TCP connection. The Garmin HTTP server can only
 * answer a watch pull while a radio wake has the process up. The work that follows — opening a
 * socket, talking to UIKit — must not run on the CoreBluetooth queue `app.aaps.ble`.
 *
 * [ping] only decides whether this event is inside the throttle and, if not, enqueues the listener
 * on the main queue. It does not call the listener. There is no `dispatch_sync` in either direction:
 * the radio queue must not wait for main, and main must not wait for the radio queue.
 *
 * ⚠️ ASYNC IMPACT: [ping] runs on the CoreBluetooth queue. [enqueue] is `dispatch_async` to main.
 * The listener runs later, on main.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
object IosRadioWake {

    var listener: (() -> Unit)? = null

    /**
     * Where the listener is handed off. Production uses the main queue and does not run the block
     * before [ping] returns. Tests replace it so they can see the hand-off without a run loop.
     */
    var enqueue: (() -> Unit) -> Unit = { block ->
        dispatch_async(dispatch_get_main_queue(), block)
    }

    var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** Notifications during one connection come in a burst. One hop per window is enough. */
    var minGapMs: Long = 1_000L

    private val lastEnqueueMs = AtomicLong(NEVER)

    fun ping() {
        if (listener == null) return
        val now = nowMs()
        val previous = lastEnqueueMs.load()
        if (previous != NEVER && now - previous < minGapMs) return
        if (!lastEnqueueMs.compareAndSet(previous, now)) return
        enqueue(fun() {
            val current = listener ?: return
            try {
                current()
            } catch (_: Throwable) {
            }
        })
    }

    fun resetForTests() {
        listener = null
        nowMs = { Clock.System.now().toEpochMilliseconds() }
        minGapMs = 1_000L
        lastEnqueueMs.store(NEVER)
        enqueue = { block -> dispatch_async(dispatch_get_main_queue(), block) }
    }

    private const val NEVER = Long.MIN_VALUE
}
