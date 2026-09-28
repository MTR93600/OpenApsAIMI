package app.aaps.plugins.aps.openAPSAIMI

/**
 * Android half of [aimiWaitMs], on `Thread.sleep`.
 *
 * This is the exact wait the retry helper used before it moved to shared code, interrupt handling
 * included: restore the flag the JVM cleared when it raised the exception, and tell the caller the
 * wait did not finish so it stops retrying.
 */
actual fun aimiWaitMs(millis: Long): Boolean =
    try {
        Thread.sleep(millis)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
