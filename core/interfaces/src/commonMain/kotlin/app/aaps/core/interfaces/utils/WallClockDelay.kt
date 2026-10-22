package app.aaps.core.interfaces.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Suspends until the wall clock [now] reaches [endTimeMs].
 *
 * One long `delay` is not enough on iOS: while the system suspends the app, the delay does not
 * count down, so it would end late by the time spent suspended. Sleeping in steps of at most
 * [maxStepMs] and reading the wall clock after each step means a wake-up is noticed within one
 * step. Returns at once when [endTimeMs] is already in the past.
 */
suspend fun delayUntilWallClock(endTimeMs: Long, now: () -> Long, maxStepMs: Long = 30_000L) {
    require(maxStepMs > 0)
    while (true) {
        val remaining = endTimeMs - now()
        if (remaining <= 0) return
        delay(min(remaining, maxStepMs))
    }
}

/**
 * One pending action at a wall-clock time, for platforms without a system job scheduler.
 *
 * A new [schedule] replaces the pending action, and [cancel] drops it. The action runs in [scope]
 * and lives only as long as the process does.
 */
class WallClockTimer(private val scope: CoroutineScope, private val now: () -> Long) {

    private var pending: Job? = null

    fun schedule(endTimeMs: Long, action: suspend () -> Unit) {
        cancel()
        pending = scope.launch {
            delayUntilWallClock(endTimeMs, now)
            action()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
