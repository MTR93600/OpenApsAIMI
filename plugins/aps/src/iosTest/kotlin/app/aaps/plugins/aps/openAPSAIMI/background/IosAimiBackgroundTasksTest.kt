package app.aaps.plugins.aps.openAPSAIMI.background

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.learning.IosMlTrainingScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AIMI background jobs on iOS were complete and never started, because nothing called them.
 * These tests pin the part of the wiring that can be run without iOS: the order of the calls and
 * what a failure does. They cannot prove that the app calls [IosAimiBackgroundTasks.install] or
 * that the Swift `init` calls `register`; the BGTaskScheduler needs a real app for that.
 */
class IosAimiBackgroundTasksTest {

    private class CountingLogger : AAPSLogger by NoOpAapsLogger {

        var errors = 0
        override fun error(tag: LTag, message: String) { errors++ }
        override fun error(tag: LTag, message: String, throwable: Throwable) { errors++ }
    }

    @Test
    fun nothingIsConfiguredUntilInstallRuns() {
        // The state a background launch finds: ML must say "not configured", not "done".
        assertFalse(IosMlTrainingScheduler.isConfigured)
    }

    @Test
    fun retentionIsStillStartedWhenTheCoordinatorCannotBeBuilt() {
        val calls = mutableListOf<String>()
        val logger = CountingLogger()
        val ok = IosAimiBackgroundTasks.installWith(
            coordinator = { error("graph has no coordinator") },
            logger = logger,
            configureMl = { calls += "configureMl" },
            scheduleMl = { calls += "scheduleMl"; true },
            configureRetention = { calls += "configureRetention" },
            scheduleRetention = { calls += "scheduleRetention"; true },
        )
        assertFalse(ok)
        assertEquals(listOf("configureRetention", "scheduleRetention"), calls)
        assertEquals(1, logger.errors)
    }

    @Test
    fun aRetentionFailureDoesNotReachTheCaller() {
        val logger = CountingLogger()
        val ok = IosAimiBackgroundTasks.installWith(
            coordinator = { error("no coordinator") },
            logger = logger,
            configureMl = {},
            scheduleMl = { true },
            configureRetention = { error("no directory") },
            scheduleRetention = { true },
        )
        assertFalse(ok)
        assertEquals(2, logger.errors)
    }

    @Test
    fun aJobThatIsNotQueuedIsReportedAsAFailure() {
        val logger = CountingLogger()
        val ok = IosAimiBackgroundTasks.installWith(
            coordinator = { error("no coordinator") },
            logger = logger,
            configureMl = {},
            scheduleMl = { true },
            configureRetention = {},
            scheduleRetention = { false },
        )
        assertFalse(ok)
        assertTrue(logger.errors >= 1)
    }
}
