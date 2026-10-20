package app.aaps.pump.medtrum.comm

/**
 * Minimal logging seam for the Medtrum protocol.
 *
 * The Android driver uses `AAPSLogger` (injected via Dagger). This interface
 * decouples the protocol from the logging implementation. The M2/M3 layers
 * provide the real logger; tests use a no-op or recording fake.
 */
interface MedtrumLogger {
    fun debug(tag: String, message: String)
    fun error(tag: String, message: String)
    fun warn(tag: String, message: String)

    companion object {
        /** No-op logger for contexts where logging is not needed. */
        val NO_OP: MedtrumLogger = object : MedtrumLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun error(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String) = Unit
        }
    }
}
