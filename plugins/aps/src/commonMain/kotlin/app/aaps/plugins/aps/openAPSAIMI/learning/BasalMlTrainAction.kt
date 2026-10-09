package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * What the platform worker should do after a basal ML training run.
 *
 * The mapping from [BasalMlTrainingCoordinator.TrainingOutcome] is platform-independent;
 * the platform worker (Android WorkManager, iOS BGTask) translates the action into its
 * own result type.
 */
enum class BasalMlTrainAction {
    /** Training finished (or had nothing to do): the work is done. */
    SUCCESS,

    /** Training failed transiently: the platform should retry later. */
    RETRY,
}

/** Maps a training outcome to the worker action. Pure, no platform API. */
fun mapTrainingOutcome(outcome: BasalMlTrainingCoordinator.TrainingOutcome): BasalMlTrainAction =
    when (outcome) {
        BasalMlTrainingCoordinator.TrainingOutcome.SUCCESS,
        BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED,
        -> BasalMlTrainAction.SUCCESS

        BasalMlTrainingCoordinator.TrainingOutcome.FAILED_RETRY -> BasalMlTrainAction.RETRY
    }
