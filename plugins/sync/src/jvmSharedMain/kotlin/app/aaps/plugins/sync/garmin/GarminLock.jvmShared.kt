package app.aaps.plugins.sync.garmin

import java.util.concurrent.locks.ReentrantLock

internal actual class GarminLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
