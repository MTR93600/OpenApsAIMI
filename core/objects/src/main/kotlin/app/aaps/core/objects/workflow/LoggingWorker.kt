package app.aaps.core.objects.workflow

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Base class for WorkManager workers. Subclasses are `@HiltWorker`s whose `@AssistedInject`
 * constructor supplies [aapsLogger] and [fabricPrivacy] (plus the worker's own dependencies) and
 * are instantiated by `HiltWorkerFactory`. [doWork] runs [doWorkAndLog] on [dispatcher] and logs
 * the result.
 */
abstract class LoggingWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val dispatcher: CoroutineDispatcher,
    val aapsLogger: AAPSLogger,
    val fabricPrivacy: FabricPrivacy
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result =
        withContext(dispatcher) {
            val startedAt = SystemClock.elapsedRealtime()
            doWorkAndLog().also {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val traceTag = tags.firstOrNull { it.startsWith("calc-trace:") } ?: "-"
                aapsLogger.debug(LTag.WORKER, "Worker result ${it::class.java.simpleName.uppercase()} for ${this@LoggingWorker::class.java} ${it.outputData}")
                aapsLogger.debug(LTag.WORKER, "Worker duration ${elapsedMs}ms for ${this@LoggingWorker::class.java.simpleName} trace=$traceTag")
            }
        }

    abstract suspend fun doWorkAndLog(): Result
}
