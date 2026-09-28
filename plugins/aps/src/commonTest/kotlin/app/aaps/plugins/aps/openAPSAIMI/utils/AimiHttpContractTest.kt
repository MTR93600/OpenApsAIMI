package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
}
