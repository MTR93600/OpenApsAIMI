package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiIsMainThread
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.keys.ACTIVITY_SOURCE_MODE_DISABLED
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlinx.coroutines.runBlocking
/**
 * 🎛️ Unified Activity Provider - MTR Implementation
 *
 * Orchestrates data retrieval from multiple sources (Garmin watchface, Wear OS, Health Connect, Phone)
 * based on user preferences and data freshness validation.
 *
 * **Heart rate:** Garmin CIQ rows (`Garmin-Watchface`, legacy `Garmin`) follow the same priority
 * rules as steps so physio `hrNow` matches dashboard ingestion.
 *
 * **Window totals:** Health Connect and phone sync store, on each [SC] row, both per-interval
 * counts (`steps5min`, `steps15min`, …) for the same sync instant. For standard windows (5–180 min),
 * the **latest** row’s matching field is used when fresh enough and non-zero. Sources that only
 * populate `steps5min` (e.g. Garmin HTTP delta) leave longer windows at zero — those are treated
 * as unset and fall back to per-bucket max(`steps5min`) aggregation across the requested span.
 * Long or non-standard windows still use bucket aggregation only.
 *
 * DB reads are **synchronous** on the calling thread to avoid empty results from fire-and-forget
 * async loads (race with immediate cache read).
 */
@SingleIn(AppScope::class)
class UnifiedActivityProviderMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) : ActivityVitalsProvider {

