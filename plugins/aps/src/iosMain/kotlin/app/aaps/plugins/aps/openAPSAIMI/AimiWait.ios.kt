package app.aaps.plugins.aps.openAPSAIMI

import platform.posix.usleep

/** Longest single `usleep`, so the microsecond argument can never overflow its unsigned range. */
private const val MAX_CHUNK_MS = 1_000L

/** Microseconds in one millisecond. */
private const val MICROS_PER_MS = 1_000L

/**
 * iOS half of [aimiWaitMs], on `usleep`.
 *
 * It really waits. A no-op here would be a silent behaviour change: the retry helper would fire its
 * three attempts back to back at an API that had just asked it to slow down. There is no HTTP
 * implementation on iOS today, so nothing reaches this yet, but a wait that lies is the kind of
 * thing that is never noticed until it is in production.
 *
 * `usleep` takes microseconds in an unsigned 32 bit value, so the wait is taken one second at a
 * time. There is no thread interrupt on this platform, so the wait always runs to its end.
 */
actual fun aimiWaitMs(millis: Long): Boolean {
    var remaining = millis
    while (remaining > 0) {
        val chunk = minOf(remaining, MAX_CHUNK_MS)
        usleep((chunk * MICROS_PER_MS).toUInt())
        remaining -= chunk
    }
    return true
}
