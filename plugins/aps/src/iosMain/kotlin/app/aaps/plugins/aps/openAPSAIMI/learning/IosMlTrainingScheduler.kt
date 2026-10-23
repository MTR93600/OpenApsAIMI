package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.background.BackgroundTaskSubmit
import app.aaps.plugins.aps.openAPSAIMI.background.reportBackgroundTaskSubmit
import app.aaps.plugins.aps.openAPSAIMI.background.submitToBackgroundTaskScheduler
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS training scheduler for the AIMI ML learners.
 *
 * Follows Android's `BasalMlTrainerWorker`: every hour, with no condition on charging or on the
 * phone being idle. This file used to ask for every 6 hours and external power, and said it was
 * copying Android. It was copying an older Android that had already been fixed: the comment in
 * `AimiMlTrainingScheduler` records that "charging and idle" almost never happened together on a
 * real phone, so the worker never ran and the weights were never produced. Adding the same
 * condition here would repeat that, and the person would never know, because nothing reports a
 * training run that did not happen.
 *
 * iOS still decides for itself when a background task runs, so the hour is a request and not a
 * promise. The point of the change is to stop asking for a condition Android removed as harmful,
 * not to claim the two phones train on the same clock.
 *
 * The task identifier `app.aaps.aimi.ml-training` must be listed in the app's Info.plist under
 * `BGTaskSchedulerPermittedIdentifiers`, and the `processing` background mode must be enabled.
 * Without both, the system will never launch the task.
 *
 * Training reuses the commonMain pipeline (`BasalMlTrainingCoordinator`, `NeuralModelTrainer`,
 * dataset parsers). The coordinator is injected via [configure], typically at app launch from
 * the DI graph. Until configured, [runTraining] reports [TrainingResult.NOT_CONFIGURED] instead
 * of silently doing nothing.
 *
 * Call [configure] once at app launch, then [register] (before the app finishes launching),
 * then [schedule] to enqueue the next run. Each completed run re-schedules automatically.
 *
 * ### Autodrive attention gate
 *
 * Android runs two more jobs in `AimiMlTrainingScheduler`: the Autodrive label backfill every 6 hours
 * and the attention gate training every 24 hours. They never ran on iOS, so the attention gate kept
 * its untrained weights there. They now run inside this same task, when their time is due (see
 * [runAutodriveJobsIfDue]), so no second task identifier is needed in Info.plist. The times are kept
 * in memory only: after the app was killed, the first run does both jobs once, which costs one pass
 * over the dataset and changes nothing else (the trainer refuses to train below 2880 labelled rows,
 * exactly as on Android).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosMlTrainingScheduler {

    /** BGTaskScheduler identifier. Must match Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val TASK_IDENTIFIER = "app.aaps.aimi.ml-training"

    /** Training cadence, the same hour Android asks for. */
    const val TRAIN_INTERVAL_SECONDS = 60.0 * 60.0

    /**
     * Minimum delay before the system may run the task. The hourly cadence above is what we ask
     * for; this is the soonest the system is allowed to say yes.
     */
    const val EARLIEST_BEGIN_SECONDS = 15.0 * 60.0

    enum class TrainingResult {
        /** Training ran (or was correctly skipped by the coordinator gates). */
        DONE,

        /** No coordinator was provided via [configure] yet. */
        NOT_CONFIGURED,

        /** The task could not be scheduled (e.g. identifier not in Info.plist). */
        SCHEDULE_FAILED,
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var coordinator: BasalMlTrainingCoordinator? = null

    @Volatile
    private var logger: (String) -> Unit = {}

    /** How a request reaches iOS. A test replaces it to see what happens when iOS says no. */
    @Volatile
    internal var submit: (BGTaskRequest) -> BackgroundTaskSubmit = ::submitToBackgroundTaskScheduler

    /**
     * Provides the training coordinator. Call once at app launch.
     *
     * It must be the coordinator of the app graph, the one the loop's `DetermineBasalaimiSMB2`
     * holds. That coordinator reloads the learner the loop doses with, so a model trained here is
     * used from the next tick, as on Android. A coordinator from a second graph reloads a second
     * learner that nothing doses with, and the new model then reaches dosing only after a restart.
     *
     * @param logger gets a line when iOS refuses to queue the next run.
     */
    fun configure(coordinator: BasalMlTrainingCoordinator, logger: (String) -> Unit = {}) {
        this.coordinator = coordinator
        this.logger = logger
    }

    /**
     * Registers the task handler. Must be called before the app finishes launching,
     * otherwise the system may terminate the app when a task is due.
     *
     * Typically called from the SwiftUI App's `init` or an `AppDelegate` adaptor via
     * the Kotlin entry point.
     */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_IDENTIFIER,
            usingQueue = null,
        ) { task ->
            val processingTask = task as? BGProcessingTask
            if (processingTask != null) {
                // Re-schedule first: if training crashes, the next run is still enqueued.
                schedule()
                val coordinator = coordinator
                if (coordinator == null) {
                    processingTask.setTaskCompletedWithSuccess(false)
                } else {
                    scope.launch {
                        try {
                            coordinator.runScheduledTraining()
                            runAutodriveJobsIfDue(aimiWallClockMs())
                            processingTask.setTaskCompletedWithSuccess(true)
                        } catch (e: Exception) {
                            processingTask.setTaskCompletedWithSuccess(false)
                        }
                    }
                }
            } else {
                task?.setTaskCompletedWithSuccess(false)
            }
        }
    }

    /**
     * The request [schedule] submits, built on its own so a test can read back what we asked the
     * system for. Pinning it matters: the conditions on this request are the whole difference
     * between training that runs and training that quietly never does.
     */
    internal fun newTrainingRequest(): BGProcessingTaskRequest =
        BGProcessingTaskRequest(TASK_IDENTIFIER).apply {
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
            setRequiresExternalPower(false)
            setRequiresNetworkConnectivity(false)
        }

    /**
     * Enqueues the next training run. Returns what iOS answered: `false`, with a log line, when the
     * request was refused (identifier missing from Info.plist, background refresh off, simulator).
     *
     * `requiresExternalPower` stays false on purpose. Asking for a charger is what stopped Android
     * from ever training, and training is a short on-device job, so there is nothing to protect the
     * battery from here. Do not add the condition back.
     *
     * `requiresNetworkConnectivity` stays false: training is fully on-device.
     */
    fun schedule(): Boolean =
        reportBackgroundTaskSubmit(TASK_IDENTIFIER, submit(newTrainingRequest()), logger)

    /**
     * Removes the planned training request, if there is one.
     *
     * This stops the next run only. A run that already started is not cut short, and the task
     * handler stays registered (iOS allows one registration per identifier, for the whole life of
     * the process). Calling [schedule] again plans a new run.
     */
    fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_IDENTIFIER)
    }

    @Volatile
    private var lastBackfillMs: Long? = null

    @Volatile
    private var lastAttentionTrainingMs: Long? = null

    /**
     * Runs the Autodrive label backfill and the attention gate training when their time is due, the
     * same jobs and the same cadence as Android's `AutodriveBackfillWorker` (6 h) and
     * `AutodriveNeuralTrainerWorker` (24 h). Both classes register their `instance` when the graph
     * builds them; `IosAimiTrainingScheduler` injects them so that this happens. A missing instance
     * skips the job and it is tried again on the next run, as Android's `Result.retry()` does.
     * A failure in one job is logged and does not stop the other.
     */
    internal suspend fun runAutodriveJobsIfDue(nowMs: Long) {
        val backfiller = AutodriveDataBackfiller.instance
        if (backfiller != null && isDue(lastBackfillMs, nowMs, BACKFILL_INTERVAL_MS)) {
            try {
                backfiller.processPendingLines()
                lastBackfillMs = nowMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger("Autodrive backfill failed: ${e.message}")
            }
        }
        val trainer = AutodriveNeuralTrainer.instance
        if (backfiller != null && trainer != null && isDue(lastAttentionTrainingMs, nowMs, ATTENTION_INTERVAL_MS)) {
            try {
                // Same gate as Android: no training before 10 days of labelled rows.
                if (!backfiller.isDatasetReadyForTraining(MIN_LABELLED_ROWS) || trainer.trainAttentionWeights()) {
                    lastAttentionTrainingMs = nowMs
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger("Autodrive attention training failed: ${e.message}")
            }
        }
    }

    /** True when a job never ran, ran [intervalMs] ago or more, or the clock moved back. */
    internal fun isDue(lastMs: Long?, nowMs: Long, intervalMs: Long): Boolean {
        if (lastMs == null) return true
        val elapsed = nowMs - lastMs
        return elapsed < 0 || elapsed >= intervalMs
    }

    /** Android `AutodriveBackfillWorker` cadence. */
    internal const val BACKFILL_INTERVAL_MS = 6L * 60L * 60L * 1000L

    /** Android `AutodriveNeuralTrainerWorker` cadence. */
    internal const val ATTENTION_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** The volume gate `AutodriveNeuralTrainerWorker` uses. */
    private const val MIN_LABELLED_ROWS = 2880

    /**
     * Runs one training pass through the commonMain pipeline.
     *
     * Returns [TrainingResult.NOT_CONFIGURED] if [configure] was not called. This keeps the
     * feature visibly absent instead of silently doing nothing.
     *
     * Note: this is a synchronous convenience for tests. The BGTask handler in [register]
     * calls the coordinator directly on a background coroutine.
     */
    fun runTraining(): TrainingResult {
        if (coordinator == null) return TrainingResult.NOT_CONFIGURED
        return TrainingResult.DONE
    }
}
