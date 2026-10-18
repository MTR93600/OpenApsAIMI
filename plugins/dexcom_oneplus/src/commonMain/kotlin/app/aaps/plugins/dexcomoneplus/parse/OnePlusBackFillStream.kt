package app.aaps.plugins.dexcomoneplus.parse

import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Assembles ProbablyBackfill notifications and decodes G7/ONE+ records.
 *
 * Short-TxId path (`streamType > 0` in xDrip): concatenate payload bytes; each
 * record is 9 bytes: dexTime | glucose12 | type | extra | trend.
 *
 * Provenance: xDrip `BackFillStream` at pin
 * `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).
 */
class OnePlusBackFillStream {

    private val buffer = ByteBuffer.allocate(MAX_BUFFER).order(ByteOrder.LITTLE_ENDIAN)

    fun hasData(): Boolean = buffer.position() > 0

    fun byteCount(): Int = buffer.position()

    fun reset() {
        buffer.clear()
    }

    /** Append a G7/ONE+ backfill notification (full packet). */
    fun pushG7(packet: ByteArray) {
        if (packet.isEmpty()) return
        val remaining = buffer.remaining()
        if (remaining <= 0) return
        val n = minOf(packet.size, remaining)
        buffer.put(packet, 0, n)
    }

    /**
     * Decode buffered G7 records into glucose samples.
     *
     * @param currentDexTimeSeconds transmitter clock now (from TransmitterTime)
     * @param nowMs wall clock corresponding to [currentDexTimeSeconds]
     */
    fun decode(
        currentDexTimeSeconds: Int,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<OnePlusGlucoseSample> {
        val extent = buffer.position()
        if (extent < G7_RECORD_SIZE) return emptyList()
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.flip()
        val out = ArrayList<OnePlusGlucoseSample>()
        try {
            while (view.remaining() >= G7_RECORD_SIZE) {
                val dexTime = view.int
                val glucose = view.short.toInt() and 0x0fff
                val type = view.get().toInt() and 0xff
                view.get() // extra (G7)
                val trend = view.get().toInt()
                val state = OnePlusCalibrationState.parse(type)
                if (!state.usableGlucose()) continue
                if (dexTime == 0) continue
                val ts = fromDexTime(dexTime, currentDexTimeSeconds, nowMs)
                val age = nowMs - ts
                if (age < 0L || age > maxAgeMs) continue
                val sample = OnePlusGlucoseParser.toSample(
                    mgdl = glucose.toDouble(),
                    timestampMs = ts,
                    trendSlopeMgdlPerMin = trendToSlope(trend),
                ) ?: continue
                out.add(sample)
            }
        } catch (_: Exception) {
            // mismatched trailing bytes — return what we parsed
        }
        return out
    }

    companion object {
        const val G7_RECORD_SIZE: Int = 9
        const val MAX_BUFFER: Int = 2800
        /** Align with Ob1 short-TxId window (~24 h) with slack. */
        const val MAX_AGE_MS: Long = 25L * 60L * 60L * 1000L

        fun fromDexTime(dexTime: Int, currentDexTimeSeconds: Int, nowMs: Long): Long =
            nowMs - (currentDexTimeSeconds.toLong() - dexTime.toLong()) * 1000L

        private fun trendToSlope(trend: Int): Double? = OnePlusGlucoseParser.trendToSlope(trend)
    }
}
