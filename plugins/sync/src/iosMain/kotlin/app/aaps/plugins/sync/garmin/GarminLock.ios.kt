package app.aaps.plugins.sync.garmin

import platform.Foundation.NSLock

internal actual class GarminLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
