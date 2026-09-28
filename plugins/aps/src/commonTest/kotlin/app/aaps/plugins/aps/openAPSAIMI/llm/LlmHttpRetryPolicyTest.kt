package app.aaps.plugins.aps.openAPSAIMI.llm

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retry policy, stated in numbers rather than in prose.
 *
 * This is the rule that decides how hard AIMI pushes an API that is already struggling, so the move
 * to shared code is only safe if the numbers came across untouched: which statuses are worth another
 * try, how many attempts in total, and how long the waits are. Every case below is written against
 * the behaviour the Android version had.
 *
 * The waits are recorded rather than spent, so the test proves the exact backoff without taking
 * four seconds to do it.
 */
class LlmHttpRetryPolicyTest {

    /** Keeps every wait it was asked for instead of sleeping it. */
    private class RecordedWaits(private val interrupted: Boolean = false) {

        val waits = mutableListOf<Long>()

        fun wait(millis: Long): Boolean {
            waits += millis
            return !interrupted
        }
    }

    /** One line that looks like what the executors throw for a server overload. */
    private fun overloaded() = Exception("Gemini Error (503): The model is overloaded")

    // ── Which statuses are worth another try ───────────────────────────────────────────────────

    @Test
    fun `only the five server side statuses count as transient`() {
        for (code in listOf(500, 502, 503, 504, 529)) {
            assertTrue(LlmHttpRetry.isTransientStatus(code), "$code should be transient")
        }
        for (code in listOf(200, 400, 401, 403, 404, 408, 429, 501, 505, 530)) {
            assertFalse(LlmHttpRetry.isTransientStatus(code), "$code should not be transient")
        }
    }

    @Test
    fun `a transient message is recognised in both shapes the executors emit`() {
        assertTrue(LlmHttpRetry.isTransient(Exception("Gemini Error (503): overloaded")))
        assertTrue(LlmHttpRetry.isTransient(Exception("HTTP 503: Service Unavailable")))
        assertTrue(LlmHttpRetry.isTransient(Exception("UNAVAILABLE")))
        assertTrue(LlmHttpRetry.isTransient(Exception("Read timed out")))
        assertTrue(LlmHttpRetry.isTransient(Exception("connect timeout")))
        assertTrue(LlmHttpRetry.isTransient(Exception("HTTP 529: Overloaded")))
    }

    @Test
    fun `a quota refusal is not transient and a message free error is neither`() {
        val quota = Exception("HTTP 429: RESOURCE_EXHAUSTED")
        assertTrue(LlmHttpRetry.isQuota(quota))
        assertFalse(LlmHttpRetry.isTransient(quota))

        assertTrue(LlmHttpRetry.isQuota(Exception("You exceeded your current quota")))

        val noMessage = Exception()
        assertFalse(LlmHttpRetry.isTransient(noMessage))
        assertFalse(LlmHttpRetry.isQuota(noMessage))
    }

    // ── How many attempts and how long between them ────────────────────────────────────────────

    @Test
    fun `a transient error is tried three times in all and waits 700 then 1400`() {
        val waits = RecordedWaits()
        var calls = 0

        assertFailsWith<Exception> {
            LlmHttpRetry.withTransientRetry(NoOpAapsLogger, wait = waits::wait) {
                calls++
                throw overloaded()
            }
        }

        assertEquals(3, calls)
        assertEquals(listOf(700L, 1400L), waits.waits)
    }

    @Test
    fun `the wait doubles for as long as the attempts are allowed to run`() {
        val waits = RecordedWaits()

        assertFailsWith<Exception> {
            LlmHttpRetry.withTransientRetry(NoOpAapsLogger, maxAttempts = 5, wait = waits::wait) { throw overloaded() }
        }

        assertEquals(listOf(700L, 1400L, 2800L, 5600L), waits.waits)
    }

    @Test
    fun `a call that works second time is not retried again`() {
        val waits = RecordedWaits()
        var calls = 0

        val answer = LlmHttpRetry.withTransientRetry(NoOpAapsLogger, wait = waits::wait) {
            calls++
            if (calls == 1) throw overloaded()
            "done"
        }

        assertEquals("done", answer)
        assertEquals(2, calls)
        assertEquals(listOf(700L), waits.waits)
    }

    @Test
    fun `a quota refusal is surfaced at once so the caller can switch model`() {
        val waits = RecordedWaits()
        var calls = 0

        val thrown = assertFailsWith<Exception> {
            LlmHttpRetry.withTransientRetry(NoOpAapsLogger, wait = waits::wait) {
                calls++
                throw Exception("HTTP 429: RESOURCE_EXHAUSTED")
            }
        }

        assertEquals(1, calls)
        assertEquals(emptyList(), waits.waits)
        assertEquals("HTTP 429: RESOURCE_EXHAUSTED", thrown.message)
    }

    @Test
    fun `a bad request is surfaced at once and never retried`() {
        val waits = RecordedWaits()
        var calls = 0

        assertFailsWith<Exception> {
            LlmHttpRetry.withTransientRetry(NoOpAapsLogger, wait = waits::wait) {
                calls++
                throw Exception("HTTP 401: Unauthorized")
            }
        }

        assertEquals(1, calls)
        assertEquals(emptyList(), waits.waits)
    }

    @Test
    fun `an interrupted wait stops the retries and throws the error that was being retried`() {
        val waits = RecordedWaits(interrupted = true)
        var calls = 0

        val thrown = assertFailsWith<Exception> {
            LlmHttpRetry.withTransientRetry(NoOpAapsLogger, wait = waits::wait) {
                calls++
                throw overloaded()
            }
        }

        assertEquals(1, calls)
        assertEquals(listOf(700L), waits.waits)
        assertEquals("Gemini Error (503): The model is overloaded", thrown.message)
    }
}