    companion object {
        private const val TAG = "ActivityProvider"

        const val PREF_KEY_SOURCE_MODE = "aimi_activity_source_mode"

        const val MODE_PREFER_WEAR = "prefer_wear"
        const val MODE_AUTO_FALLBACK = "auto"
        const val MODE_HEALTH_CONNECT_ONLY = "hc_only"

        /** Shared with commonMain via [ACTIVITY_SOURCE_MODE_DISABLED] — see its KDoc. */
        const val MODE_DISABLED = ACTIVITY_SOURCE_MODE_DISABLED

        const val DEFAULT_MODE = MODE_AUTO_FALLBACK

        private const val SOURCE_HC = "HealthConnect"

        /**
         * Device name of the rows the iPhone writes from HealthKit (`IosHeartRateSync`).
         *
         * HealthKit has the role on iOS that Health Connect has on Android: it is the phone's own
         * health store. So these rows take every branch a Health Connect row takes, see
         * [isPlatformHealthStore]. No Android writer uses this name, and heart rate rows are not
         * synced to Nightscout, so on Android nothing here changes.
         */
        internal const val HEALTHKIT_DEVICE = "HealthKit"
        private const val SOURCE_PHONE = "PhoneSensor"
        private const val SOURCE_GARMIN = "Garmin-Watchface"
        /** [LoopHubImpl.storeHeartRate] default when watchface omits `device`. */
        private const val SOURCE_GARMIN_LEGACY = "Garmin"

        /** HC / phone sync may lag; beyond this, prefer bucket aggregation. */
        private const val MAX_ROW_AGE_MS = 10 * 60 * 1000L

        private const val MINUTE_MS = 60_000L
        /** Match requested window to [SC] column (±2.5 min). */
        private const val WINDOW_SLACK_MS = 150_000L

        /**
         * Same read as the instance [getMode], for a caller that is not this class.
         *
         * It must go through [Preferences]: that is where the AIMI settings screen writes the value. It
         * used to read the platform's old preference file directly, which since the encrypted settings
         * store (upstream 74c37d157fc) is only read once to move the settings, so a change made after
         * the update was never seen here.
         */
        fun getMode(preferences: Preferences): String = preferences.get(AimiStringKey.ActivitySourceMode)

        /**
         * Picks the freshest HR row for [mode] (Garmin / Wear / HC priority).
         * [records] should be sorted by [HR.timestamp] descending when possible.
         */
        /**
         * Aggregates step totals for [startMs]..[nowMs] from pre-loaded [records] and [mode] source priority.
         */
        internal fun resolveStepsTotalSince(
            records: List<SC>,
            mode: String,
            startMs: Long,
            nowMs: Long,
        ): StepsResult? {
            if (mode == MODE_DISABLED || records.isEmpty()) return null

            val filtered = filterStepsRecordsForMode(records, mode)
            if (filtered.isEmpty()) return null

            val durationMs = nowMs - startMs
            val latest = filtered.maxByOrNull { it.timestamp }!!
            val stalenessMs = nowMs - latest.timestamp

            val fromPrefilledWindow = stepsFromPrefilledWindowFields(latest, durationMs, stalenessMs)
            val bucketTotal = sumMaxSteps5PerFiveMinuteBucket(filtered)
            val totalSteps = fromPrefilledWindow ?: bucketTotal

            return StepsResult(
                steps = totalSteps,
                timestamp = nowMs,
                source = latest.device,
                duration = durationMs,
            )
        }

        private fun filterStepsRecordsForMode(records: List<SC>, mode: String): List<SC> =
            when (mode) {
                MODE_PREFER_WEAR ->
                    records.filter { isWearDevice(it.device) }
                        .ifEmpty { records.filter { it.device == SOURCE_GARMIN } }

                MODE_HEALTH_CONNECT_ONLY ->
                    records.filter { isPlatformHealthStore(it.device) }

                MODE_AUTO_FALLBACK -> {
                    val garmin = records.filter { it.device == SOURCE_GARMIN }
                    val wear = records.filter { isWearDevice(it.device) }
                    val hcPhone = records.filter { isPlatformHealthStore(it.device) || it.device == SOURCE_PHONE }
                    when {
                        garmin.isNotEmpty() -> garmin
                        wear.isNotEmpty() -> wear
                        else -> hcPhone
                    }
                }
                else -> emptyList()
            }

        internal fun stepsFromPrefilledWindowFields(latest: SC, durationMs: Long, stalenessMs: Long): Int? {
            if (stalenessMs > MAX_ROW_AGE_MS) return null
            fun near(minutes: Int): Boolean {
                val target = minutes * MINUTE_MS
                return kotlin.math.abs(durationMs - target) <= WINDOW_SLACK_MS
            }
            val v = when {
                near(5) -> latest.steps5min
                near(10) -> latest.steps10min
                near(15) -> latest.steps15min
                near(30) -> latest.steps30min
                near(60) -> latest.steps60min
                near(180) -> latest.steps180min
                else -> return null
            }
            // Garmin HTTP stores only steps5min; zero in longer windows means "unset", not "no steps".
            if (v == 0 && !near(5)) return null
            return v.coerceAtLeast(0)
        }

        internal fun sumMaxSteps5PerFiveMinuteBucket(filtered: List<SC>): Int =
            filtered
                .groupBy { it.timestamp / (5 * MINUTE_MS) }
                .values
                .sumOf { bucket -> bucket.maxOfOrNull { it.steps5min.coerceAtLeast(0) } ?: 0 }

        internal fun resolveLatestHeartRate(records: List<HR>, mode: String): HrResult? {
            if (mode == MODE_DISABLED || records.isEmpty()) return null

            val sorted = if (records.size <= 1) records else records.sortedByDescending { it.timestamp }
            val garminRecord = sorted.firstOrNull { isGarminDevice(it.device) }
            val wearRecord = sorted.firstOrNull { isWearDevice(it.device) }
            val hcRecord = sorted.firstOrNull { isPlatformHealthStore(it.device) }

            val picked = when (mode) {
                MODE_PREFER_WEAR -> wearRecord ?: garminRecord ?: hcRecord
                MODE_HEALTH_CONNECT_ONLY -> hcRecord
                MODE_AUTO_FALLBACK -> garminRecord ?: wearRecord ?: hcRecord
                else -> null
            }
            return picked?.let { hrToResult(it) }
        }

        /** True for a row from the phone's own health store: Health Connect on Android, HealthKit on iOS. */
        internal fun isPlatformHealthStore(device: String?): Boolean =
            device == SOURCE_HC || device == HEALTHKIT_DEVICE

        internal fun isGarminDevice(device: String?): Boolean =
            device == SOURCE_GARMIN || device == SOURCE_GARMIN_LEGACY

        internal fun isWearDevice(device: String?): Boolean {
            if (device == null) return false
            if (isGarminDevice(device)) return false
            return !isPlatformHealthStore(device) && device != SOURCE_PHONE
        }

        private fun hrToResult(hr: HR): HrResult =
            HrResult(bpm = hr.beatsPerMinute, timestamp = hr.timestamp, source = hr.device)
    }

