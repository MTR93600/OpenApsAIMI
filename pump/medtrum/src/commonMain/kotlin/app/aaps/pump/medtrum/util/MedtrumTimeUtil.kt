package app.aaps.pump.medtrum.util

/**
 * Time conversion between the Medtrum pump clock and system time.
 *
 * Ported from the Android driver (pump/medtrum). The pump counts seconds since
 * 2014-01-01T00:00:00Z. The Android version used `java.time.Instant`; this version
 * uses plain epoch-millis arithmetic so it compiles in commonMain.
 *
 * [nowMillis] is the clock. It has no working default on purpose: it used to fall back to `0L`,
 * which made [getCurrentTimePumpSeconds] answer a large negative number, and that number is what
 * `SetTimePacket` would have written to the pump. A caller that only converts a pump time needs no
 * clock and may leave it out; a caller that asks for the current time without one now fails at
 * once instead of setting the pump clock to nonsense. The Android packet tests are what found this.
 */
class MedtrumTimeUtil(private val nowMillis: () -> Long = NO_CLOCK) {

    fun getCurrentTimePumpSeconds(): Long {
        return (nowMillis() - PUMP_EPOCH_MILLIS) / 1000
    }

    fun convertPumpTimeToSystemTimeMillis(pumpTime: Long): Long {
        return PUMP_EPOCH_MILLIS + pumpTime * 1000
    }

    companion object {
        /** 2014-01-01T00:00:00Z in epoch millis. */
        const val PUMP_EPOCH_MILLIS = 1388534400000L

        /** Refuses rather than inventing a time. See the class doc. */
        private val NO_CLOCK: () -> Long = { error("MedtrumTimeUtil was built without a clock, so it cannot answer the current time") }
    }
}
