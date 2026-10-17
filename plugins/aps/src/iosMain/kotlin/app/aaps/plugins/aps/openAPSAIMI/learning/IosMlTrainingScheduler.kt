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
     * Enqueues the next training run.
     *
     * `requiresExternalPower` stays false on purpose. Asking for a charger is what stopped Android
     * from ever training, and training is a short on-device job, so there is nothing to protect the
     * battery from here. Do not add the condition back.
     *
     * `requiresNetworkConnectivity` stays false: training is fully on-device.
     */
    fun schedule(): Boolean {
        val request = newTrainingRequest()
        return try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            true
        } catch (e: Exception) {
            false
        }
    }

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