    override fun getLatestSteps(windowMs: Long): StepsResult? {
        val mode = getMode()
        if (mode == MODE_DISABLED) return null

        val now = aimiWallClockMs()
        val start = now - windowMs

        return try {
            val records = loadStepsRecords(start, now).sortedByDescending { it.timestamp }

            if (records.isEmpty()) return null

            val garminRecord = selectLatestDeltaRecord(records.filter { it.device == SOURCE_GARMIN })
            val wearRecord = selectLatestDeltaRecord(records.filter { isWearDevice(it.device) })
            val hcRecord = selectLatestDeltaRecord(records.filter { isPlatformHealthStore(it.device) })
            val phoneRecord = selectLatestDeltaRecord(records.filter { it.device == SOURCE_PHONE })

            val result = when (mode) {
                MODE_PREFER_WEAR -> {
                    wearRecord?.let { toStepsResult(it) }
                        ?: garminRecord?.let { toStepsResult(it) }
                }
                MODE_HEALTH_CONNECT_ONLY -> {
                    hcRecord?.let { toStepsResult(it) }
                }
                MODE_AUTO_FALLBACK -> {
                    garminRecord?.let { toStepsResult(it) }
                        ?: wearRecord?.let { toStepsResult(it) }
                        ?: hcRecord?.let { toStepsResult(it) }
                        ?: phoneRecord?.let { toStepsResult(it) }
                }
                else -> null
            }
            result
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error fetching steps", e)
            null
        }
    }

    fun getStepsTotalSince(startMs: Long): StepsResult? {
        val mode = getMode()
        if (mode == MODE_DISABLED) return null

        val now = aimiWallClockMs()

        return try {
            val records = loadStepsRecords(startMs, now).sortedBy { it.timestamp }
            resolveStepsTotalSince(records, mode, startMs, now)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error fetching total steps", e)
            null
        }
    }

    override fun getLatestHeartRate(windowMs: Long): HrResult? {
        val mode = getMode()
        if (mode == MODE_DISABLED) return null

        val now = aimiWallClockMs()
        val start = now - windowMs

        return try {
            resolveLatestHeartRate(loadHrRecords(start, now), mode)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error fetching HR", e)
            null
        }
    }

    private fun getMode(): String {
        return preferences.get(AimiStringKey.ActivitySourceMode)
    }

    private fun toStepsResult(sc: SC): StepsResult {
        return StepsResult(
            steps = sc.steps5min,
            timestamp = sc.timestamp,
            source = sc.device,
            duration = sc.duration
        )
    }

    private fun selectLatestDeltaRecord(records: List<SC>): SC? {
        if (records.isEmpty()) return null
        return records.firstOrNull { it.duration in 299_000L..301_000L } ?: records.first()
    }

    private fun loadStepsRecords(start: Long, end: Long): List<SC> {
        if (aimiIsMainThread()) {
            aapsLogger.warn(LTag.APS, "[$TAG] steps read skipped on main thread (avoid blocking UI)")
            return emptyList()
        }
        return try {
            runBlocking { persistenceLayer.getStepsCountFromTimeToTime(start, end) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] steps DB read failed", e)
            emptyList()
        }
    }

    private fun loadHrRecords(start: Long, end: Long): List<HR> {
        if (aimiIsMainThread()) {
            aapsLogger.warn(LTag.APS, "[$TAG] HR read skipped on main thread (avoid blocking UI)")
            return emptyList()
        }
        return try {
            runBlocking { persistenceLayer.getHeartRatesFromTimeToTime(start, end) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] HR DB read failed", e)
            emptyList()
        }
    }

}
