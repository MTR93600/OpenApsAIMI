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
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosPhysioPipelineFeed @Inject constructor(
    private val pipeline: PhysioPipeline,
    private val aapsLogger: AAPSLogger,
) : PhysioPipelineFeed {

    private companion object {

        const val TAG = "PhysioFeedIos"
    }

    private val scope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val running = AtomicBoolean(false)
    private val cadence = PhysioFeedCadence(aimiWallClockMs())

    override fun onHealthContextRefresh() {
        if (!pipeline.isPhysioAssistantEnabled()) return
        if (running.load()) return

        val window = cadence.due(aimiWallClockMs()) ?: return
        if (!running.compareAndSet(false, true)) return

        scope.launch {
            try {
                pipeline.run(daysBack = window.daysBack, runLLM = window.runLLM)
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Physio run failed: ${e.message}", e)
            } finally {
                cadence.markRan(window, aimiWallClockMs())
                running.store(false)
            }
        }
    }
}
