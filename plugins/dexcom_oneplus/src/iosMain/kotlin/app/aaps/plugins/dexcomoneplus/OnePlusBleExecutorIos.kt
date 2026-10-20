package app.aaps.plugins.dexcomoneplus

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

internal actual fun onePlusBleExecutor(): OnePlusBleExecutor = OnePlusIosBleExecutor()

/**
 * [OnePlusBleExecutor] on a serial GCD queue.
 *
 * A serial queue gives the two things the session needs: one block at a time, and a thread that is
 * not the main one and not the central's. Blocking a GCD thread for minutes is normally a thing to
 * avoid, because the pool grows a thread for every blocked one; here it costs exactly one thread,
 * since this queue is serial and the driver builds one executor per driver instance.
 *
 * ⚠️ There is no way to cancel a block GCD has already started, and no way to interrupt a thread on
 * iOS. [shutdownNow] therefore drops everything still queued and lets a cycle already running reach
 * its own stop check. That is the same stop check Android relies on; Android only reaches it sooner
 * because an interrupt cuts the current wait short.
 */
private class OnePlusIosBleExecutor : OnePlusBleExecutor {

    @OptIn(ExperimentalForeignApi::class)
    private val queue = dispatch_queue_create("app.aaps.ble.cgm.dexcom.oneplus.session", null)

    @Volatile
    private var stopped = false

    @OptIn(ExperimentalForeignApi::class)
    override fun execute(block: () -> Unit) {
        // Matches the JVM executor, which throws RejectedExecutionException once it is shut down.
        check(!stopped) { "ONEPLUS_SESSION: the BLE executor is stopped" }
        dispatch_async(queue) {
            // Read again on the queue: shutdownNow may have been called while this waited its turn,
            // which is the half of shutdownNow that iOS can honour.
            if (!stopped) block()
        }
    }

    override fun shutdownNow() {
        stopped = true
    }
}
