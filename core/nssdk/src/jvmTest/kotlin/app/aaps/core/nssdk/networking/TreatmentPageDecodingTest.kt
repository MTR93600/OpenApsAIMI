package app.aaps.core.nssdk.networking

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A treatments page is decoded per document.
 *
 * Confirmed before the fix: one `"duration": 30.0` made `NsHttpResponse.body` return null, while
 * `eTagAsLong()` still returned the cursor. The history call then handed the runner an empty list
 * and that ETag, so the page was skipped.
 */
class TreatmentPageDecodingTest {

    private lateinit var server: MockWebServer
    private val warnings = mutableListOf<String>()
    private lateinit var client: NSAndroidClientImpl

    @BeforeEach
    fun setUp() {
        warnings.clear()
        server = MockWebServer()
        server.start()
        client = NSAndroidClientImpl(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = "token",
            logging = false,
            logger = { },
            dispatcher = Dispatchers.Unconfined,
            onDecodeWarning = { warnings += it }
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a page of durations written as decimals is loaded whole`() = runTest {
        enqueue(
            """{"result":[
              {"eventType":"Temp Basal","date":1785990980390,"duration":30.0,"absolute":1.0,"identifier":"a"},
              {"eventType":"Temp Basal","date":1785990981390,"duration":30.0,"absolute":1.1,"identifier":"b"},
              {"eventType":"Temp Basal","date":1785990982390,"duration":30.0,"absolute":1.2,"identifier":"c"}
            ]}"""
        )

        val response = client.getTreatmentsModifiedSince(1, 100)

        assertThat(response.values.map { it.identifier }).containsExactly("a", "b", "c").inOrder()
        assertThat(response.values.map { (it as NSTemporaryBasal).duration }).containsExactly(1_800_000L, 1_800_000L, 1_800_000L)
        assertThat(response.lastServerModified).isEqualTo(1785992481019L)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `one unreadable treatment is skipped and the etag still advances`() = runTest {
        enqueue(
            """{"result":[
              {"eventType":"Correction Bolus","date":1785992179555,"insulin":0.25,"identifier":"a"},
              {"eventType":"Correction Bolus","date":{"x":1},"insulin":0.1,"identifier":"bad"},
              {"eventType":"Temp Basal","date":1785990980390,"duration":30.0,"absolute":1.0,"identifier":"tbr"}
            ]}"""
        )

        val response = client.getTreatmentsModifiedSince(1, 100)

        assertThat(response.values.map { it.identifier }).containsExactly("a", "tbr").inOrder()
        assertThat((response.values[1] as NSTemporaryBasal).duration).isEqualTo(1_800_000L)
        assertThat(response.lastServerModified).isEqualTo(1785992481019L)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).startsWith("Treatments, documents skipped because they could not be read: 1. The first: bad:")
    }

    /** Empty values and the ETag: the cursor still moves, and one warning names the count. */
    @Test
    fun `a page of only unreadable treatments is empty and the etag still advances`() = runTest {
        enqueue(
            """{"result":[
              {"eventType":"Correction Bolus","date":{"x":1},"insulin":0.1,"identifier":"bad-a"},
              {"eventType":"Correction Bolus","date":"not-a-number","insulin":0.1,"identifier":"bad-b"}
            ]}"""
        )

        val response = client.getTreatmentsModifiedSince(1, 100)

        assertThat(response.values).isEmpty()
        assertThat(response.lastServerModified).isEqualTo(1785992481019L)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).startsWith("Treatments, documents skipped because they could not be read: 2. The first: bad-a:")
    }

    @Test
    fun `first load keeps the readable treatments and does not invent a cursor`() = runTest {
        enqueue(
            """{"result":[
              {"eventType":"Correction Bolus","date":1785992179555,"insulin":0.25,"identifier":"a"},
              {"eventType":"Correction Bolus","date":{"x":1},"insulin":0.1,"identifier":"bad"},
              {"eventType":"Correction Bolus","date":1785992279555,"insulin":0.1,"identifier":"c"}
            ]}"""
        )

        val response = client.getTreatmentsNewerThan("2026-01-01T00:00:00.000Z", 100)

        assertThat(response.values.map { it.identifier }).containsExactly("a", "c").inOrder()
        assertThat(response.lastServerModified).isEqualTo(0L)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).startsWith("Treatments, documents skipped because they could not be read: 1. The first: bad:")
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("ETag", """W/"1785992481019"""")
                .body(body).build()
        )
    }
}
