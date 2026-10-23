package app.aaps.plugins.aps.openAPSAIMI.background

import app.aaps.plugins.aps.openAPSAIMI.learning.IosMlTrainingScheduler
import app.aaps.plugins.aps.openAPSAIMI.retention.IosRetentionScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two schedulers used to answer `true` for every request, also when iOS refused it. These
 * tests feed them a refusal and check that it comes back as `false` with a log line.
 */
class IosBackgroundTaskSubmitTest {

    private val realMlSubmit = IosMlTrainingScheduler.submit
    private val realRetentionSubmit = IosRetentionScheduler.submit

    @AfterTest
    fun restore() {
        IosMlTrainingScheduler.submit = realMlSubmit
        IosRetentionScheduler.submit = realRetentionSubmit
        IosRetentionScheduler.configure()
    }

    @Test
    fun `a refusal is false and logged with the reason`() {
        val lines = mutableListOf<String>()
        val accepted = reportBackgroundTaskSubmit("app.aaps.test", BackgroundTaskSubmit(false, "not permitted")) { lines += it }
        assertFalse(accepted)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("app.aaps.test"))
        assertTrue(lines.single().contains("not permitted"))
    }

    @Test
    fun `an accepted request is true and not logged`() {
        val lines = mutableListOf<String>()
        assertTrue(reportBackgroundTaskSubmit("app.aaps.test", BackgroundTaskSubmit(true, null)) { lines += it })
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the training scheduler reports what ios answered`() {
        val lines = mutableListOf<String>()
        IosMlTrainingScheduler.submit = { BackgroundTaskSubmit(false, "unavailable") }
        assertFalse(IosMlTrainingScheduler.schedule())

        IosMlTrainingScheduler.submit = { request ->
            lines += request.identifier
            BackgroundTaskSubmit(true, null)
        }
        assertTrue(IosMlTrainingScheduler.schedule())
        assertEquals(listOf(IosMlTrainingScheduler.TASK_IDENTIFIER), lines)
    }

    @Test
    fun `the retention scheduler reports what ios answered and logs a refusal`() {
        val lines = mutableListOf<String>()
        IosRetentionScheduler.configure(logger = { lines += it })
        IosRetentionScheduler.submit = { BackgroundTaskSubmit(false, "too many pending requests") }
        assertFalse(IosRetentionScheduler.schedule())
        assertTrue(lines.single().contains("too many pending requests"))

        IosRetentionScheduler.submit = { BackgroundTaskSubmit(true, null) }
        assertTrue(IosRetentionScheduler.schedule())
        assertEquals(1, lines.size)
    }
}
