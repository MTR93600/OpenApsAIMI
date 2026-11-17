package app.aaps.plugins.sync.tidepool.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.sync.tidepool.comm.KtorTidepoolApi.Companion.tidepoolDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Tidepool HTTP calls, against a fake server. These rules were OkHttp interceptors before the move
 * to Ktor, and they must hold on Android and iOS alike: the client headers on every call, the long read
 * timeout for a delete only, and never the access token in the log.
 */
class KtorTidepoolApiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val logLines = mutableListOf<String>()

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        versionName: String = "4.0.0",
        logBodies: Boolean = false,
    ): KtorTidepoolApi {
        val engine = MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { tidepoolDefaults("info.nightscout.androidaps", versionName) }
        return KtorTidepoolApi(client, "https://api.tidepool.org", RecordingLogger(logLines), logBodies)
    }

    @Test
    fun every_call_carries_the_app_id_and_version() = runTest {
        api().doUpload("token-1", "upload-1", "[]")

        val headers = requests.single().headers
        assertEquals("AAPS/4.0.0", headers[HttpHeaders.UserAgent])
        assertEquals("info.nightscout.androidaps", headers["X-Tidepool-Client-Name"])
        assertEquals("4.0.0", headers["X-Tidepool-Client-Version"])
        assertEquals("token-1", headers[SESSION_TOKEN_HEADER])
    }

    @Test
    fun header_characters_outside_printable_ascii_are_dropped() = runTest {
        api(versionName = "4.0.0-dév\u0001").doUpload("token-1", "upload-1", "[]")

        assertEquals("4.0.0-dv", requests.single().headers["X-Tidepool-Client-Version"])
    }

    @Test
    fun only_a_delete_gets_the_long_read_timeout() = runTest {
        val api = api(body = "")
        api.deleteDataSet("token-1", "upload-1")
        api.doUpload("token-1", "upload-1", "[]")

        val delete = requests.first { it.method == HttpMethod.Delete }
        val upload = requests.first { it.method == HttpMethod.Post }
        assertEquals(KtorTidepoolApi.DELETE_READ_TIMEOUT_MINUTES * 60_000L, delete.timeout()?.socketTimeoutMillis)
        assertEquals(10_000L, upload.timeout()?.socketTimeoutMillis)
    }

    @Test
    fun data_sets_are_listed_with_the_query_tidepool_expects() = runTest {
        val reply = api(body = """[{"uploadId":"upload-2","id":"dataset-2"},{"data":{"uploadId":"upload-1"}}]""")
            .getDataSets("token-1", "user 1", "info.nightscout.androidaps", "AAPS", 1)

        val url = requests.single().url
        assertEquals("/v1/users/user%201/data_sets", url.encodedPath)
        assertEquals("info.nightscout.androidaps", url.parameters["client.name"])
        assertEquals("AAPS", url.parameters["deviceId"])
        assertEquals("1", url.parameters["size"])
        assertEquals(listOf("upload-2", "upload-1"), reply.body?.map { it.getUploadId() })
    }

    @Test
    fun no_device_id_leaves_it_out_of_the_query() = runTest {
        api(body = "[]").getDataSets("token-1", "user-1", "info.nightscout.androidaps", null, 100)

        assertFalse(requests.single().url.parameters.contains("deviceId"))
    }

    @Test
    fun an_error_has_no_body() = runTest {
        val reply = api(status = HttpStatusCode.InternalServerError, body = """{"uploadId":"x"}""")
            .getDataSets("token-1", "user-1", "app", null, 1)

        assertFalse(reply.isSuccessful)
        assertEquals(500, reply.code)
        assertNull(reply.body)
    }

    @Test
    fun an_empty_answer_has_no_body() = runTest {
        // A delete answers like this. The uploader accepts it for a delete only.
        val reply = api(body = "").deleteDataSet("token-1", "upload-1")

        assertTrue(reply.isSuccessful)
        assertNull(reply.body)
    }

    @Test
    fun the_token_is_never_logged_and_bodies_only_when_asked() = runTest {
        api(logBodies = false).doUpload("secret-token", "upload-1", """[{"type":"cbg"}]""")
        assertFalse(logLines.any { "secret-token" in it })
        assertFalse(logLines.any { "\"cbg\"" in it })

        api(logBodies = true).doUpload("secret-token", "upload-1", """[{"type":"cbg"}]""")
        assertFalse(logLines.any { "secret-token" in it })
        assertTrue(logLines.any { "\"cbg\"" in it })
    }

    private fun HttpRequestData.timeout(): HttpTimeoutConfig? = getCapabilityOrNull(HttpTimeoutCapability)

    private class RecordingLogger(private val lines: MutableList<String>) : AAPSLogger {

        override fun debug(message: String) { lines += message }
        override fun debug(enable: Boolean, tag: LTag, message: String) { lines += message }
        override fun debug(tag: LTag, message: String) { lines += message }
        override fun debug(tag: LTag, accessor: () -> String) { lines += accessor() }
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) { lines += format }
        override fun warn(tag: LTag, message: String) { lines += message }
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) { lines += format }
        override fun info(tag: LTag, message: String) { lines += message }
        override fun info(tag: LTag, format: String, vararg arguments: Any?) { lines += format }
        override fun error(tag: LTag, message: String) { lines += message }
        override fun error(tag: LTag, message: String, throwable: Throwable) { lines += message }
        override fun error(tag: LTag, format: String, vararg arguments: Any?) { lines += format }
        override fun error(message: String) { lines += message }
        override fun error(message: String, throwable: Throwable) { lines += message }
        override fun error(format: String, vararg arguments: Any?) { lines += format }
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) { lines += message }
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) { lines += message }
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) { lines += message }
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) { lines += message }
    }
}
