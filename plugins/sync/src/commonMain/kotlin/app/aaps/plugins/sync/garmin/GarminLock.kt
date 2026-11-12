package app.aaps.plugins.sync.garmin

/**
 * A lock that waits instead of spinning.
 *
 * The push state is touched from the HTTP thread, the loop collector and the Connect IQ callback.
 * `kotlin.synchronized` does not exist on Kotlin/Native, and a `compareAndSet` loop would burn the
 * CoreBluetooth thread if it ever waited there. Each platform supplies a mutex that parks the caller:
 * `ReentrantLock` on the JVM, `NSLock` on iPhone.
 */
internal expect class GarminLock() {
    fun <T> withLock(block: () -> T): T
}
