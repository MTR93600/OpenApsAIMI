package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTrainingScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The iOS side of [AimiTrainingScheduler]: plans and cancels the training task in
 * [IosMlTrainingScheduler].
 *
 * This class adds no rule of its own. The hourly request and its conditions are in
 * [IosMlTrainingScheduler], and tests pin them there.
 *
 * Two things must be true for a planned task to really train, and this class cannot make them true:
 * the task identifier must be in the app's Info.plist, and `IosAimiBackgroundTasks.install` must
 * have given the scheduler its coordinator. If the system refuses the request, `schedule` logs a
 * warning here, so the failure is in the log and not silent.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiTrainingScheduler @Inject constructor(
    private val aapsLogger: AAPSLogger,
    // Injected so their `instance` companions exist before the training task looks them up, as in
    // Android's `AimiMlTrainingScheduler`. Without this the attention trainer is never built.
    @Suppress("unused") private val autodriveNeuralTrainer: AutodriveNeuralTrainer,
    @Suppress("unused") private val autodriveDataBackfiller: AutodriveDataBackfiller,
) : AimiTrainingScheduler {

    override fun schedule() {
        if (IosMlTrainingScheduler.schedule()) {
            aapsLogger.debug(LTag.APS, "AIMI training task planned")
        } else {
            aapsLogger.warn(LTag.APS, "AIMI training task was refused by the system (is the identifier in Info.plist?)")
        }
    }

    override fun cancel() {
        IosMlTrainingScheduler.cancel()
        aapsLogger.debug(LTag.APS, "AIMI training task cancelled")
    }
}
