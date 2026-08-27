package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.objects.workflow.MetroWorkerCreator
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.Dispatchers

/**
 * Periodic worker (6h, idle + charging) for basal / T3C neural weight training.
 */
class BasalMlTrainerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    // Injected (not @Assisted): forces Metro to instantiate the singleton coordinator, so training
    // actually runs. The previous static-`instance` lookup was never populated (nobody injected the
    // coordinator) → the worker retried forever and no model was ever trained.
    private val coordinator: BasalMlTrainingCoordinator,
) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.APS, "BasalMlTrainerWorker: starting coordinated training")
        return runBasalMlTrainingJob(coordinator)
    }

    /**
     * Metro builds the worker through this. WorkManager supplies the context and parameters, the
     * graph supplies the rest - the same split `@HiltWorker` expressed, without Hilt.
     */
    @AssistedFactory
    fun interface Factory : MetroWorkerCreator {

        override fun create(context: Context, params: WorkerParameters): BasalMlTrainerWorker
    }
}
