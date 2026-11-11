package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The HTTP text of the local Garmin server, read and written as the Android `HttpServer` does. */
class GarminHttpTest {

    private fun reader(text: String): () -> Int {
        val bytes = text.encodeToByteArray()
        var i = 0
        return { if (i < bytes.size) bytes[i++].toInt() and 0xff else -1 }
    }

    @Test
    fun aGetGivesThePathAndTheDecodedQuery() {
        val request = GarminHttp.readRequest(reader("GET /get?key=abc&device=Fenix%207&wait=10 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"))

        assertEquals("/get", request.path)
        assertEquals("key=abc&device=Fenix 7&wait=10", request.query)
        assertNull(request.body)
    }

    @Test
    fun aGetWithNoQueryHasNone() {
        val request = GarminHttp.readRequest(reader("GET /sgv.json HTTP/1.1\r\n\r\n"))

        assertEquals("/sgv.json", request.path)
        assertNull(request.query)
    }

    @Test
    fun aFormPostCarriesTheQueryInTheBody() {
        val body = "carbs=20&key=abc"
        val request = GarminHttp.readRequest(
            reader("POST /carbs HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")
        )

        assertEquals("/carbs", request.path)
        assertEquals("carbs=20&key=abc", request.query)
        assertNull(request.body)
    }

    @Test
    fun aJsonPostKeepsItsBody() {
        val body = """{"hr":80}"""
        val request = GarminHttp.readRequest(
            reader("POST /get?key=abc HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
        )

        assertEquals("key=abc", request.query)
        assertEquals(body, request.body)
    }

    @Test
    fun somethingThatIsNotHttpIsRefused() {
        assertFailsWith<GarminHttpException> { GarminHttp.readRequest(reader("HELLO\r\n\r\n")) }
        assertFailsWith<GarminHttpException> { GarminHttp.readRequest(reader("")) }
    }

    @Test
    fun theAnswerHasTheLengthAndTheType() {
        val text = GarminHttp.response(200, """{"ok":true}""", "application/json").decodeToString()

        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}", text)
    }

    @Test
    fun anAnswerWithNoBodyHasNoLength() {
        assertEquals("HTTP/1.1 404 OK\r\n\r\n", GarminHttp.response(404, null, null).decodeToString())
    }

    @Test
    fun aPlusStaysAPlusAndUtf8IsDecoded() {
        assertEquals("a+b é", GarminHttp.percentDecode("a+b%20%C3%A9"))
    }
}
