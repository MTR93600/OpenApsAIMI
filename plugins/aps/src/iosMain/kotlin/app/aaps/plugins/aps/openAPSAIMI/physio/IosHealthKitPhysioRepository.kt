package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.Heartbeat
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.SleepSegment
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.WristTemperatureSample
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.BasalBodyTemperatureMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.HcRecoveryProxyThermalSource
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.OuraApiThermalClient
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataCache
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataOrigins
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataWindowMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSampleMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSourceTier
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import platform.Foundation.NSDate
import platform.Foundation.NSPredicate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKHeartbeatSeriesQuery
import platform.HealthKit.HKHeartbeatSeriesSample
import platform.HealthKit.HKObjectQueryNoLimit
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierAppleSleepingWristTemperature
import platform.HealthKit.HKQuantityTypeIdentifierBasalBodyTemperature
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierRestingHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionNone
import platform.HealthKit.HKQueryOptionStrictStartDate
import platform.HealthKit.HKSample
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKSeriesType
import platform.HealthKit.HKStatistics
import platform.HealthKit.HKStatisticsOptionCumulativeSum
import platform.HealthKit.HKStatisticsOptionDiscreteMin
import platform.HealthKit.HKStatisticsQuery
import platform.HealthKit.HKUnit
import platform.HealthKit.countUnit
import platform.HealthKit.degreeCelsiusUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.predicateForSamplesWithStartDate
import platform.HealthKit.unitDividedByUnit

