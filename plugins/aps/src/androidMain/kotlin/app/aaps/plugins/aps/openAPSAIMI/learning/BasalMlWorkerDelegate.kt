package app.aaps.plugins.aps.openAPSAIMI.learning

import androidx.work.ListenableWorker.Result
import app.aaps.core.objects.workflow.LoggingWorker

internal suspend fun LoggingWorker.runBasalMlTrainingJob(coordinator: BasalMlTrainingCoordinator): Result {
    return when (mapTrainingOutcome(coordinator.runScheduledTraining())) {
        BasalMlTrainAction.SUCCESS -> Result.success()
        BasalMlTrainAction.RETRY -> Result.retry()
    }
}
