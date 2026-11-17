package app.aaps.plugins.sync.tidepool.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.sync.tidepool.messages.DatasetReplyMessage
import app.aaps.plugins.sync.tidepool.messages.OpenDatasetRequestMessage
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** The Ktor client with the engine of the platform: OkHttp on Android, Darwin (`NSURLSession`) on iOS. */
internal expect fun tidepoolHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * [TidepoolApi] over Ktor, the same on Android and iOS.
 *
 * @param baseUrl the production or the integration server
 * @param logBodies write request and answer bodies to the TIDEPOOL log. The token is never written.
 */
class KtorTidepoolApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val aapsLogger: AAPSLogger,
    private val logBodies: Boolean,
) : TidepoolApi {

    override suspend fun getDataSets(token: String, userId: String, clientName: String, deviceId: String?, size: Int): TidepoolResponse<List<DatasetReplyMessage>> =
        answer("GET data_sets", client.get {
            tidepool(token, "v1", "users", userId, "data_sets")
            parameter("client.name", clientName)
            deviceId?.let { parameter("deviceId", it) }
            parameter("size", size)
        }) { json -> (json as? JsonArray)?.mapNotNull { DatasetReplyMessage.from(it) } }

    override suspend fun openDataSet(token: String, userId: String, request: OpenDatasetRequestMessage): TidepoolResponse<DatasetReplyMessage> {
        val body = request.toJson().toString()
        logBody("POST data_sets", body)
        return answer("POST data_sets", client.post {
            tidepool(token, "v1", "users", userId, "data_sets")
            contentType(ContentType.Application.Json)
            setBody(body)
        }) { json -> DatasetReplyMessage.from(json) }
    }

    override suspend fun doUpload(token: String, uploadId: String, records: String): TidepoolResponse<Unit> {
        aapsLogger.debug(LTag.TIDEPOOL) { "Upload body size: ${records.length}" }
        logBody("POST data", records)
        return answer("POST data", client.post {
            tidepool(token, "v1", "datasets", uploadId, "data")
            contentType(ContentType.Application.Json)
            setBody(records)
        }) { }
    }

    override suspend fun deleteDataSet(token: String, uploadId: String): TidepoolResponse<Unit> =
        answer("DELETE dataset", client.delete {
            tidepool(token, "v1", "datasets", uploadId)
            // Tidepool answers a delete only after it has removed all records, which takes longer than the
            // default for a big dataset, and the delete does not finish when the client gives up - so purge
            // failed again and again on the same dataset. Other calls keep the short timeout, so a stuck
            // upload does not hold the upload lock for minutes.
            timeout { socketTimeoutMillis = DELETE_READ_TIMEOUT_MINUTES * 60_000L }
        }) { }

    private fun HttpRequestBuilder.tidepool(token: String, vararg path: String) {
        url {
            takeFrom(baseUrl)
            // Encoded segments, so an id can never change the path
            appendPathSegments(*path)
        }
        header(SESSION_TOKEN_HEADER, token)
    }

    /** The status of [response], and the body read by [read] when the call worked. */
    private suspend fun <T> answer(name: String, response: HttpResponse, read: (JsonElement) -> T?): TidepoolResponse<T> {
        val text = response.bodyAsText()
        aapsLogger.debug(LTag.TIDEPOOL, "$name: ${response.status.value} ${response.status.description}")
        logBody("$name answer", text)
        val body = if (response.status.value in 200..299) {
            if (text.isBlank()) null else runCatching { read(json.parseToJsonElement(text)) }.getOrNull()
        } else null
        return TidepoolResponse(response.status.value, response.status.description, body)
    }

    private fun logBody(name: String, body: String) {
        if (logBodies) aapsLogger.debug(LTag.TIDEPOOL) { "$name body: $body" }
    }

    companion object {

        internal const val DELETE_READ_TIMEOUT_MINUTES = 2

        // OkHttp's defaults, which the app used before
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 10_000L

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * The client settings every Tidepool call needs.
         *
         * The client headers say which app and version send each request, with the same values as the
         * dataset's `client.name` and `client.version`. Tidepool does not need them to work; they help to
         * find AAPS traffic in its logs.
         */
        fun HttpClientConfig<*>.tidepoolDefaults(applicationId: String, versionName: String) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = READ_TIMEOUT_MS
            }
            val version = versionName.asHeaderValue()
            defaultRequest {
                header(HttpHeaders.UserAgent, "AAPS/$version")
                header("X-Tidepool-Client-Name", applicationId.asHeaderValue())
                header("X-Tidepool-Client-Version", version)
            }
        }

        /** A Tidepool API for [baseUrl], with the engine of this platform. */
        fun create(baseUrl: String, applicationId: String, versionName: String, aapsLogger: AAPSLogger, logBodies: Boolean): KtorTidepoolApi =
            KtorTidepoolApi(tidepoolHttpClient { tidepoolDefaults(applicationId, versionName) }, baseUrl, aapsLogger, logBodies)

        // A header value with characters outside printable ASCII is refused, and a dev build's version
        // name could hold one. A refused header would fail every request, so such characters are dropped.
        internal fun String.asHeaderValue(): String = filter { it in ' '..'~' }
    }
}
