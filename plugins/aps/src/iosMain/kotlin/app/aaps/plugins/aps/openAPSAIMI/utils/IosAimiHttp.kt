package app.aaps.plugins.aps.openAPSAIMI.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut

/**
 * iOS half of [AimiHttp], on Ktor's Darwin engine.
 *
 * Darwin runs on `NSURLSession`, so an iOS build gets the system's own connection handling, proxy
 * settings and certificate validation rather than a second HTTP stack. `:core:nssdk` already picked
 * the same engine for the same reason.
 *
 * Ktor rather than raw `NSURLSession`, for two reasons that are both contract, not taste:
 *
 * - [AimiHttpRequest] carries a connect timeout **and** a read timeout, and says they must not be
 *   flattened into one. `NSURLSessionConfiguration` has no separate connect timeout, so raw
 *   `NSURLSession` could not keep them apart and the auditor would give up at the wrong moment.
 * - `HttpURLConnection` on Android refuses a redirect that changes protocol. These requests carry an
 *   API key in a header, so a silent `https` to `http` downgrade would leak it. Ktor's redirect
 *   handling refuses the downgrade too, and the setting is written out below rather than inherited.
 *
 * ### Blocking, on purpose
 *
 * [execute] blocks, because the interface says it must and every caller is already inside
 * `withContext(aapsIoDispatcher)`, which on iOS is a real background pool. The `runBlocking` below
 * is therefore parking a background thread, never the main one. It would deadlock on the main
 * thread, which is why no caller may move there - see the interface KDoc.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiHttp @Inject constructor() : AimiHttp {

    /**
     * Built once. A client owns a connection pool, so one per request would open a new pool each
     * time and throw away every kept-alive connection.
     */
    private val client: HttpClient by lazy {
        HttpClient(Darwin) {
            // A refused request is data, not an exception: the interface requires every status to
            // come back normally so each caller can word its own failure. This is Ktor's default,
            // written out because the whole contract rests on it.
            expectSuccess = false
            // Installed so a request can carry its own two timeouts below.
            install(HttpTimeout)
            followRedirects = true
        }
    }

    override fun execute(request: AimiHttpRequest): AimiHttpResponse = runBlocking {
        val response = client.request(request.url) {
            method = HttpMethod.parse(request.method)
            for ((name, value) in request.headers) header(name, value)
            request.body?.let { setBody(it) }
            timeout {
                connectTimeoutMillis = request.connectTimeoutMs.toLong()
                requestTimeoutMillis = request.readTimeoutMs.toLong()
                socketTimeoutMillis = request.readTimeoutMs.toLong()
            }
        }
        // Bytes decoded as UTF-8, not `bodyAsText()`. That helper decodes with the charset named in
        // the Content-Type header, while Android reads the stream as UTF-8 whatever the header
        // says. A server answering `charset=ISO-8859-1` would otherwise give the two phones two
        // different strings for the same body.
        val text = response.bodyAsBytes().decodeToString()
        val successful = response.status.value in 200..299
        AimiHttpResponse(
            code = response.status.value,
            // The standard reason phrase for this code, which is what `HttpURLConnection` hands
            // back on Android. NSURLSession does not expose the status line itself, so it cannot be
            // read off the wire; `localizedStringForStatusCode` is no substitute, because it is a
            // translated sentence rather than the phrase. Ktor carries the canonical phrase with
            // the code, so both phones print the same words.
            reason = response.status.description.takeIf { it.isNotEmpty() },
            // Android reads the error stream on a failure, and that stream is absent when the
            // refusal carried no body at all, which it reports as `null`. An empty body on a
            // successful call stays an empty string, because that is what Android returns.
            body = if (text.isEmpty() && !successful) null else text,
        )
    }

    /**
     * Names a Darwin transport failure, following the mapping the interface lays down.
     *
     * The cause chain is walked rather than only the top type, because Ktor wraps the platform's
     * `NSError` in its own exception. Testing only the outermost type would answer `OTHER` for every
     * real network failure.
     *
     * Anything this cannot place answers [AimiHttpFailure.OTHER]. That is the instruction, not a
     * shortcut: a wrong guess both retries a request that should not be retried and tells the user
     * the wrong reason their auditor verdict did not arrive.
     */
    override fun classify(error: Throwable): AimiHttpFailure {
        var current: Throwable? = error
        // Bounded, because a cause chain can be circular and this runs on a dosing path.
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is HttpRequestTimeoutException,
                is ConnectTimeoutException,
                is SocketTimeoutException -> return AimiHttpFailure.TIMEOUT

                is DarwinHttpRequestException -> when (current.origin.code) {
                    NSURLErrorTimedOut                -> return AimiHttpFailure.TIMEOUT
                    NSURLErrorNotConnectedToInternet,
                    NSURLErrorCannotFindHost,
                    NSURLErrorCannotConnectToHost,
                    NSURLErrorNetworkConnectionLost,
                    NSURLErrorDNSLookupFailed         -> return AimiHttpFailure.NO_NETWORK
                }
            }
            current = current.cause
            depth++
        }
        return AimiHttpFailure.OTHER
    }

    private companion object {

        const val MAX_CAUSE_DEPTH = 10
    }
}
