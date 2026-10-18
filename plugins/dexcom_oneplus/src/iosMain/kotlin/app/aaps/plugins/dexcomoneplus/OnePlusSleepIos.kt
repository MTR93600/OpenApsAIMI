package app.aaps.plugins.dexcomoneplus

import platform.posix.usleep

/**
 * There is no interruption to report on iOS, so this always sleeps the whole time and returns true.
 *
 * `usleep` takes microseconds in an unsigned 32 bit value, so a long wait is served in slices
 * rather than overflowing into a short one.
 */
internal actual fun onePlusSleep(millis: Long): Boolean {
    var left = millis
    while (left > 0L) {
        val slice = if (left > MAX_SLICE_MS) MAX_SLICE_MS else left
        usleep((slice * 1_000L).toUInt())
        left -= slice
    }
    return true
}

/** One second at a time, well inside what `usleep` can hold in microseconds. */
private const val MAX_SLICE_MS = 1_000L
