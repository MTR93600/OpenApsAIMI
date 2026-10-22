package app.aaps.implementation.receivers

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.awaitInitialized
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.dst.DstHelper
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.objects.workflow.MetroWorkerCreator
import app.aaps.implementation.maintenance.KeepAliveChecks
import app.aaps.implementation.maintenance.PeriodicMaintenance
import com.google.common.util.concurrent.ListenableFuture
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@AssistedInject
class KeepAliveWorker(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val localAlertUtils: LocalAlertUtils,
    private val persistenceLayer: PersistenceLayer,
    private val config: Config,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val rxBus: RxBus,
    private val commandQueue: CommandQueue,
    private val periodicMaintenance: PeriodicMaintenance,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dstHelper: DstHelper,
    private val workManager: WorkManager,
    private val ch: ConcentrationHelper
) : LoggingWorker(context, params, Dispatchers.Default, aapsLogger, fabricPrivacy) {

    @AssistedFactory
    fun interface Factory : MetroWorkerCreator {

        override fun create(context: Context, params: WorkerParameters): KeepAliveWorker
    }

    companion object {

        private var lastRun: Long = 0

        const val KA_0 = "KeepAlive"
        private const val KA_5 = "KeepAlive_5"
        private const val KA_10 = "KeepAlive_10"

        fun scheduleIfNotRunning(context: Context, aapsLogger: AAPSLogger, fabricPrivacy: FabricPrivacy) {
            if (lastRun != 0L && lastRun + T.mins(20).msecs() < System.currentTimeMillis()) {
                schedule(context)
                aapsLogger.error(LTag.CORE, "KeepAliveRescheduled")
                fabricPrivacy.logCustom("KeepAliveRescheduled")
            }
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                KA_0,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(KeepAliveWorker::class.java, 15, TimeUnit.MINUTES)
                    .setInputData(Data.Builder().putString("schedule", KA_0).build())
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .build()
            )

        }
    }

    private val keepAliveChecks = KeepAliveChecks(
        aapsLogger = aapsLogger,
        localAlertUtils = localAlertUtils,
        config = config,
        iobCobCalculator = iobCobCalculator,
        loop = loop,
        dateUtil = dateUtil,
        activePlugin = activePlugin,
        profileFunction = profileFunction,
        rxBus = rxBus,
        commandQueue = commandQueue,
        ch = ch,
        statusOutdatedLabel = { rh.gs(app.aaps.core.ui.R.string.keepalive_status_outdated) },
        basalOutdatedLabel = { rh.gs(app.aaps.core.ui.R.string.keepalive_basal_outdated) }
    )

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.CORE, "KeepAlive received from: " + inputData.getString("schedule") + " Thread count: " + Thread.activeCount())

        // 15 min interval is WorkManager minimum so schedule another instances to have 5 min interval
        if (inputData.getString("schedule") == KA_0) {
            workManager.enqueueUniqueWork(
                KA_5,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(KeepAliveWorker::class.java)
                    .setInputData(Data.Builder().putString("schedule", KA_5).build())
                    .setInitialDelay(5, TimeUnit.MINUTES)
                    .build()
            )
            workManager.enqueueUniqueWork(
                KA_10,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(KeepAliveWorker::class.java)
                    .setInputData(Data.Builder().putString("schedule", KA_10).build())
                    .setInitialDelay(10, TimeUnit.MINUTES)
                    .build()
            )
        } else {
            // Sometimes schedule +5min, +10min gets broken
            // If this happens do nothing
            // It's causing false Pump unreachable alerts
            if (lastRun + T.mins(4).msecs() > dateUtil.now()) return Result.success(workDataOf("Error" to "Schedule broken. Ignoring"))
        }

        // Gate the plugin-touching work behind app init — without this, a worker that fires
        // after a reboot before MainApp's init scope has populated pluginStore.plugins crashes.
        if (!config.awaitInitialized()) {
            aapsLogger.debug(LTag.CORE, "KeepAlive: app not yet initialized, retrying")
            return Result.retry()
        }

        if (lastRun != 0L && dateUtil.now() - lastRun > T.mins(10).msecs()) {
            aapsLogger.error(LTag.CORE, "KeepAlive fail")
            fabricPrivacy.logCustom("KeepAliveFail")
        }
        lastRun = dateUtil.now()

        dstHelper.dstCheck()
        // The platform-neutral half - missed-reading alarm, snooze shortening, log and database
        // trimming - lives in PeriodicMaintenance so iOS and desktop run the same code instead of
        // nothing. What stays here is what genuinely needs this worker: the pump and APS checks, and
        // the WorkManager telemetry below.
        periodicMaintenance.runOnce()
        // The pump and APS checks are shared with iOS: same code, only the trigger is WorkManager here.
        keepAliveChecks.run()
        workerDbStatus()
        workerActiveStatus()

        return Result.success()
    }


    // When Worker DB grows too much, work operations become slow
    // Library is cleaning DB every 7 days which may not be sufficient for NSClient full sync
    private fun workerDbStatus() {
        val terminal = getWorkInfosSafe(listOf(WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED)) ?: return
        aapsLogger.debug(LTag.CORE, "WorkManager size is ${terminal.size}")
        if (terminal.size > 1000) {
            workManager.pruneWork()
            aapsLogger.debug(LTag.CORE, "WorkManager pruning ....")
        }
    }

    // Report ENQUEUED/RUNNING/BLOCKED counts and the top worker classes by count, so a leaking
    // chain is identifiable. Useful for the lead-up to a freeze; once WorkManager itself stalls,
    // KeepAlive stops firing and this stops logging too.
    private fun workerActiveStatus() {
        val active = getWorkInfosSafe(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) ?: return
        val byState = active.groupingBy { it.state }.eachCount()
        aapsLogger.debug(
            LTag.CORE,
            "WorkManager active: total=${active.size}" +
                " enqueued=${byState[WorkInfo.State.ENQUEUED] ?: 0}" +
                " running=${byState[WorkInfo.State.RUNNING] ?: 0}" +
                " blocked=${byState[WorkInfo.State.BLOCKED] ?: 0}"
        )
        // WorkManager auto-adds the worker's fully-qualified class name as a tag; filter by '.'
        // to skip user-added tags, then keep the simple name for readability.
        val topTags = active.asSequence()
            .flatMap { it.tags.asSequence() }
            .filter { it.contains('.') }
            .groupingBy { it.substringAfterLast('.') }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString { "${it.key}=${it.value}" }
        if (topTags.isNotEmpty()) aapsLogger.debug(LTag.CORE, "WorkManager active top: $topTags")
    }

    // Bounded blocking get: if WorkManager itself is wedged (the very thing we're diagnosing),
    // an unbounded .get() would hang the KeepAliveWorker and take the diagnostic down with it.
    private fun getWorkInfosSafe(states: List<WorkInfo.State>): List<WorkInfo>? {
        val future: ListenableFuture<List<WorkInfo>> = workManager.getWorkInfos(WorkQuery.Builder.fromStates(states).build())
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            aapsLogger.error(LTag.CORE, "WorkManager getWorkInfos timeout for $states")
            future.cancel(true)
            null
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "WorkManager getWorkInfos failed for $states", e)
            null
        }
    }

    @VisibleForTesting
    suspend fun checkAPS() = keepAliveChecks.checkAPS()

    @VisibleForTesting
    suspend fun checkPump() = keepAliveChecks.checkPump()
}
