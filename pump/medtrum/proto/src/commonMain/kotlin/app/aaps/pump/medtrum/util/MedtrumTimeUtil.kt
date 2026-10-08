package app.aaps.pump.medtrum.util

/**
 * Time conversion between the Medtrum pump clock and system time.
 *
 * Ported from the Android driver (pump/medtrum). The pump counts seconds since
 * 2014-01-01T00:00:00Z. The Android version used `java.time.Instant`; this version
 * uses plain epoch-millis arithmetic so it compiles in commonMain.
 *
 * [nowMillis] is a seam for the current time (defaults to a platform clock via
 * expect/actual in a later lot; for M1 the caller supplies it or tests pin it).
 */
class MedtrumTimeUtil(private val nowMillis: () -> Long = { 0L }) {

    fun getCurrentTimePumpSeconds(): Long {
        return (nowMillis() - PUMP_EPOCH_MILLIS) / 1000
    }

    fun convertPumpTimeToSystemTimeMillis(pumpTime: Long): Long {
        return PUMP_EPOCH_MILLIS + pumpTime * 1000
    }

    companion object {
        /** 2014-01-01T00:00:00Z in epoch millis. */
        const val PUMP_EPOCH_MILLIS = 1388534400000L
    }
}
