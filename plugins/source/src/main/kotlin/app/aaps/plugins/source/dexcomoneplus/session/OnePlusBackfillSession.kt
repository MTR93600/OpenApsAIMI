package app.aaps.plugins.source.dexcomoneplus.session

import android.util.Log
import app.aaps.plugins.source.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.source.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.source.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.source.dexcomoneplus.parse.OnePlusBackFillControlRx
import app.aaps.plugins.source.dexcomoneplus.parse.OnePlusBackFillStream
import app.aaps.plugins.source.dexcomoneplus.parse.OnePlusBackFillTx

/**
 * Short history pull after session up (A6.8) — G7/ONE+ BackFillTxMessage2 path.
 *
 * Sequence: enable ProbablyBackfill notify → write Control 0x59 window →
 * collect stream → decode → [onGlucose].
 *
 * ⚠️ ASYNC IMPACT: Blocks bleExecutor (Control + backfill awaits). [shouldContinue]
 * false / GATT disconnect must unblock both queues.
 *
 * Provenance: Ob1G5StateMachine.backFillIfNeeded / monitorBackFill / BackFillStream
 * at pin `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).
 */
class OnePlusBackfillSession(
    private val gatt: OnePlusGattClient,
    private val onGlucose: (OnePlusGlucoseSample) -> Unit = {},
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
) {

    /**
     * @param currentDexTimeSeconds from TransmitterTime (required > 0)
     * @return number of samples delivered
     */
    fun runOnce(
        currentDexTimeSeconds: Int,
        shouldContinue: () -> Boolean,
        lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    ): Int {
        if (!gatt.isConnected() || !shouldContinue()) return 0
        if (currentDexTimeSeconds < 1) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: backfill skipped — no dexTime",
            )
            return 0
        }

        val lookbackSec = (lookbackMs / 1000L).toInt().coerceAtLeast(1)
        val endDex = currentDexTimeSeconds
        val startDex = (currentDexTimeSeconds - lookbackSec).coerceAtLeast(1)
        if (startDex >= endDex) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: backfill skipped — empty window",
            )
            return 0
        }

        try {
            gatt.enableBackfillNotifications()
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_BACKFILL_CCCD_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
            return 0
        }
        try {
            Thread.sleep(CCCD_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return 0
        }

        val tx = OnePlusBackFillTx.build(startDex, endDex)
        try {
            gatt.writeControl(tx)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: wrote BackFillTx2 opcode=0x59 " +
                    "start=$startDex end=$endDex",
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_BACKFILL_WRITE_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
            return 0
        }

        // Optional Control ACK (same opcode 0x59).
        val ack = gatt.awaitControlNotify(ACK_TIMEOUT_MS)
        if (ack != null && OnePlusBackFillControlRx.isAck(ack)) {
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: BackFillControlRx ACK")
        } else if (ack != null) {
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: backfill Control notify non-ACK op=0x" +
                    (ack[0].toInt() and 0xff).toString(16),
            )
        }

        val stream = OnePlusBackFillStream()
        val deadline = System.currentTimeMillis() + COLLECT_TIMEOUT_MS
        var lastPacketMs = 0L
        while (shouldContinue() && gatt.isConnected() && System.currentTimeMillis() < deadline) {
            val wait = if (stream.hasData()) QUIET_TIMEOUT_MS else 1_000L
            val packet = gatt.awaitBackfillNotify(wait)
            if (!shouldContinue()) break
            if (packet == null) {
                if (stream.hasData() && lastPacketMs > 0L &&
                    System.currentTimeMillis() - lastPacketMs >= QUIET_TIMEOUT_MS
                ) {
                    break
                }
                continue
            }
            stream.pushG7(packet)
            lastPacketMs = System.currentTimeMillis()
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: backfill chunk ${packet.size}b total=${stream.byteCount()}",
            )
        }

        val nowMs = System.currentTimeMillis()
        val samples = stream.decode(currentDexTimeSeconds, nowMs)
        for (sample in samples) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.BG}: backfill mgdl=${sample.mgdl} ts=${sample.timestampMs}",
            )
            onGlucose(sample)
        }
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: backfill done samples=${samples.size} bytes=${stream.byteCount()}",
        )
        return samples.size
    }

    companion object {
        const val CCCD_SETTLE_MS: Long = 200L
        const val ACK_TIMEOUT_MS: Long = 5_000L
        const val COLLECT_TIMEOUT_MS: Long = 15_000L
        const val QUIET_TIMEOUT_MS: Long = 3_000L
        /** A6.8 short window (Ob1 G5 default 3 h; G7 allows 24 h). */
        const val DEFAULT_LOOKBACK_MS: Long = 3L * 60L * 60L * 1000L
    }
}
