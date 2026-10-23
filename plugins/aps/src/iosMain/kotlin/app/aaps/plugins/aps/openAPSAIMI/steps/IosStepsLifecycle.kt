package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.StepService
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioRepository
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStepsLifecycle
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the step buckets come from. The HealthKit repository is the real one; a test can fake it. */
interface IosStepBucketSource {

    /** Steps per 5 minute bucket, or `null` when the answer is unknown. See `IosStepBuckets`. */
    suspend fun fetchStepBuckets(nowMs: Long): Map<Long, Int>?

    /**
     * End time of the newest step sample between [sinceMs] and [nowMs], or `null` when there is
     * none or the read failed. Used only to prove that a step source exists, see [IosStepEvidence].
     */
    suspend fun newestStepSampleMs(sinceMs: Long, nowMs: Long): Long?
}

/**
 * Whether HealthKit has shown that it can deliver steps at all.
 *
 * HealthKit does not report a refused read: it answers as if the store were empty. So a refresh
 * with no steps can mean "the person sat still" or "the app may not read steps". Android has the
 * same question and answers it with its sensor: the getters answer `null` until the step counter
 * has sent its first event (`StepService.hasReceivedSensorEvent`), and a real 0 only after that.
 *
 * The iOS proof is one step sample of any size in the last [WINDOW_MS]. An iPhone that is carried
 * writes step samples by itself, with or without a watch, so a whole day without a single one is
 * what a refused read, or motion tracking switched off, looks like. A shorter window would turn
 * every night into "unknown", while an Android phone with a sensor answers a real 0 at night.
 *
 * When a refresh finds no steps, the look-up is repeated at most every [PROBE_RETRY_MS] - also
 * when a proof is known, because a read permission taken away hides the old samples as well. So a
 * phone without access does not run a HealthKit query on every getter call, and a revoked
 * permission turns the steps to unknown within [PROBE_RETRY_MS].
 */
internal class IosStepEvidence {

    @Volatile
    private var newestSampleMs: Long? = null

    @Volatile
    private var lastProbeMs: Long? = null

    /** Records that a step sample ended at [timeMs]. */
    fun noteSampleAt(timeMs: Long) {
        val current = newestSampleMs
        if (current == null || timeMs > current) newestSampleMs = timeMs
    }

    /**
     * Records a look-up of the newest step sample, which REPLACES what was known. When the person
     * takes the read permission away, HealthKit hides every sample, old ones too, so an empty answer
     * must also forget a proof found before - or the getters would answer a fake 0 for a whole day.
     */
    fun noteProbe(newestSampleMs: Long?, nowMs: Long) {
        this.newestSampleMs = newestSampleMs
        lastProbeMs = nowMs
    }

    /** True when a step sample is known inside the last [WINDOW_MS]. A sample "in the future" counts. */
    fun isProven(nowMs: Long): Boolean {
        val newest = newestSampleMs ?: return false
        return nowMs - newest <= WINDOW_MS
    }

    /** True when a new look-up may run. A clock that moved back makes it due at once. */
    fun probeDue(nowMs: Long): Boolean {
        val last = lastProbeMs ?: return true
        val elapsed = nowMs - last
        return elapsed < 0 || elapsed >= PROBE_RETRY_MS
    }

    /** Forgets everything, like Android's `StepService.resetForNewRegistration`. */
    fun reset() {
        newestSampleMs = null
        lastProbeMs = null
    }

    companion object {

        const val WINDOW_MS = 24L * 60L * 60L * 1000L

        const val PROBE_RETRY_MS = 5L * 60L * 1000L
    }
}

