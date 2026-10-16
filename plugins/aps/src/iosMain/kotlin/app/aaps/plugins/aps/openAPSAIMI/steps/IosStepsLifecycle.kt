package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.StepService
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioRepository
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStepsLifecycle
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.AtomicInt
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
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosStepsLifecycle @Inject constructor(
    private val aapsLogger: AAPSLogger,
    repository: IosHealthKitPhysioRepository,
) : AimiStepsLifecycle {

    private val source: IosStepBucketSource = repository

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private var loop: Job? = null
    private val refreshing = AtomicInt(0)

    override fun start() {
        if (loop?.isActive == true) return
        StepService.refreshRequest = { scope.launch { refreshOnce() } }
        loop = scope.launch {
            while (isActive) {
                refreshOnce()
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
            refreshStepCache(source, StepService.cache, aimiWallClockMs()) { isWarning, text ->
                if (isWarning) aapsLogger.warn(LTag.APS, "[$TAG] $text") else aapsLogger.info(LTag.APS, "[$TAG] $text")
            }
        } finally {
            refreshing.value = 0
        }
    }

    private companion object {

        const val TAG = "IosStepsLifecycle"

        const val REFRESH_INTERVAL_MS = 60_000L
    }
}

/**
 * One refresh of [cache] from [source]. A failed read leaves the old buckets and their old time, so
 * the cache turns stale on its own instead of showing old steps as current.
 *
 * @param log gets `true` for a warning and `false` for an info line.
 */
internal suspend fun refreshStepCache(
    source: IosStepBucketSource,
    cache: IosStepCache,
    nowMs: Long,
    log: (Boolean, String) -> Unit,
) {
    val before = cache.status()
    val buckets = source.fetchStepBuckets(nowMs)
    if (buckets == null) {
        log(true, "step refresh failed, status=${cache.status()}: the step getters answer 0 and that 0 means unknown, not no steps")
    } else {
        cache.update(buckets, nowMs)
        if (before != IosStepDataStatus.AVAILABLE) {
            log(false, "step data available, ${buckets.size} buckets with steps")
        }
    }
}
