package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioLifecycle
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The start/stop rule of the physio work, without any platform type, so a test can run it.
 *
 * This mirrors `start()` and `stop()` of `AIMIPhysioManagerMTR` on Android, with one difference:
 * Android hands its schedule to WorkManager, iOS has no such thing. Here the "schedule" is a gate
 * that [IosPhysioPipelineFeed] checks before it runs the pipeline. While the gate is closed, no run
 * starts.
 *
 * It owns no timer and no loop on purpose. The feed already runs on the loop tick. A second loop
 * would feed the rolling baseline twice from the same readings, which the feed's own KDoc warns
 * about.
 *
 * @param isEnabled the person's physio assistant switch.
 * @param restoreLastUpdate reads the stored time of the last run back into memory.
 * @param log gets one line per decision.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PhysioRunGate(
    private val isEnabled: () -> Boolean,
    private val restoreLastUpdate: () -> Long,
    private val log: (String) -> Unit,
) {

    private val open = AtomicBoolean(false)

    /** True while physio runs are allowed: started, and not stopped since. */
    val isOpen: Boolean get() = open.load()

    /**
     * Opens the gate when the assistant is switched on, and closes it when it is off.
     * Calling it again while open changes nothing and does not restore the time again.
     */
    fun start() {
        if (!isEnabled()) {
            open.store(false)
            log("start ignored, the physio assistant is switched off")
            return
        }
        if (!open.compareAndSet(false, true)) return
        val lastUpdateMs = restoreLastUpdate()
        log("started, last run restored: ${if (lastUpdateMs == 0L) "never" else lastUpdateMs.toString()}")
    }

    /** Closes the gate. The next run is not started. A run already in flight finishes. */
    fun stop() {
        open.store(false)
        log("stopped")
    }
}

/**
 * The iOS `AimiPhysioLifecycle`.
 *
 * `start()` and `stop()` open and close the gate that [IosPhysioPipelineFeed] reads before every
 * run. See [PhysioRunGate] for the rule. The enabled switch is the same one Android reads,
 * `PhysioPipeline.isPhysioAssistantEnabled`.
 *
 * ### What Android does that iOS does not
 *
 * - The 6 hourly watchdog (`AIMIPhysioPipelineWatchdogMTR`). It checks Health Connect permissions
 *   and the Android vitals database. Neither exists on iOS, so there is nothing to recover.
 * - The extra bootstrap job when the data is stale. On iOS the feed's first tick already runs a
 *   one day window, because the feed counts that window as never run.
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosPhysioLifecycle @Inject constructor(
    private val pipeline: PhysioPipeline,
    private val aapsLogger: AAPSLogger,
) : AimiPhysioLifecycle {

    private val gate = PhysioRunGate(
        isEnabled = { pipeline.isPhysioAssistantEnabled() },
        restoreLastUpdate = { pipeline.restoreLastUpdateTime() },
        log = { aapsLogger.info(LTag.APS, "[$TAG] $it") },
    )

    /** True while the feed may start a run. */
    internal val isRunning: Boolean get() = gate.isOpen

    override fun start() = gate.start()

    override fun stop() = gate.stop()

    private companion object {

        const val TAG = "PhysioLifecycleIos"
    }
}