/**
 * Keeps the iOS `StepService` cache fresh from HealthKit.
 *
 * Built on [IosHealthKitPhysioRepository], the one HealthKit client the real engine graph uses,
 * and not on the older windowed HealthKit port in `IosPlatformWearable.ios.kt`. That port blocks
 * its caller on a semaphore and sits behind `iosHealthKitApsEnabled`, which nothing sets; its own
 * comment says it may be retired in favour of the repository. This class adds no third client.
 *
 * Refresh runs every [REFRESH_INTERVAL_MS] while started. One minute is far below the 5 minute
 * loop tick and below the 5 minute bucket size, and it costs one small HealthKit query. A faster
 * rate would add no accuracy, because HealthKit itself only gets Watch steps in batches.
 * Worst case, a value used by a tick is about 70 seconds old (one interval plus the 10 second
 * query timeout) plus whatever delay HealthKit has before it holds the steps. If a getter finds
 * the cache unusable it also asks for an extra refresh, so a tick after the app was suspended
 * does not have to wait for the next minute.
 *
 * A failed refresh keeps the old buckets but does not renew their time, so after 10 minutes the
 * cache reports stale instead of showing old steps as current.
 *
 * ### Heart rate
 *
 * The same loop also runs [IosHeartRateSync], which writes HealthKit heart rate into the AAPS
 * heart rate table. It sits here because Android starts its Health Connect sync from the steps
 * lifecycle too (`AIMIStepsManagerMTR.start`). It is checked on every minute of the loop but runs
 * only every 4 hours, on the wall clock, so a phone that was suspended catches up on the next
 * minute after it wakes.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosStepsLifecycle @Inject constructor(
    private val aapsLogger: AAPSLogger,
    repository: IosHealthKitPhysioRepository,
    persistenceLayer: PersistenceLayer,
    preferences: Preferences,
) : AimiStepsLifecycle {

    private val source: IosStepBucketSource = repository
    private val evidence = IosStepEvidence()

    private val heartRateSync = IosHeartRateSync(
        source = repository,
        write = { rows -> persistenceLayer.insertOrUpdateHeartRates(rows) },
        isEnabled = { preferences.get(BooleanKey.AimiHealthConnectStepsEnable) },
        lastSyncMs = { preferences.get(LongNonKey.AimiHealthConnectLastSync) },
        storeLastSyncMs = { preferences.put(LongNonKey.AimiHealthConnectLastSync, it) },
        log = { isWarning, text -> log(isWarning, text) },
    )

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private var loop: Job? = null
    private val refreshing = AtomicInt(0)

    override fun start() {
        if (loop?.isActive == true) return
        evidence.reset()
        StepService.refreshRequest = { scope.launch { refreshOnce() } }
        loop = scope.launch {
            while (isActive) {
                refreshOnce()
                syncHeartRate()
                delay(REFRESH_INTERVAL_MS)
            }
        }
        aapsLogger.info(LTag.APS, "[$TAG] started")
    }

    override fun stop() {
        loop?.cancel()
        loop = null
        StepService.refreshRequest = null
        aapsLogger.info(LTag.APS, "[$TAG] stopped")
    }

    private suspend fun refreshOnce() {
        if (!refreshing.compareAndSet(0, 1)) return
        try {
            refreshStepCache(source, StepService.cache, evidence, aimiWallClockMs(), ::log)
        } finally {
            refreshing.value = 0
        }
    }

    /** Never throws: a heart rate problem must not stop the step refresh loop. */
    private suspend fun syncHeartRate() {
        try {
            heartRateSync.runIfDue(aimiWallClockMs())
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] heart rate sync failed", e)
        }
    }

    private fun log(isWarning: Boolean, text: String) {
        if (isWarning) aapsLogger.warn(LTag.APS, "[$TAG] $text") else aapsLogger.info(LTag.APS, "[$TAG] $text")
    }

    private companion object {

        const val TAG = "IosStepsLifecycle"

        const val REFRESH_INTERVAL_MS = 60_000L
    }
}

/**
 * One refresh of [cache] from [source].
 *
 * - A failed read leaves the old buckets and their old time, so the cache turns stale on its own
 *   instead of showing old steps as current.
 * - Buckets with steps are a reading, and they also prove that a step source exists.
 * - No steps at all is a real 0 only when [evidence] has seen a step sample in the last 24 hours
 *   (looked up from [source] when needed). Otherwise the cache is marked
 *   [IosStepDataStatus.NO_STEP_SOURCE] and the getters answer `null`, as on an Android phone with
 *   no working step sensor.
 *
 * @param log gets `true` for a warning and `false` for an info line.
 */
internal suspend fun refreshStepCache(
    source: IosStepBucketSource,
    cache: IosStepCache,
    evidence: IosStepEvidence,
    nowMs: Long,
    log: (Boolean, String) -> Unit,
) {
    val before = cache.status()
    val buckets = source.fetchStepBuckets(nowMs)
    if (buckets == null) {
        log(true, "step refresh failed, status=${cache.status()}: the step getters answer null (unknown) until a refresh works")
        return
    }
    val newestKey = buckets.keys.maxOrNull()
    if (newestKey != null) {
        evidence.noteSampleAt(IosStepBuckets.startOfKeyMs(newestKey))
    } else if (evidence.probeDue(nowMs)) {
        evidence.noteProbe(source.newestStepSampleMs(nowMs - IosStepEvidence.WINDOW_MS, nowMs), nowMs)
    }

    if (evidence.isProven(nowMs)) {
        cache.update(buckets, nowMs)
        if (before != IosStepDataStatus.AVAILABLE) {
            log(false, "step data available, ${buckets.size} buckets with steps")
        }
    } else {
        cache.markNoStepSource(nowMs)
        if (before != IosStepDataStatus.NO_STEP_SOURCE) {
            log(
                true,
                "no step sample in HealthKit for 24 h (read refused, or motion tracking off): " +
                    "the step getters answer null (unknown), not 0",
            )
        }
    }
}
