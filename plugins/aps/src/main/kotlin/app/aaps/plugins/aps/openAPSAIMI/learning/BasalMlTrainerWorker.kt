package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.energy.EnergyMeter
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers

/**
 * Periodic worker (6h, idle + charging) for basal / T3C neural weight training.
 */
@HiltWorker
class BasalMlTrainerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    // Injected (not @Assisted): forces Dagger to instantiate the @Singleton coordinator, so training
    // actually runs. The previous static-`instance` lookup was never populated (nobody injected the
    // coordinator) → the worker retried forever and no model was ever trained.
    private val coordinator: BasalMlTrainingCoordinator,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        EnergyMeter.attach(applicationContext)
        return EnergyMeter.measureSuspend(EnergyMeter.WORKER_BASAL_ML) { doWorkMeasured() }
    }

    private suspend fun doWorkMeasured(): Result {
        aapsLogger.debug(LTag.APS, "BasalMlTrainerWorker: starting coordinated training")
        return runBasalMlTrainingJob(coordinator)
    }
}
