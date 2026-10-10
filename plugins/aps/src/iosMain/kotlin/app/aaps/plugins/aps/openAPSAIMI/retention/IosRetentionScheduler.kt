package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.iosTickAimiRoot
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS scheduler for the telemetry retention janitor.
 *
 * Mirrors Android's `AimiRetentionWorker` (WorkManager, one pass a day) using
 * BGTaskScheduler with a `BGProcessingTask`. Unlike the ML training scheduler, this task
 * does not require external power: it is I/O-bound and short, and Android runs it as a
 * plain daily worker too.
 *
 * The task identifier `app.aaps.aimi.retention` must be listed in the app's Info.plist under
 * `BGTaskSchedulerPermittedIdentifiers`, and the `processing` background mode must be enabled.
 * Without both, the system will never launch the task.
 *
 * Call [configure] once at app launch (or rely on the defaults), then [register] (before the
 * app finishes launching), then [schedule] to enqueue the first run. Each completed run
 * re-schedules the next one a day out, so a crash never loses the cadence.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosRetentionScheduler {

    /** BGTaskScheduler identifier. Must match Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val TASK_IDENTIFIER = "app.aaps.aimi.retention"

    /** One pass a day, mirroring Android's `AimiRetentionWorker`. */
    const val RETENTION_INTERVAL_SECONDS = 24.0 * 60.0 * 60.0

    /** Delay before the first run after install or [schedule]. */
    const val EARLIEST_BEGIN_SECONDS = 15.0 * 60.0

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var aimiDir: String? = null

    @Volatile
    private var logger: (String) -> Unit = {}

    /**
     * Points the scheduler at the telemetry directory. Optional: when never called, the
     * iOS tick root is used.
     */
    fun configure(aimiDir: String = iosTickAimiRoot(), logger: (String) -> Unit = {}) {
        this.aimiDir = aimiDir
        this.logger = logger
    }

    /**
     * Registers the task handler. Must be called before the app finishes launching,
     * otherwise the system may terminate the app when a task is due.
     *
     * Typically called from the SwiftUI App's `init` or an `AppDelegate` adaptor via
     * the Kotlin entry point, next to `IosMlTrainingScheduler.register()`.
     */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_IDENTIFIER,
            usingQueue = null,
        ) { task ->
            val processingTask = task as? BGProcessingTask
            // Re-schedule first: if this run crashes, the next one is still enqueued.
            schedule(RETENTION_INTERVAL_SECONDS)
            if (processingTask != null) {
                scope.launch {
                    try {
                        val changed = runOnce()
                        logger("retention: pass finished, $changed file(s) changed")
                        processingTask.setTaskCompletedWithSuccess(true)
                    } catch (e: Exception) {
                        processingTask.setTaskCompletedWithSuccess(false)
                    }
                }
            } else {
                task?.setTaskCompletedWithSuccess(false)
            }
        }
    }

    /**
     * Enqueues the next janitor run [delaySeconds] from now.
     *
     * No external power or network required: the pass is local I/O only.
     */
    fun schedule(delaySeconds: Double = EARLIEST_BEGIN_SECONDS): Boolean {
        val request = BGProcessingTaskRequest(TASK_IDENTIFIER).apply {
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(delaySeconds))
            setRequiresExternalPower(false)
            setRequiresNetworkConnectivity(false)
        }
        return try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs one janitor pass now, on the calling thread. Returns how many files changed
     * (overflows drained, archives recovered, old months evicted).
     *
     * This is a synchronous convenience for tests and manual triggers; the BGTask handler
     * in [register] runs the same code on a background coroutine. Do not call on the main
     * thread: a first pass over a large overflow file can take minutes.
     */
    fun runOnce(): Int {
        val dir = aimiDir ?: iosTickAimiRoot()
        return IosRetentionJanitor(dir, logger).runOnce()
    }
}
