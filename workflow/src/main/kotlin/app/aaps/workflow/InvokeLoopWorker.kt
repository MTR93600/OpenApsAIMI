package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class InvokeLoopWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop

    class InvokeLoopData(
        val cause: Event?
    )

    /*
     Triggered after autosens calculation. We run the loop for EventNewBG and EventNewHistoryData,
     but dedupe by [Loop.lastBgTriggeredRun] for both: repeated history syncs must not re-invoke the
     loop for the same actualBg timestamp (otherwise pumps like Combo get duplicate temp basals / buzz).
    */
    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as InvokeLoopData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        if (data.cause !is EventNewBG && data.cause !is EventNewHistoryData) return Result.success(workDataOf("Result" to "no calculation needed"))
        val glucoseValue = iobCobCalculator.ads.actualBg() ?: return Result.success(workDataOf("Result" to "bg outdated"))

        if (glucoseValue.timestamp <= loop.lastBgTriggeredRun) {
            return Result.success(workDataOf("Result" to "already looped with that bg timestamp"))
        }
        loop.lastBgTriggeredRun = glucoseValue.timestamp

        val causeName = data.cause?.javaClass?.simpleName ?: "Unknown"
        loop.invoke("Calculation for $glucoseValue (cause=$causeName)", true)
        return Result.success()
    }
}