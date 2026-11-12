package app.aaps.plugins.sync.garmin

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A tiny lock that exists on the JVM and on Kotlin/Native. `kotlin.synchronized` is JVM only, and
 * the push state is touched from the HTTP thread, the loop collector and the Connect IQ callback.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GarminLock {
    private val held = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) {
            // The critical sections only update maps. Waiting here is shorter than a message send.
        }
        try {
            return block()
        } finally {
            held.store(0)
        }
    }
}
