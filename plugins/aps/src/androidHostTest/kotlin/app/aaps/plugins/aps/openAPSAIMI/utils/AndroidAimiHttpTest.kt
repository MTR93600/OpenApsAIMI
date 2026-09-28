package app.aaps.plugins.aps.openAPSAIMI.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android half of the HTTP seam, against a real socket.
 *
 * A real server rather than a mock, because the things that could go wrong here are all on the wire:
 * whether the error body of a refused request is read at all, whether the request bytes leave as
 * they were written, and whether a non-2xx comes back as an answer instead of an exception.
 */
class AndroidAimiHttpTest {

    /**
     * A one-request HTTP server on a free port.
     *
     * It reads one request, remembers it, writes [statusLine] with [body], and closes. Enough for a
     * single call and nothing more.
     */
    private class OneShotServer(
        private val statusLine: String,
        private val body: String?
    ) : AutoCloseable {

        private val server = ServerSocket(0)
        private val started = CountDownLatch(1)
        val port: Int get() = server.localPort

        @Volatile var requestLine: String = ""
        @Volatile var headerLines: List<String> = emptyList()
        @Volatile var requestBody: String = ""

        private val thread = Thread {
            started.countDown()
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = ByteArrayOutputStream()
                    // Read up to the blank line that ends the headers.
                    while (!head.toString(Charsets.UTF_8.name()).endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next == -1) break
                        head.write(next)
                    }
                    val lines = head.toString(Charsets.UTF_8.name()).split("\r\n").filter { it.isNotEmpty() }
                    requestLine = lines.firstOrNull().orEmpty()
                    headerLines = lines.drop(1)
                    val length = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    if (length > 0) {
                        val payload = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(payload, read, length - read)
                            if (n == -1) break
                            read += n
                        }
                        requestBody = String(payload, 0, read, Charsets.UTF_8)
                    }

                    val bytes = body?.toByteArray(Charsets.UTF_8)
                    val out = StringBuilder()
                    out.append("HTTP/1.1 ").append(statusLine).append("\r\n")
                    out.append("Content-Length: ").append(bytes?.size ?: 0).append("\r\n")
                    out.append("Connection: close\r\n\r\n")
                    socket.getOutputStream().apply {
                        write(out.toString().toByteArray(Charsets.UTF_8))
                        if (bytes != null) write(bytes)
                        flush()
                    }
                }
            }
        }

        init {
            thread.isDaemon = true
            thread.start()
            started.await(5, TimeUnit.SECONDS)
        }

        fun awaitDone() = thread.join(5_000)

        override fun close() {
            runCatching { server.close() }
        }
    }

    private val http = AndroidAimiHttp()

    private fun request(port: Int, method: String, body: String? = null, headers: Map<String, String> = emptyMap()) =
        AimiHttpRequest(
            url = "http://127.0.0.1:$port/v1/chat",
            method = method,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            headers = headers,
            body = body
        )

    @Test
    fun `a refused request hands back its status code and its error body, and does not throw`() {
        val errorBody = """{"error":{"message":"Incorrect API key provided","code":"invalid_api_key"}}"""
        OneShotServer("401 Unauthorized", errorBody).use { server ->
            val response = http.execute(request(server.port, "POST", body = "{}"))

            assertThat(response.code).isEqualTo(401)
            assertThat(response.body).isEqualTo(errorBody)
            assertThat(response.reason).isEqualTo("Unauthorized")
            assertThat(response.isSuccessful).isFalse()
        }
    }

    @Test
    fun `a refusal carrying no body is reported by status, not turned into an IO failure`() {
        // The success stream is never used as a fallback here. Reaching for it would throw for any
        // status at or above 400 and would hide the 404 behind an unrelated error.
        OneShotServer("404 Not Found", null).use { server ->
            val response = http.execute(request(server.port, "POST", body = "{}"))

            assertThat(response.code).isEqualTo(404)
            assertThat(response.isSuccessful).isFalse()
            assertThat(response.body).isAnyOf(null, "")
        }
    }

    @Test
    fun `a successful request returns the body as sent`() {
        val ok = """{"choices":[{"message":{"content":"line one\nline two"}}]}"""
        OneShotServer("200 OK", ok).use { server ->
            val response = http.execute(request(server.port, "POST", body = "{}"))

            assertThat(response.code).isEqualTo(200)
            assertThat(response.isSuccessful).isTrue()
            assertThat(response.body).isEqualTo(ok)
        }
    }

    @Test
    fun `the method, the headers and the body bytes leave exactly as they were given`() {
        val payload = """{"model":"gpt-4o","messages":[{"role":"user","content":"café"}]}"""
        OneShotServer("200 OK", "{}").use { server ->
            http.execute(
                request(
                    port = server.port,
                    method = "POST",
                    body = payload,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "x-api-key" to "secret-key",
                        "anthropic-version" to "2023-06-01"
                    )
                )
            )
            server.awaitDone()

            assertThat(server.requestLine).startsWith("POST /v1/chat ")
            assertThat(server.headerLines).contains("Content-Type: application/json")
            assertThat(server.headerLines).contains("x-api-key: secret-key")
            assertThat(server.headerLines).contains("anthropic-version: 2023-06-01")
            assertThat(server.requestBody).isEqualTo(payload)
        }
    }

    @Test
    fun `a request without a body stays a plain GET`() {
        OneShotServer("200 OK", """{"data":[]}""").use { server ->
            val response = http.execute(
                request(server.port, "GET", headers = mapOf("Authorization" to "Bearer token"))
            )
            server.awaitDone()

            assertThat(response.code).isEqualTo(200)
            assertThat(server.requestLine).startsWith("GET /v1/chat ")
            assertThat(server.headerLines).contains("Authorization: Bearer token")
            assertThat(server.requestBody).isEmpty()
        }
    }
}
