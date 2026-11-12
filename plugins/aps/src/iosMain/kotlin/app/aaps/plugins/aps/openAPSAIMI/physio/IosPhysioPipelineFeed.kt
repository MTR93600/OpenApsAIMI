package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable

/**
 * How far back one physio run reads, and whether it may ask the language model.
 *
 * The three windows are Android's three WorkManager jobs, so the baseline on an iPhone is built
 * from the same readings over the same periods. Declared narrowest first, so a wider window can be
 * seen to cover a narrower one.
 */
internal enum class PhysioFeedWindow(val daysBack: Int, val runLLM: Boolean) {

    /** Android's `PhysioRealtimeWorker`, every 15 minutes. */
    REALTIME(1, false),

    /** Android's `PhysioMetabolicWorker`, every 30 minutes. */
    METABOLIC(3, false),

    /** Android's `PhysioDailyWorker`, once a day, the only one that may ask the language model. */
    DAILY(7, true),
}

/**
 * Decides which window, if any, is due on a given tick.
 *
 * One tick runs at most one window, and it is the widest one that is due. A wider window already
 * contains the narrower ones, so running it satisfies them too - see [markRan]. This is the one
 * place where iOS is deliberately not a copy of Android: there, three separate jobs can land
 * together and feed the same rolling baseline twice inside a minute.
 *
 * @param startedAtMs when the app started. The two wider windows count from there, so the first
 *   tick picks [PhysioFeedWindow.REALTIME] as a bootstrap run rather than a seven day one with a
 *   language model call.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PhysioFeedCadence(startedAtMs: Long) {

    companion object {

        const val REALTIME_PERIOD_MS = 15L * 60L * 1000L
        const val METABOLIC_PERIOD_MS = 30L * 60L * 1000L
        const val DAILY_PERIOD_MS = 24L * 60L * 60L * 1000L
    }

    private val realtimeRanAtMs = AtomicLong(0L)
    private val metabolicRanAtMs = AtomicLong(startedAtMs)
    private val dailyRanAtMs = AtomicLong(startedAtMs)

    /**
     * The widest window that is due, or null when none is.
     *
     * A clock that reads as being in the past - the person moved their phone's clock back - makes
     * every window due. Sitting it out could stop the physio path for hours.
     */
    fun due(nowMs: Long): PhysioFeedWindow? = when {
        isDue(nowMs, dailyRanAtMs.load(), DAILY_PERIOD_MS)         -> PhysioFeedWindow.DAILY
        isDue(nowMs, metabolicRanAtMs.load(), METABOLIC_PERIOD_MS) -> PhysioFeedWindow.METABOLIC
        isDue(nowMs, realtimeRanAtMs.load(), REALTIME_PERIOD_MS)   -> PhysioFeedWindow.REALTIME
        else                                                       -> null
    }

    /** Records a finished run. It counts for every window as narrow as this one, or narrower. */
    fun markRan(window: PhysioFeedWindow, atMs: Long) {
        realtimeRanAtMs.store(atMs)
        if (window.ordinal >= PhysioFeedWindow.METABOLIC.ordinal) metabolicRanAtMs.store(atMs)
        if (window.ordinal >= PhysioFeedWindow.DAILY.ordinal) dailyRanAtMs.store(atMs)
    }

    private fun isDue(nowMs: Long, lastMs: Long, periodMs: Long): Boolean {
        val elapsed = nowMs - lastMs
        return elapsed < 0 || elapsed >= periodMs
    }
}

/**
 * Holds the runs back while the phone is locked.
 *
 * A run that finds the health data locked is not counted as run, so its window stays due. Trying
 * again on every tick would read HealthKit every five minutes for nothing while the phone is in a
 * pocket, so after a lock the ticks wait. The unlock notice ends the wait at once; if it never
 * comes (iOS did not deliver it to a suspended app), the wait ends after [LOCK_RETRY_MS].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PhysioLockPause {

    companion object {

        const val LOCK_RETRY_MS = 15L * 60L * 1000L
    }

    private val lockedAtMs = AtomicLong(0L)

    /** A run found the data locked at [nowMs]. */
    fun locked(nowMs: Long) = lockedAtMs.store(nowMs)

    /** True while ticks must not start a run. A clock that went back ends the wait. */
    fun paused(nowMs: Long): Boolean {
        val at = lockedAtMs.load()
        if (at == 0L) return false
        val elapsed = nowMs - at
        return elapsed in 0 until LOCK_RETRY_MS
    }

    /** The phone was unlocked. True when a run was waiting for it. */
    fun unlocked(): Boolean = lockedAtMs.exchange(0L) != 0L
}

/**
 * The iOS half of [PhysioPipelineFeed]: it runs [PhysioPipeline], and it is the only thing that
 * does so on this platform.
 *
 * iOS has no WorkManager, so there is no schedule to hang the run on. What it does have is the
 * health context refresh, which happens on every loop tick, so the cadence is kept here instead,
 * in [PhysioFeedCadence].
 *
 * ### Nothing waits for it
 *
 * The caller is on the loop's path, so the run is launched on the IO dispatcher and never joined.
 * A run already in flight means this tick does nothing at all.
 *
 * ### Start and stop
 *
 * Nothing runs until `IosPhysioLifecycle.start()` has opened its gate, and nothing new runs after
 * `stop()`. This is the iOS form of Android cancelling its WorkManager jobs.
 *
 * ### Locked phone
 *
 * HealthKit cannot be read while the phone is locked. A run that meets that throws
 * [PhysioDataLockedException] and stores nothing; see [PhysioLockPause] for when it is tried again.
 * The unlock is the iOS notice `UIApplicationProtectedDataDidBecomeAvailable`.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosPhysioPipelineFeed @Inject constructor(
    private val pipeline: PhysioPipeline,
    private val lifecycle: IosPhysioLifecycle,
    private val aapsLogger: AAPSLogger,
) : PhysioPipelineFeed {

    private companion object {

        const val TAG = "PhysioFeedIos"
    }

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val running = AtomicBoolean(false)
    private val cadence = PhysioFeedCadence(aimiWallClockMs())
    private val lockPause = PhysioLockPause()

    init {
        // The feed lives as long as the app, so the observer is never removed.
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationProtectedDataDidBecomeAvailable,
            `object` = null,
            queue = null,
        ) { _ ->
            if (lockPause.unlocked()) {
                aapsLogger.info(LTag.APS, "[$TAG] phone unlocked, running the physio window that waited")
                onHealthContextRefresh()
            }
        }
    }

    override fun onHealthContextRefresh() {
        if (!lifecycle.isRunning) return
        if (!pipeline.isPhysioAssistantEnabled()) return
        if (running.load()) return
        if (lockPause.paused(aimiWallClockMs())) return

        val window = cadence.due(aimiWallClockMs()) ?: return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            var counted = true
            try {
                pipeline.run(daysBack = window.daysBack, runLLM = window.runLLM)
            } catch (e: PhysioDataLockedException) {
                // Not run: the window stays due and waits for the unlock.
                counted = false
                lockPause.locked(aimiWallClockMs())
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Physio run failed: ${e.message}", e)
            } finally {
                if (counted) cadence.markRan(window, aimiWallClockMs())
                running.store(false)
            }
        }
    }
}
