package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shape of the HTTP seam, checked on every target.
 *
 * The point of these is the rule the AIMI clients depend on: a server that refuses a request comes
 * back as an answer, carrying its status code and whatever it said, and not as a boolean, a `null`
 * or a thrown error. The four clients word their own failures out of those two pieces, so losing
 * either one would change the text a user reads when their meal photo or their coaching request is
 * turned down.
 */
class AimiHttpContractTest {

    /** Answers exactly what it was built with, so a test can state a server reply in one line. */
    private class FixedAimiHttp(private val response: AimiHttpResponse) : AimiHttp {

        var lastRequest: AimiHttpRequest? = null
            private set

        override fun execute(request: AimiHttpRequest): AimiHttpResponse {
            lastRequest = request
            return response
        }

        /** These tests are about answers, not failures, so there is nothing here to name. */
        override fun classify(error: Throwable): AimiHttpFailure = AimiHttpFailure.OTHER
    }

    /** Fails the way a platform fails: it raises, and it names what it raised. */
    private class FailingAimiHttp(
        private val error: Throwable,
        private val failure: AimiHttpFailure
    ) : AimiHttp {

        var classifyCalls = 0
            private set

        override fun execute(request: AimiHttpRequest): AimiHttpResponse = throw error

        override fun classify(error: Throwable): AimiHttpFailure {
            classifyCalls++
            return failure
        }
    }

    @Test
    fun `a refused request keeps its status code and its body`() {
        val errorBody = """{"error":{"message":"Rate limit reached","type":"tokens"}}"""
        val http = FixedAimiHttp(AimiHttpResponse(code = 429, reason = "Too Many Requests", body = errorBody))

        val response = http.execute(
            AimiHttpRequest(url = "https://example.invalid/v1", method = "POST", connectTimeoutMs = 1, readTimeoutMs = 2)
        )

        assertEquals(429, response.code)
        assertEquals(errorBody, response.body)
        assertEquals("Too Many Requests", response.reason)
        assertFalse(response.isSuccessful)
    }

    @Test
    fun `a refusal with no body at all still reports its status`() {
        val http = FixedAimiHttp(AimiHttpResponse(code = 502, reason = "Bad Gateway", body = null))

        val response = http.execute(
            AimiHttpRequest(url = "https://example.invalid/v1", method = "GET", connectTimeoutMs = 1, readTimeoutMs = 2)
        )

        assertEquals(502, response.code)
        assertNull(response.body)
        assertFalse(response.isSuccessful)
    }

    @Test
    fun `success counts every 2xx and nothing outside it`() {
        fun successAt(code: Int) = AimiHttpResponse(code = code, reason = null, body = "").isSuccessful

        assertFalse(successAt(199))
        assertTrue(successAt(200))
        assertTrue(successAt(204))
        assertTrue(successAt(299))
        assertFalse(successAt(300))
        assertFalse(successAt(404))
    }

    @Test
    fun `a missing reason phrase stays missing rather than becoming empty text`() {
        // One client prints the reason straight into its message, so a null has to stay a null: it
        // printed "null" before the port and has to keep printing it.
        val response = AimiHttpResponse(code = 500, reason = null, body = null)

        assertEquals("HTTP 500: null", "HTTP ${response.code}: ${response.reason}")
    }

    @Test
    fun `the request carries its own two timeouts`() {
        val http = FixedAimiHttp(AimiHttpResponse(code = 200, reason = "OK", body = "{}"))

        http.execute(
            AimiHttpRequest(
                url = "https://example.invalid/v1",
                method = "POST",
                connectTimeoutMs = 15_000,
                readTimeoutMs = 45_000,
                headers = mapOf("Content-Type" to "application/json"),
                body = "{\"a\":1}"
            )
        )

        val sent = http.lastRequest
        assertEquals(15_000, sent?.connectTimeoutMs)
        assertEquals(45_000, sent?.readTimeoutMs)
        assertEquals("POST", sent?.method)
        assertEquals(mapOf("Content-Type" to "application/json"), sent?.headers)
        assertEquals("{\"a\":1}", sent?.body)
    }

    @Test
    fun `a failure to reach the server still travels out of execute as itself`() {
        // Adding `classify` must not turn this into a seam type of its own. Four AIMI clients catch
        // the platform error and word their own message from it, and `LlmHttpRetry` reads its
        // `message` to decide whether to try again, so the error the platform raised has to arrive
        // at the caller unwrapped.
        val raised = IllegalStateException("connection reset")
        val http = FailingAimiHttp(raised, AimiHttpFailure.NO_NETWORK)

        val caught = try {
            http.execute(AimiHttpRequest(url = "https://example.invalid/v1", method = "POST", connectTimeoutMs = 1, readTimeoutMs = 2))
            null
        } catch (e: Throwable) {
            e
        }

        assertSame(raised, caught)
        assertEquals(0, http.classifyCalls, "execute must not classify on its own; the caller asks")
    }

    @Test
    fun `naming a failure is a separate question about the error that came out`() {
        val raised = IllegalStateException("connection reset")
        val http = FailingAimiHttp(raised, AimiHttpFailure.NO_NETWORK)

        assertEquals(AimiHttpFailure.NO_NETWORK, http.classify(raised))
        assertEquals(1, http.classifyCalls)
    }
}
