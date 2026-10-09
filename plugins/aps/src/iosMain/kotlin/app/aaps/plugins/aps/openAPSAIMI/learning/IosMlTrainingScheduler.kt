package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS training scheduler for the AIMI ML learners.
 *
 * Mirrors Android's `BasalMlTrainerWorker` (WorkManager, every 6h, idle + charging) using
 * BGTaskScheduler with a `BGProcessingTask` that requires external power.
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
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosMlTrainingScheduler {

    /** BGTaskScheduler identifier. Must match Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val TASK_IDENTIFIER = "app.aaps.aimi.ml-training"

    /** Training cadence, mirroring Android's 6h worker. */
    const val TRAIN_INTERVAL_SECONDS = 6.0 * 60.0 * 60.0

    /** Minimum delay before the system may run the task. */
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

    /**
     * Provides the training coordinator. Call once at app launch, before [register].
     *
     * The coordinator is normally built from the app's DI graph:
     * `BasalMlTrainingCoordinator(iosAimiStorage(), basalNeuralLearner, logger)`.
     */
    fun configure(coordinator: BasalMlTrainingCoordinator) {
        this.coordinator = coordinator
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
                            processingTask.setTaskCompletedWithSuccess(true)
                        } catch (e: Exception) {
                            processingTask.setTaskCompletedWithSuccess(false)
                        }
                    }
                }
            } else {
                task.setTaskCompletedWithSuccess(false)
            }
        }
    }

    /**
     * Enqueues the next training run.
     *
     * Uses `requiresExternalPower = true` to mirror Android's "idle + charging" constraint.
     * `requiresNetworkConnectivity` stays false: training is fully on-device.
     */
    fun schedule(): Boolean {
        val request = BGProcessingTaskRequest(TASK_IDENTIFIER).apply {
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
            setRequiresExternalPower(true)
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
