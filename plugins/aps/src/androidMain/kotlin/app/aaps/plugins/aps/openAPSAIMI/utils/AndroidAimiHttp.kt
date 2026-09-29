package app.aaps.plugins.aps.openAPSAIMI.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Android half of [AimiHttp], on `HttpURLConnection`.
 *
 * `HttpURLConnection` rather than OkHttp, although one of the five AIMI clients used OkHttp
 * directly. On Android the platform `HttpURLConnection` is itself backed by OkHttp, so the two share
 * one engine: the same transparent gzip, the same connection pool and the same retry on a failed
 * connection. The one real difference is that `HttpURLConnection` refuses a redirect that changes
 * protocol while OkHttp follows it, and refusing to be redirected from `https` to `http` is the safer
 * of the two. Choosing it keeps this module off a library it never declared - OkHttp only ever
 * arrived here through another module's dependencies.
 *
 * Every connection is closed in a `finally`, which the callers this replaces did not all do.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiHttp @Inject constructor() : AimiHttp {

    override fun execute(request: AimiHttpRequest): AimiHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)

            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }

            val code = connection.responseCode
            // Never connection.inputStream as a fallback on a failure: it throws for any status at or
            // above 400, which would hide the status behind an unrelated I/O failure.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return AimiHttpResponse(
                code = code,
                reason = connection.responseMessage,
                body = stream?.let { readAll(it) }
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Names a JVM transport failure, in the exact order the auditor used to name it itself.
     *
     * The order is the whole point and must not be tidied up. `UnknownHostException` and
     * `SocketTimeoutException` are both `IOException`s, so a `when` that tested `IOException` first
     * would swallow the other two and every timeout would read as "no network". This is the same
     * chain, in the same order, that stood in `AuditorAIService.getVerdict` before the port.
     */
    override fun classify(error: Throwable): AimiHttpFailure = when (error) {
        is UnknownHostException   -> AimiHttpFailure.NO_NETWORK
        is SocketTimeoutException -> AimiHttpFailure.TIMEOUT
        is IOException            -> AimiHttpFailure.NO_NETWORK
        else                      -> AimiHttpFailure.OTHER
    }

    /**
     * Reads [stream] to its end as UTF-8.
     *
     * Chunked rather than line by line: a line reader would have to decide what to do with the line
     * endings, and the JSON bodies these APIs return are read verbatim.
     */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) text.append(buffer, 0, charsRead)
            text.toString()
        }
}
