package app.aaps.implementation.scenes

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.WallClockTimer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * Ends a timed scene when its time is up, by running the shared [SceneExpiryRunner].
 *
 * iOS is a master phone, so this is reachable: `SceneExecutor.activate` runs here and asks for the
 * expiry. Without it the SMB toggle and the profile switch of a timed scene would stay applied.
 *
 * Two things end a scene on time, and either is enough:
 * - this timer, while the app is alive. It waits on the wall clock ([WallClockTimer]), so a
 *   wake-up after iOS suspended the app is noticed within one step;
 * - the loop check (`SceneExpiryRunner.endIfOverdue`, called by `PostCalculationRunner` on each new
 *   glucose value), which ends an overdue scene even after the app was killed and started again.
 *
 * Android uses WorkManager instead, which also survives process death. The loop check is what
 * covers that case here.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosSceneExpiryScheduler(
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val expiryRunner: () -> SceneExpiryRunner,
    scope: CoroutineScope
) : SceneExpiryScheduler {

    private val timer = WallClockTimer(scope, dateUtil::now)

    override fun schedule(sceneName: String, delayMs: Long) {
        aapsLogger.debug(LTag.UI, "Scene '$sceneName' expires in ${delayMs}ms")
        timer.schedule(dateUtil.now() + delayMs) {
            val outcome = expiryRunner().run(sceneName)
            aapsLogger.debug(LTag.UI, "Scene '$sceneName' expiry finished: $outcome")
        }
    }

    override fun cancel() = timer.cancel()
}
