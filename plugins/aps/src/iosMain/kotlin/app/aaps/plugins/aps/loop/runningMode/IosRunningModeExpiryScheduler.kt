package app.aaps.plugins.aps.loop.runningMode

import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.WallClockTimer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iOS twin of the Android `RunningModeExpiryScheduler`: runs [RunningModeExpiryJob] when a
 * temporary running mode reaches its end. The decision of *when* is shared
 * ([RunningModeExpiryDecision]); only the trigger is iOS.
 *
 * iOS has no WorkManager. The trigger is a [WallClockTimer] in the app scope, so a wake-up after
 * iOS suspended the app is noticed within one step. A master phone is woken every few minutes by
 * the sensor and the pump, so in practice the job runs soon after the end.
 *
 * What this cannot do: run while the app is not running at all. Android's WorkManager survives
 * process death. Here, when the mode ended while the app was killed, nothing is scheduled at the
 * next start; the start-up check of `RunningModeReconciler` is what compares the pump with the
 * mode then.
 */
@SingleIn(AppScope::class)
@Inject
class IosRunningModeExpiryScheduler(
    private val persistenceLayer: PersistenceLayer,
    private val job: RunningModeExpiryJob,
    private val config: Config,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val appScope: CoroutineScope
) {

    private var started = false
    private val mutex = Mutex()
    private val timer = WallClockTimer(appScope, dateUtil::now)

    fun start() {
        if (started) return
        started = true
        if (!config.APS) {
            aapsLogger.debug(LTag.APS, "IosRunningModeExpiryScheduler: config.APS=false, not scheduling")
            return
        }
        appScope.launch {
            rescheduleFromCurrentMode()
            persistenceLayer.observeChanges(RM::class).collect { rescheduleFromCurrentMode() }
        }
    }

    internal suspend fun rescheduleFromCurrentMode() = mutex.withLock {
        val now = dateUtil.now()
        val active = persistenceLayer.getRunningModeActiveAt(now)
        timer.cancel()
        val delayMs = RunningModeExpiryDecision.delayUntilExpiry(active, now)
        if (delayMs == null) {
            aapsLogger.debug(LTag.APS, "IosRunningModeExpiryScheduler: nothing to schedule for ${active.mode}")
            return@withLock
        }
        aapsLogger.debug(LTag.APS, "IosRunningModeExpiryScheduler: expiry of ${active.mode} in ${delayMs}ms")
        timer.schedule(now + delayMs) { job.run() }
    }
}
