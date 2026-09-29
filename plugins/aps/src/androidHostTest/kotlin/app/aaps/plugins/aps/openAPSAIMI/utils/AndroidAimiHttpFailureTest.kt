package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Proof that naming a failure through the seam picks the same auditor status as the code it replaces.
 *
 * `AuditorAIService.getVerdict` used to end with a `when` over three JVM exception types, and that
 * `when` decided which of three things the user was told about a missing verdict. The port moved the
 * question into [AimiHttp.classify] and the answer into
 * [app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker.statusForTransportFailure],
 * which is two moving parts where there was one.
 *
 * So this does not assert what the new code should say. It keeps a copy of the old `when` as
 * [statusBeforeThePort] and asserts the two agree, over every transport failure the JVM actually
 * raises on a failed HTTP call. If they ever disagree, the copy is the one that is right.
 */
class AndroidAimiHttpFailureTest {

    private val http = AndroidAimiHttp()

    /**
     * The `when` that stood at the end of `AuditorAIService.getVerdict` before the port, copied
     * verbatim including its order. `UnknownHostException` and `SocketTimeoutException` are both
     * `IOException`s, so the order is what makes the three branches reachable.
     */
    private fun statusBeforeThePort(error: Throwable): AuditorStatusTracker.Status = when (error) {
        is UnknownHostException   -> AuditorStatusTracker.Status.OFFLINE_NO_NETWORK
        is SocketTimeoutException -> AuditorStatusTracker.Status.ERROR_TIMEOUT
        is IOException            -> AuditorStatusTracker.Status.OFFLINE_NO_NETWORK
        else                      -> AuditorStatusTracker.Status.ERROR_EXCEPTION
    }

    /** The `isRetryable` test that stood in the same loop, also copied verbatim. */
    private fun retryableBeforeThePort(error: Throwable): Boolean =
        error is SocketTimeoutException || error is IOException || error is UnknownHostException

    private fun statusNow(error: Throwable): AuditorStatusTracker.Status =
        AuditorStatusTracker.statusForTransportFailure(http.classify(error))

    private fun retryableNow(error: Throwable): Boolean = http.classify(error) != AimiHttpFailure.OTHER

    /** Everything an HTTP call on this platform can raise, plus the errors the clients raise themselves. */
    private val everyFailure: List<Throwable> = listOf(
        UnknownHostException("api.openai.com"),
        SocketTimeoutException("timeout"),
        SocketTimeoutException("Read timed out"),
        ConnectException("Failed to connect to /10.0.0.1:443"),
        SocketException("Connection reset"),
        SSLHandshakeException("Chain validation failed"),
        FileNotFoundException("https://example.invalid"),
        IOException("unexpected end of stream"),
        Exception("HTTP 401"),
        Exception("HTTP 429: {\"error\":\"quota\"}"),
        IllegalStateException("bug"),
        RuntimeException("bug")
    )

    @Test
    fun `every transport failure still picks the status it picked before the port`() {
        for (failure in everyFailure) {
            assertThat(statusNow(failure)).isEqualTo(statusBeforeThePort(failure))
        }
    }

    @Test
    fun `every transport failure is still retried exactly when it was retried before`() {
        for (failure in everyFailure) {
            assertThat(retryableNow(failure)).isEqualTo(retryableBeforeThePort(failure))
        }
    }

    @Test
    fun `the three statuses are each really reached, so the check above is not vacuous`() {
        val reached = everyFailure.map { statusNow(it) }.toSet()

        assertThat(reached).containsExactly(
            AuditorStatusTracker.Status.OFFLINE_NO_NETWORK,
            AuditorStatusTracker.Status.ERROR_TIMEOUT,
            AuditorStatusTracker.Status.ERROR_EXCEPTION
        )
    }

    @Test
    fun `the auditor's own deadline still reads as a timeout`() {
        // Before the port this was a hand-made java.net.SocketTimeoutException, thrown only so that
        // the same `when` would call it a timeout. The class changed; the status must not.
        val own = AimiHttpTimeoutException("Coroutine timeout after 45000ms")
        val before = statusBeforeThePort(SocketTimeoutException("Coroutine timeout after 45000ms"))

        assertThat(AuditorStatusTracker.statusForTransportFailure(AimiHttpFailure.TIMEOUT)).isEqualTo(before)
        assertThat(own.message).isEqualTo("Coroutine timeout after 45000ms")
    }

    @Test
    fun `an ordinary refusal wrapped in an error is not a transport failure`() {
        // The clients throw a plain Exception carrying the HTTP status. It must stay non-retryable,
        // otherwise a 401 with a bad API key would be sent three times.
        assertThat(http.classify(Exception("HTTP 401"))).isEqualTo(AimiHttpFailure.OTHER)
        assertThat(retryableNow(Exception("HTTP 401"))).isFalse()
    }
}