/**
 * iOS half of [AimiPhysioSource] and [AimiPhysioDataSource], over HealthKit.
 *
 * The Android half is `AIMIPhysioDataRepositoryMTR` over Health Connect. AIMI has to reach the same
 * dose on either phone, so this class copies that one method by method, including what it answers
 * when something is missing: `0` for a heart rate or a step count, an empty list for HRV and
 * resting heart rate, `null` for sleep, an empty window for thermal. The 30 minute cache and the
 * activity source mode check in front of the step count are the same too.
 *
 * ### What is genuinely different, and why
 *
 * - **HRV is computed, not read.** Health Connect publishes RMSSD. HealthKit publishes SDNN, which
 *   is a different number from the same heartbeats and is not interchangeable with it. `hrvRmssd`
 *   scales insulin through `smbMult`, so SDNN is not put in that field. Instead the beat to beat
 *   intervals in `HKHeartbeatSeriesSample` are read and RMSSD is worked out from them, which is
 *   the real metric. **This costs coverage.** Apple records a heartbeat series only during its own
 *   background tachogram, which by default runs about every four hours, so a watch wearer gets
 *   roughly six one minute bursts a day, more with Irregular Rhythm Notifications or AFib History
 *   on, and none at all during workouts or Low Power Mode. On most ticks the honest answer is
 *   therefore no HRV data, which the dose path already handles. A substituted SDNN would look like
 *   better coverage and quietly be the wrong number.
 * - **Wrist temperature is absolute.** Health Connect carries the writer's baseline, HealthKit does
 *   not, so the baseline is worked out from the person's own history. See
 *   `IosHealthKitPhysioMath.wristTemperatureDeltas`.
 * - **A denied read looks exactly like no data.** HealthKit refuses to say which it is, by design.
 *   So nothing here tries to tell them apart: absence is reported as absence.
 *
 * An iPhone with no Apple Watch has no heart rate, no HRV, no resting heart rate and no wrist
 * temperature, so the physio path stays inert. That is the same as an Android phone with no
 * wearable, and it is the intended behaviour, not a gap.
 *
 * ### Threading
 *
 * Every method suspends and runs on [aapsIoDispatcher]. `HealthContextRepository` launches them and
 * never joins, so nothing here is ever on the main thread. Each HealthKit query is awaited with
 * `suspendCancellableCoroutine` and carries a timeout; a query that never answers is stopped rather
 * than parking a coroutine for good.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
class IosHealthKitPhysioRepository @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val ouraApiThermalClient: OuraApiThermalClient,
    private val keyValueCache: AimiKeyValueCache,
) : AimiPhysioSource, AimiPhysioDataSource {

    private companion object {

        const val TAG = "PhysioHealthKit"

        /** Same as Android. */
        const val CACHE_TTL_MS = 30 * 60 * 1000L

        /** Same as Android. */
        const val API_TIMEOUT_MS = 10_000L

        /**
         * The whole HRV read gets its own, longer budget, because one HealthKit query per heartbeat
         * series is needed and there can be many. Series are read newest first and whatever is
         * finished when the budget runs out is kept, which beats answering nothing.
         */
        const val HRV_BUDGET_MS = 20_000L

        /** Newest series only, so a person with AFib History on cannot make this unbounded. */
        const val MAX_HEARTBEAT_SERIES = 100

        /** The authorization sheet needs the app in front; a background try is given this long. */
        const val AUTHORIZATION_TIMEOUT_MS = 15_000L

        /** A failed authorization is retried no more often than this, never on every tick. */
        const val AUTHORIZATION_RETRY_MS = 60 * 60 * 1000L

        /** Sleep is read over the same 48 hours as on Android. */
        const val SLEEP_WINDOW_MS = 48L * 60L * 60L * 1000L

        /** Last heart rate is read over the same hour as on Android. */
        const val LAST_HR_WINDOW_MS = 60L * 60L * 1000L

        /** How far back the personal wrist temperature baseline is learned from. */
        const val THERMAL_BASELINE_DAYS = 30

        /** Resting heart rate is believable only inside this range, as on Android. */
        val PLAUSIBLE_BPM = 35..120
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STORE AND AUTHORIZATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Built on first use, never in the constructor.
     *
     * Metro builds this class while the graph is assembled, which can happen outside a running app,
     * and touching HealthKit there throws. `isHealthDataAvailable` is false on an iPad and in most
     * simulator runs, and then there is no store at all and every method answers its empty value.
     */
    private val store: HKHealthStore? by lazy {
        runCatching {
            if (HKHealthStore.isHealthDataAvailable()) HKHealthStore() else null
        }.onFailure {
            aapsLogger.error(LTag.APS, "[$TAG] HealthKit unavailable: ${it.message}")
        }.getOrNull()
    }

    private val authorizationGate = Mutex()
    private var authorizationDone = false
    private var lastAuthorizationAttemptMs = 0L

    /**
     * The store, with read authorization asked for once.
     *
     * The ask is behind a mutex so the four fetches `HealthContextRepository` starts together do
     * not each raise a sheet. Once HealthKit has answered, it is never asked again. If the ask
     * fails - the usual reason is that the app was in the background and no sheet could be shown -
     * it is tried again at the next tick after [AUTHORIZATION_RETRY_MS], so it stays off the per
     * tick path.
     *
     * Whether the person said yes is not checked, because HealthKit does not tell. A refused type
     * simply reads as empty.
     */
    private suspend fun authorizedStore(): HKHealthStore? {
        val healthStore = store ?: return null
        authorizationGate.lock()
        try {
            val nowMs = aimiWallClockMs()
            if (!authorizationDone && nowMs - lastAuthorizationAttemptMs >= AUTHORIZATION_RETRY_MS) {
                lastAuthorizationAttemptMs = nowMs
                val answered = try {
                    withTimeout(AUTHORIZATION_TIMEOUT_MS) { requestReadAuthorization(healthStore) }
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] Read authorization failed: ${e.message}")
                    false
                }
                if (answered) {
                    authorizationDone = true
                    aapsLogger.info(LTag.APS, "[$TAG] HealthKit read authorization answered")
                }
            }
        } finally {
            authorizationGate.unlock()
        }
        return healthStore
    }

    /** Every type this class ever reads, asked for in one sheet. */
    private fun readTypes(): Set<HKObjectType> {
        val types = mutableSetOf<HKObjectType>()
        listOf(
            HKQuantityTypeIdentifierStepCount,
            HKQuantityTypeIdentifierHeartRate,
            HKQuantityTypeIdentifierRestingHeartRate,
            HKQuantityTypeIdentifierAppleSleepingWristTemperature,
            HKQuantityTypeIdentifierBasalBodyTemperature,
        ).forEach { identifier ->
            quantityType(identifier)?.let { types += it }
        }
        HKCategoryTypeIdentifierSleepAnalysis?.let { identifier ->
            HKObjectType.categoryTypeForIdentifier(identifier)?.let { types += it }
        }
        runCatching { HKSeriesType.heartbeatSeriesType() }.getOrNull()?.let { types += it }
        return types
    }

    private suspend fun requestReadAuthorization(healthStore: HKHealthStore): Boolean =
        suspendCancellableCoroutine { continuation ->
            val types = readTypes()
            if (types.isEmpty()) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            healthStore.requestAuthorizationToShareTypes(
                typesToShare = null,
                readTypes = types,
            ) { answered, error ->
                if (error != null) {
                    aapsLogger.warn(LTag.APS, "[$TAG] Authorization error: ${error.localizedDescription}")
                    continuation.resume(false)
                } else {
                    continuation.resume(answered)
                }
            }
        }

    // ═══════════════════════════════════════════════════════════════════════
    // CACHE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * One cached answer.
     *
     * [daysBack] is part of the entry rather than part of a key, because a call for a different
     * window must not be served the old one. The Android side does the same with its cache key.
     */
    private data class CachedData<T>(
        val data: T,
        val daysBack: Int,
        val timestampMs: Long,
    ) {

        fun isValidFor(days: Int): Boolean =
            daysBack == days && aimiWallClockMs() < timestampMs + CACHE_TTL_MS
    }

    private val sleepCache = AtomicReference<CachedData<SleepDataMTR>?>(null)
    private val hrvCache = AtomicReference<CachedData<List<HRVDataMTR>>?>(null)
    private val rhrCache = AtomicReference<CachedData<List<RHRDataMTR>>?>(null)
    private val stepsCache = AtomicReference<CachedData<Int>?>(null)
    private val thermalCache = AtomicReference<CachedData<ThermalDataWindowMTR>?>(null)

    // ═══════════════════════════════════════════════════════════════════════
    // AimiPhysioSource
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Newest heart rate of the last hour, or `0` when there is none.
     *
     * Android asks Health Connect for the newest record and takes the last sample inside it. One
     * HealthKit sample is one reading, so the newest sample by end date is the same thing.
     */
    override suspend fun fetchLastHeartRate(): Int = withContext(aapsIoDispatcher) {
        val healthStore = authorizedStore() ?: return@withContext 0
        val heartRate = quantityType(HKQuantityTypeIdentifierHeartRate) ?: return@withContext 0
        val nowMs = aimiWallClockMs()
        val samples = readSamples(healthStore, heartRate, nowMs - LAST_HR_WINDOW_MS, nowMs)
            ?: return@withContext 0
        val newest = samples
            .filterIsInstance<HKQuantitySample>()
            .maxByOrNull { it.endDate.timeIntervalSince1970 }
            ?: return@withContext 0
        newest.quantity.doubleValueForUnit(bpmUnit()).toInt()
    }

    /**
     * Average steps per day over [daysBack], or `0`.
     *
     * The activity source mode check in front is the Android one: when the person told AIMI to
     * prefer the watch, or turned activity off, the platform health store is not read at all.
     */
    override suspend fun fetchStepsData(daysBack: Int, ignoreUnifiedSourceMode: Boolean): Int =
        withContext(aapsIoDispatcher) {
            if (!ignoreUnifiedSourceMode) {
                val mode = UnifiedActivityProviderMTR.getMode(keyValueCache)
                if (mode == UnifiedActivityProviderMTR.MODE_PREFER_WEAR ||
                    mode == UnifiedActivityProviderMTR.MODE_DISABLED
                ) {
                    return@withContext 0
                }
            }

            stepsCache.load()?.takeIf { it.isValidFor(daysBack) }?.let { return@withContext it.data }

            val healthStore = authorizedStore() ?: return@withContext 0
            val stepCount = quantityType(HKQuantityTypeIdentifierStepCount) ?: return@withContext 0
            val nowMs = aimiWallClockMs()
            val startMs = nowMs - daysBack * 24L * 60L * 60L * 1000L
            val answer = readStatistic(
                healthStore = healthStore,
                quantityType = stepCount,
                startMs = startMs,
                endMs = nowMs,
                option = HKStatisticsOptionCumulativeSum,
                unit = HKUnit.countUnit(),
            ) { it.sumQuantity() } ?: return@withContext 0

            // No step sample in the window is a real answer of zero, as it is on Android.
            val total = (answer.value ?: 0.0).toLong()
            val average = if (daysBack > 0) (total / daysBack).toInt() else 0
            stepsCache.store(CachedData(average, daysBack, aimiWallClockMs()))
            aapsLogger.info(LTag.APS, "[$TAG] Steps: total=$total, avg=$average/day")
            average
        }

    // ═══════════════════════════════════════════════════════════════════════
    // AimiPhysioDataSource - SLEEP
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun fetchSleepData(): SleepDataMTR? = withContext(aapsIoDispatcher) {
        sleepCache.load()?.takeIf { it.isValidFor(0) }?.let { return@withContext it.data }

        val healthStore = authorizedStore() ?: return@withContext null
        val identifier = HKCategoryTypeIdentifierSleepAnalysis ?: return@withContext null
        val sleepType = HKObjectType.categoryTypeForIdentifier(identifier) ?: return@withContext null
        val nowMs = aimiWallClockMs()
        // HKQueryOptionNone so a night that started before the window, or is still running, is
        // included. A strict start date would drop the sleep that is happening right now, which is
        // exactly the one `ongoingStartMs` is about.
        val samples = readSamples(
            healthStore = healthStore,
            sampleType = sleepType,
            startMs = nowMs - SLEEP_WINDOW_MS,
            endMs = nowMs,
            options = HKQueryOptionNone,
        ) ?: return@withContext null

        val segments = samples.filterIsInstance<HKCategorySample>().map { sample ->
            SleepSegment(
                startMs = sample.startDate.toEpochMs(),
                endMs = sample.endDate.toEpochMs(),
                categoryValue = sample.value,
            )
        }
        val sleep = IosHealthKitPhysioMath.sleepFrom(segments, nowMs)
        // Only a real night is cached. The Android side does the same: "no sleep yet" must not be
        // pinned for half an hour, or a night that starts inside that window is missed.
        if (sleep != null) {
            sleepCache.store(CachedData(sleep, 0, aimiWallClockMs()))
            aapsLogger.info(
                LTag.APS,
                "[$TAG] Sleep: ${sleep.durationHours} h from ${segments.size} samples",
            )
        }
        sleep
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AimiPhysioDataSource - HRV
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Real RMSSD per heartbeat series, newest first.
     *
     * Empty is the normal answer on most ticks: see the class KDoc for how rarely Apple records a
     * heartbeat series. Empty is also the answer when the read was refused, because HealthKit does
     * not separate the two.
     */
    override suspend fun fetchHRVData(daysBack: Int): List<HRVDataMTR> = withContext(aapsIoDispatcher) {
        hrvCache.load()?.takeIf { it.isValidFor(daysBack) }?.let { return@withContext it.data }

        val healthStore = authorizedStore() ?: return@withContext emptyList()
        val seriesType = runCatching { HKSeriesType.heartbeatSeriesType() }.getOrNull()
            ?: return@withContext emptyList()
        val nowMs = aimiWallClockMs()
        val startMs = nowMs - daysBack * 24L * 60L * 60L * 1000L

        val series = readHeartbeatSeries(healthStore, seriesType, startMs, nowMs)
            .sortedByDescending { it.endDate.timeIntervalSince1970 }
            .take(MAX_HEARTBEAT_SERIES)

        val deadlineMs = nowMs + HRV_BUDGET_MS
        val points = mutableListOf<HRVDataMTR>()
        for (sample in series) {
            if (aimiWallClockMs() >= deadlineMs) {
                aapsLogger.info(
                    LTag.APS,
                    "[$TAG] HRV budget spent after ${points.size} of ${series.size} series",
                )
                break
            }
            val beats = readHeartbeats(healthStore, sample) ?: continue
            val rmssd = IosHealthKitPhysioMath.rmssdMs(beats) ?: continue
            points += HRVDataMTR(
                timestamp = sample.endDate.toEpochMs(),
                rmssd = rmssd,
                source = "HealthKit(${sample.sourceRevision.source.name})",
            )
        }

        val sorted = points.sortedBy { it.timestamp }
        hrvCache.store(CachedData(sorted, daysBack, aimiWallClockMs()))
        if (sorted.isNotEmpty()) {
            aapsLogger.info(
                LTag.APS,
                "[$TAG] HRV: ${sorted.size} series, avg RMSSD=${sorted.map { it.rmssd }.average()} ms",
            )
        }
        sorted
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AimiPhysioDataSource - RESTING HEART RATE
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun fetchMorningRHR(daysBack: Int): List<RHRDataMTR> = withContext(aapsIoDispatcher) {
        rhrCache.load()?.takeIf { it.isValidFor(daysBack) }?.let { return@withContext it.data }

        val healthStore = authorizedStore() ?: return@withContext emptyList()
        val heartRate = quantityType(HKQuantityTypeIdentifierHeartRate) ?: return@withContext emptyList()
        val nowMs = aimiWallClockMs()
        val windows = IosHealthKitPhysioMath.morningWindows(
            nowMs = nowMs,
            daysBack = daysBack,
            zone = TimeZone.currentSystemDefault(),
        )

        val points = mutableListOf<RHRDataMTR>()
        for (window in windows) {
            val minBpm = readStatistic(
                healthStore = healthStore,
                quantityType = heartRate,
                startMs = window.startMs,
                endMs = window.endMs,
                option = HKStatisticsOptionDiscreteMin,
                unit = bpmUnit(),
            ) { it.minimumQuantity() }?.value
            if (minBpm != null && minBpm > 0.0) {
                points += RHRDataMTR(
                    timestamp = window.startMs,
                    bpm = minBpm.toInt(),
                    source = "HealthKit(DailyMin)",
                )
            }
        }

        var sorted = points.sortedBy { it.timestamp }
        if (sorted.isEmpty()) {
            sorted = readRestingHeartRateSamples(healthStore, daysBack, nowMs)
        }

        rhrCache.store(CachedData(sorted, daysBack, aimiWallClockMs()))
        if (sorted.isNotEmpty()) {
            aapsLogger.info(
                LTag.APS,
                "[$TAG] RHR: ${sorted.size} points, avg=${sorted.map { it.bpm }.average().toInt()} bpm",
            )
        }
        sorted
    }

    /**
     * Resting heart rate as Apple writes it, the fallback when no morning window held a reading.
     *
     * Mirrors `readRestingHeartRateRecordsAsRhr` on Android, same plausibility range included.
     */
    private suspend fun readRestingHeartRateSamples(
        healthStore: HKHealthStore,
        daysBack: Int,
        nowMs: Long,
    ): List<RHRDataMTR> {
        val resting = quantityType(HKQuantityTypeIdentifierRestingHeartRate) ?: return emptyList()
        val samples = readSamples(
            healthStore = healthStore,
            sampleType = resting,
            startMs = nowMs - daysBack * 24L * 60L * 60L * 1000L,
            endMs = nowMs,
        ) ?: return emptyList()
        return samples
            .filterIsInstance<HKQuantitySample>()
            .mapNotNull { sample ->
                val bpm = sample.quantity.doubleValueForUnit(bpmUnit()).toInt()
                if (bpm in PLAUSIBLE_BPM) {
                    RHRDataMTR(
                        timestamp = sample.startDate.toEpochMs(),
                        bpm = bpm,
                        source = "HealthKit(RestingHR:${sample.sourceRevision.source.name})",
                    )
                } else {
                    null
                }
            }
            .sortedBy { it.timestamp }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AimiPhysioDataSource - THERMAL
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Thermal data over the same three step chain as Android:
     *
     * 1. measured skin temperature from the platform health store - here Apple's sleeping wrist
     *    temperature, which needs an Apple Watch Series 8 or newer and is written once a night;
     * 2. the Oura API, when the person stored a token, which is shared code and behaves the same;
     * 3. a recovery proxy worked out from resting heart rate and HRV, also shared code.
     *
     * `resolvedSource` keeps the existing `ThermalDataOrigins` names on purpose. `ThermalBeliefEngine`
     * branches on them to tell a measured source from an inferred one, so a new name would silently
     * take the iPhone down a different branch.
     */
    override suspend fun fetchThermalWindow(daysBack: Int): ThermalDataWindowMTR = withContext(aapsIoDispatcher) {
        thermalCache.load()?.takeIf { it.isValidFor(daysBack) }?.let { return@withContext it.data }

        val fetchedAtMs = aimiWallClockMs()
        val healthStore = authorizedStore()
        val windowStartMs = fetchedAtMs - daysBack * 24L * 60L * 60L * 1000L

        if (healthStore != null) {
            val skin = readWristTemperature(healthStore, fetchedAtMs, windowStartMs)
            val basal = readBasalBodyTemperature(healthStore, windowStartMs, fetchedAtMs)
            if (skin.isNotEmpty() || basal != null) {
                return@withContext storeThermalWindow(
                    daysBack = daysBack,
                    window = ThermalDataWindowMTR(
                        skinSamples = skin,
                        basalBodyTemperature = basal,
                        fetchedAtMs = fetchedAtMs,
                        sourceTier = ThermalSourceTier.MEASURED,
                        resolvedSource = ThermalDataOrigins.HC_SKIN,
                    ),
                    logLabel = "HealthKit wrist: ${skin.size} samples, basal=${basal?.temperatureCelsius}",
                )
            }
        }

        val ouraSamples = ouraApiThermalClient.fetchSamples(daysBack)
        if (ouraSamples.isNotEmpty()) {
            return@withContext storeThermalWindow(
                daysBack = daysBack,
                window = ThermalDataWindowMTR(
                    skinSamples = ouraSamples,
                    fetchedAtMs = fetchedAtMs,
                    sourceTier = ThermalSourceTier.MEASURED,
                    resolvedSource = ThermalDataOrigins.OURA_API,
                ),
                logLabel = "Oura API: ${ouraSamples.size} readiness samples",
            )
        }

        val vitalsDays = daysBack.coerceAtLeast(7)
        val proxySamples = HcRecoveryProxyThermalSource.build(
            rhrPoints = fetchMorningRHR(vitalsDays),
            hrvPoints = fetchHRVData(vitalsDays),
            daysBack = daysBack,
            nowMs = fetchedAtMs,
        )
        if (proxySamples.isNotEmpty()) {
            val origin = proxySamples.last().dataOrigin
            return@withContext storeThermalWindow(
                daysBack = daysBack,
                window = ThermalDataWindowMTR(
                    skinSamples = proxySamples,
                    fetchedAtMs = fetchedAtMs,
                    sourceTier = ThermalSourceTier.INFERRED,
                    resolvedSource = origin,
                ),
                logLabel = "Recovery proxy: ${proxySamples.size} samples ($origin)",
            )
        }

        ThermalDataWindowMTR(fetchedAtMs = fetchedAtMs).also {
            thermalCache.store(CachedData(it, daysBack, aimiWallClockMs()))
            ThermalDataCache.update(it)
        }
    }

    private fun storeThermalWindow(
        daysBack: Int,
        window: ThermalDataWindowMTR,
        logLabel: String,
    ): ThermalDataWindowMTR {
        thermalCache.store(CachedData(window, daysBack, aimiWallClockMs()))
        ThermalDataCache.update(window)
        aapsLogger.info(LTag.APS, "[$TAG] Thermal: $logLabel")
        return window
    }

    /**
     * Sleeping wrist temperature, turned into deviations.
     *
     * Reads [THERMAL_BASELINE_DAYS] back rather than only the reported window, because the baseline
     * has to come from somewhere and HealthKit does not carry one.
     */
    private suspend fun readWristTemperature(
        healthStore: HKHealthStore,
        nowMs: Long,
        windowStartMs: Long,
    ): List<ThermalSampleMTR> {
        val wrist = quantityType(HKQuantityTypeIdentifierAppleSleepingWristTemperature)
            ?: return emptyList()
        val baselineStartMs = nowMs - THERMAL_BASELINE_DAYS * 24L * 60L * 60L * 1000L
        val samples = readSamples(healthStore, wrist, baselineStartMs, nowMs) ?: return emptyList()
        val celsius = HKUnit.degreeCelsiusUnit()
        val readings = samples.filterIsInstance<HKQuantitySample>().map { sample ->
            WristTemperatureSample(
                timestampMs = sample.endDate.toEpochMs(),
                celsius = sample.quantity.doubleValueForUnit(celsius),
                dataOrigin = sample.sourceRevision.source.name,
            )
        }
        return IosHealthKitPhysioMath.wristTemperatureDeltas(readings, windowStartMs)
    }

    private suspend fun readBasalBodyTemperature(
        healthStore: HKHealthStore,
        startMs: Long,
        endMs: Long,
    ): BasalBodyTemperatureMTR? {
        val basal = quantityType(HKQuantityTypeIdentifierBasalBodyTemperature) ?: return null
        val samples = readSamples(healthStore, basal, startMs, endMs) ?: return null
        val latest = samples
            .filterIsInstance<HKQuantitySample>()
            .maxByOrNull { it.startDate.timeIntervalSince1970 }
            ?: return null
        return BasalBodyTemperatureMTR(
            timestampMs = latest.startDate.toEpochMs(),
            temperatureCelsius = latest.quantity.doubleValueForUnit(HKUnit.degreeCelsiusUnit()),
            dataOrigin = latest.sourceRevision.source.name,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HEALTHKIT PLUMBING
    // ═══════════════════════════════════════════════════════════════════════

    private fun quantityType(identifier: String?): HKQuantityType? =
        identifier?.let { HKObjectType.quantityTypeForIdentifier(it) }

    private fun bpmUnit(): HKUnit = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())

    private fun NSDate.toEpochMs(): Long = (timeIntervalSince1970 * 1000.0).toLong()

    private fun predicate(startMs: Long, endMs: Long, options: ULong): NSPredicate =
        HKQuery.predicateForSamplesWithStartDate(
            NSDate.dateWithTimeIntervalSince1970(startMs / 1000.0),
            NSDate.dateWithTimeIntervalSince1970(endMs / 1000.0),
            options,
        )

    /**
     * Runs one HealthKit query and waits for it, with a timeout.
     *
     * The timeout is the point. A HealthKit query that never calls back would otherwise park this
     * coroutine for the life of the app; here it is cancelled, the query is stopped, and the caller
     * gets `null` and falls back to its empty answer.
     */
    private suspend fun <T> awaitQuery(
        healthStore: HKHealthStore,
        label: String,
        timeoutMs: Long = API_TIMEOUT_MS,
        build: (CancellableContinuation<T?>) -> HKQuery,
    ): T? = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val query = build(continuation)
                continuation.invokeOnCancellation { runCatching { healthStore.stopQuery(query) } }
                healthStore.executeQuery(query)
            }
        }
    } catch (e: Exception) {
        aapsLogger.warn(LTag.APS, "[$TAG] $label query failed: ${e.message}")
        null
    }

    private suspend fun readSamples(
        healthStore: HKHealthStore,
        sampleType: HKSampleType,
        startMs: Long,
        endMs: Long,
        options: ULong = HKQueryOptionStrictStartDate,
        limit: ULong = HKObjectQueryNoLimit,
    ): List<HKSample>? = awaitQuery(healthStore, sampleType.identifier) { continuation ->
        HKSampleQuery(
            sampleType,
            predicate(startMs, endMs, options),
            limit,
            null,
        ) { _, raw, error ->
            if (error != null) {
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] ${sampleType.identifier} read failed: ${error.localizedDescription}",
                )
                continuation.resume(null)
            } else {
                continuation.resume(raw.orEmpty().filterIsInstance<HKSample>())
            }
        }
    }

    /**
     * One statistic that HealthKit answered.
     *
     * The wrapper keeps "the query failed" (a `null` [StatisticResult]) apart from "there is no
     * data in this window" (a result holding `null`). Android treats the two differently: a window
     * with no reading is cached, a failed read is not.
     */
    private data class StatisticResult(val value: Double?)

    private suspend fun readStatistic(
        healthStore: HKHealthStore,
        quantityType: HKQuantityType,
        startMs: Long,
        endMs: Long,
        option: ULong,
        unit: HKUnit,
        pick: (HKStatistics) -> HKQuantity?,
    ): StatisticResult? = awaitQuery(healthStore, quantityType.identifier) { continuation ->
        HKStatisticsQuery(
            quantityType,
            predicate(startMs, endMs, HKQueryOptionStrictStartDate),
            option,
        ) { _, statistics, error ->
            if (error != null) {
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] ${quantityType.identifier} statistic failed: ${error.localizedDescription}",
                )
                continuation.resume(null)
            } else {
                continuation.resume(
                    StatisticResult(statistics?.let(pick)?.doubleValueForUnit(unit)),
                )
            }
        }
    }

    /**
     * The heartbeat series samples in the window.
     *
     * A date predicate on this type has been reported to answer nothing on some iOS releases while
     * the same query with no predicate answers everything. So an empty answer is retried without a
     * predicate and filtered here. The retry costs one extra query only when there is no HRV data
     * at all, which is the common case, and it is bounded by the same timeout.
     */
    private suspend fun readHeartbeatSeries(
        healthStore: HKHealthStore,
        seriesType: HKSeriesType,
        startMs: Long,
        endMs: Long,
    ): List<HKHeartbeatSeriesSample> {
        val inWindow = readSamples(healthStore, seriesType, startMs, endMs, HKQueryOptionNone)
            ?.filterIsInstance<HKHeartbeatSeriesSample>()
            .orEmpty()
        if (inWindow.isNotEmpty()) return inWindow

        val unfiltered = awaitQuery<List<HKSample>>(healthStore, "heartbeatSeries(all)") { continuation ->
            HKSampleQuery(seriesType, null, HKObjectQueryNoLimit, null) { _, raw, error ->
                if (error != null) {
                    continuation.resume(null)
                } else {
                    continuation.resume(raw.orEmpty().filterIsInstance<HKSample>())
                }
            }
        }.orEmpty()
        return unfiltered
            .filterIsInstance<HKHeartbeatSeriesSample>()
            .filter { it.endDate.toEpochMs() in startMs..endMs }
    }

    /**
     * Every beat in one series.
     *
     * `HKHeartbeatSeriesQuery` calls its handler once per beat and a last time with `done`, so the
     * beats are gathered and the coroutine is resumed only at the end. The list is only touched
     * from the handler, which HealthKit serialises.
     */
    private suspend fun readHeartbeats(
        healthStore: HKHealthStore,
        sample: HKHeartbeatSeriesSample,
    ): List<Heartbeat>? = awaitQuery(healthStore, "heartbeats") { continuation ->
        val beats = mutableListOf<Heartbeat>()
        HKHeartbeatSeriesQuery(sample) { _, timeSinceSeriesStart, precededByGap, done, error ->
            when {
                error != null -> continuation.resume(null)
                done          -> continuation.resume(beats.toList())
                else          -> beats += Heartbeat(timeSinceSeriesStart, precededByGap)
            }
        }
    }
}
