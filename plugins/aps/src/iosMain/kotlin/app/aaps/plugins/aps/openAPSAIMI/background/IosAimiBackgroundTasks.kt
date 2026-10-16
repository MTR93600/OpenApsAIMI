package app.aaps.plugins.aps.openAPSAIMI.background

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
import app.aaps.plugins.aps.openAPSAIMI.learning.IosMlTrainingScheduler
import app.aaps.plugins.aps.openAPSAIMI.retention.IosRetentionScheduler
import app.aaps.plugins.aps.openAPSAIMI.utils.iosTickAimiRoot

/**
 * The one place that starts the AIMI background jobs on iOS: ML training and telemetry retention.
 *
 * Both schedulers existed for a while and nothing called them, so on iOS the training and the
 * archiving never ran. This object is what calls them.
 *
 * ## Why there are two steps, and why they are far apart
 *
 * iOS has a hard rule: `BGTaskScheduler.registerForTaskWithIdentifier` must run before the app
 * finishes launching. If it runs later, iOS raises an exception and the app crashes. But the DI
 * graph, which holds the training coordinator, is built much later, when the first screen is made.
 * So the work is split:
 *
 * 1. [register] needs no graph. It is called from the Swift `App.init`, which is early enough.
 * 2. [install] needs the graph. It is called after the graph is built. It gives the schedulers
 *    what they need and asks iOS to run them.
 *
 * Do not merge these into one call "to make it simpler". Moving [register] to where [install]
 * is called compiles and works in a quick test, then crashes on a real phone.
 *
 * ## When a task fires before [install] ran
 *
 * iOS can start the app in the background just to run a task. The first screen may never be built
 * then, so [install] may not have run. The ML handler then reports failure (`NOT_CONFIGURED`),
 * which is honest. The retention handler does not need the graph, so it still does its job with
 * the default directory.
 */
object IosAimiBackgroundTasks {

    /**
     * Registers both task handlers. Needs no graph. Must run before the app finishes launching.
     */
    fun register() {
        IosMlTrainingScheduler.register()
        IosRetentionScheduler.register()
    }

    /**
     * Gives the schedulers what they need and enqueues the first run of each.
     *
     * A failure in one job is logged and does not stop the other, and never reaches the caller.
     * Returns true only when both jobs were configured and enqueued.
     */
    fun install(coordinator: BasalMlTrainingCoordinator, logger: AAPSLogger): Boolean =
        installWith(
            coordinator = { coordinator },
            logger = logger,
            configureMl = IosMlTrainingScheduler::configure,
            scheduleMl = IosMlTrainingScheduler::schedule,
            configureRetention = { sink -> IosRetentionScheduler.configure(iosTickAimiRoot(), sink) },
            scheduleRetention = { IosRetentionScheduler.schedule() },
        )

    /**
     * The same as [install], with every outside call passed in, so a test can watch the order.
     * [coordinator] is a function so that a failure to get it is caught here, like the others.
     */
    internal fun installWith(
        coordinator: () -> BasalMlTrainingCoordinator,
        logger: AAPSLogger,
        configureMl: (BasalMlTrainingCoordinator) -> Unit,
        scheduleMl: () -> Boolean,
        configureRetention: ((String) -> Unit) -> Unit,
        scheduleRetention: () -> Boolean,
    ): Boolean {
        val mlOk = guarded(logger, "ML training") {
            configureMl(coordinator())
            scheduleMl()
        }
        val retentionOk = guarded(logger, "retention") {
            configureRetention { line -> logger.debug(LTag.APS, line) }
            scheduleRetention()
        }
        return mlOk && retentionOk
    }

    private fun guarded(logger: AAPSLogger, name: String, block: () -> Boolean): Boolean =
        try {
            val queued = block()
            if (!queued) logger.error(LTag.APS, "AIMI $name background task was not queued")
            queued
        } catch (e: Exception) {
            logger.error(LTag.APS, "AIMI $name background task failed to start", e)
            false
        }
}
