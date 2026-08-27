package app.aaps.plugins.aps.openAPSAIMI.learning

import androidx.work.ListenableWorker.Result
import app.aaps.core.objects.workflow.LoggingWorker

internal suspend fun LoggingWorker.runBasalMlTrainingJob(coordinator: BasalMlTrainingCoordinator): Result {
    return when (coordinator.runScheduledTraining()) {
        BasalMlTrainingCoordinator.TrainingOutcome.SUCCESS,
        BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED,
        -> Result.success()

        BasalMlTrainingCoordinator.TrainingOutcome.FAILED_RETRY -> Result.retry()
    }
}
