package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.HR
import kotlin.concurrent.Volatile

/** One heart rate reading from HealthKit: when it was taken and the beats per minute. */
data class IosHeartRateReading(val timestampMs: Long, val beatsPerMinute: Double)

/** Where the heart rate comes from. The HealthKit repository is the real one; a test can fake it. */
interface IosHeartRateSource {

    /** The newest reading between [sinceMs] and [nowMs], or `null` when there is none or the read failed. */
    suspend fun fetchNewestHeartRate(sinceMs: Long, nowMs: Long): IosHeartRateReading?
}

/**
 * Writes the iPhone's HealthKit heart rate into the AAPS heart rate table, the way Android writes
 * the Health Connect heart rate (`AIMIHealthConnectSyncServiceMTR.syncHeartRate`).
 *
 * AIMI reads heart rate from that table only (`heartRatesCached`, `HealthContextRepository`,
 * `UnifiedActivityProviderMTR`). Before this class nothing on iOS wrote it, so the engine always
 * used its 80 bpm substitute, even with an Apple Watch on the wrist.
 *
 * ### What is copied from Android, on purpose
 *
 * - **Gate:** the same switch, `BooleanKey.AimiHealthConnectStepsEnable` (on by default).
 * - **Cadence:** one run every [SYNC_INTERVAL_MS] (4 hours), the period of the Android WorkManager
 *   job. The time of the last run is kept in `LongNonKey.AimiHealthConnectLastSync`, the key
 *   Android writes, so a restart does not start a new period, as with WorkManager's `UPDATE`.
 * - **What is read:** the newest heart rate sample of the last [LOOKBACK_MS] (24 hours).
 * - **What is written:** one row. `timestamp` is the sample time, `duration` is 5 minutes, as
 *   Android writes it. Only `device` differs: [UnifiedActivityProviderMTR.HEALTHKIT_DEVICE], which
 *   the activity provider treats exactly like a Health Connect row.
 *
 * This is deliberately sparse. A denser feed would give an iPhone more heart rate driven dose
 * changes than an Android phone with the same watch writing into Health Connect, which is the
 * difference the owner's parity rule forbids. If Android's cadence changes, change this one with it.
 *
 * ### Where it is different
 *
 * - **No duplicate row.** When the newest sample is the one already written, nothing is written.
 *   Android writes the same sample again. Both give the same averages, the extra row only adds noise.
 * - **No "battery not low" condition.** WorkManager has one; iOS gives no such scheduling hint here.
 *
 * No permission, no data: a refused read gives no sample, and then no row is written.
 *
 * @param write stores the rows, normally `PersistenceLayer.insertOrUpdateHeartRates`.
 * @param log gets `true` for a warning and `false` for an info line.
 */
internal class IosHeartRateSync(
    private val source: IosHeartRateSource,
    private val write: suspend (List<HR>) -> Unit,
    private val isEnabled: () -> Boolean,
    private val lastSyncMs: () -> Long,
    private val storeLastSyncMs: (Long) -> Unit,
    private val log: (Boolean, String) -> Unit,
) {

    @Volatile
    private var lastWrittenSampleMs: Long? = null

    /** True when a run is due at [nowMs]: never ran, a full period passed, or the clock went back. */
    fun isDue(nowMs: Long): Boolean {
        val last = lastSyncMs()
        if (last == 0L) return true
        val elapsed = nowMs - last
        return elapsed < 0 || elapsed >= SYNC_INTERVAL_MS
    }

    /**
     * Runs one sync when it is due and the switch is on. Returns the row written, or `null` when
     * nothing was written.
     */
    suspend fun runIfDue(nowMs: Long): HR? {
        if (!isEnabled()) return null
        if (!isDue(nowMs)) return null

        val reading = source.fetchNewestHeartRate(nowMs - LOOKBACK_MS, nowMs)
        // Stored even when there was no sample, as Android stores its last sync time after a run
        // that found no heart rate. Otherwise a phone with no watch would query every minute.
        storeLastSyncMs(nowMs)
        if (reading == null) {
            log(false, "heart rate sync: no HealthKit heart rate in the last 24 h, nothing written")
            return null
        }
        if (reading.timestampMs == lastWrittenSampleMs) return null

        val row = HR(
            timestamp = reading.timestampMs,
            beatsPerMinute = reading.beatsPerMinute,
            duration = ROW_DURATION_MS,
            device = UnifiedActivityProviderMTR.HEALTHKIT_DEVICE,
        )
        return try {
            write(listOf(row))
            lastWrittenSampleMs = reading.timestampMs
            log(false, "heart rate sync: wrote ${row.beatsPerMinute} bpm at ${row.timestamp}")
            row
        } catch (e: Exception) {
            log(true, "heart rate sync: database write failed: ${e.message}")
            null
        }
    }

    companion object {

        /** The period of the Android Health Connect job. */
        const val SYNC_INTERVAL_MS = 4L * 60L * 60L * 1000L

        /** Same as Android's `HR_LOOKBACK_MS`. */
        const val LOOKBACK_MS = 24L * 60L * 60L * 1000L

        /** Same as Android, which writes its 5 minute sync interval as the row duration. */
        const val ROW_DURATION_MS = 5L * 60L * 1000L
    }
}
