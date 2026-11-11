package app.aaps.plugins.sync.garmin

/** A request from a watch app, as far as the answer needs it. */
data class GarminHttpRequest(
    /** The path, such as `/get`. */
    val path: String,
    /** The query, `%xx` decoded, or null when there is none. */
    val query: String?,
    /** A JSON body, or null. */
    val body: String?,
)

class GarminHttpException(message: String) : Exception(message)

/**
 * The HTTP text of the local Garmin server, without any socket in it, so the iPhone server reads and
 * writes exactly what the Android `HttpServer` does. The Android server keeps its own Java code; the
 * rules here follow `HttpServer.parseRequest` and `HttpServer.respond` line by line.
 */
object GarminHttp {

    private val REQUEST_LINE = Regex("(GET|POST) (\\S*) HTTP/1.1")
    private val HEADER_LINE = Regex("([A-Za-z-]+)\\s*:\\s*(.*)")

    /**
     * Reads one request. [read] gives the next byte, or -1 at the end of the input.
     *
     * Throws [GarminHttpException] on a request that is not HTTP/1.1 GET or POST, as Android does.
     */
    fun readRequest(read: () -> Int): GarminHttpRequest {
        val requestLine = readLine(read)
        val match = REQUEST_LINE.matchEntire(requestLine) ?: throw GarminHttpException("invalid HTTP header '$requestLine'")
        val post = match.groupValues[1] == "POST"
        val target = match.groupValues[2]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(read)
            if (line.isEmpty()) break
            val header = HEADER_LINE.matchEntire(line) ?: throw GarminHttpException("invalid header line '$line'")
            headers[header.groupValues[1]] = header.groupValues[2]
        }
        val path = percentDecode(target.substringBefore('?'))
        var query = if ('?' in target) percentDecode(target.substringAfter('?')) else null
        var body: String? = null
        if (post) {
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: Int.MAX_VALUE
            val keepAlive = headers["Connection"] == "Keep-Alive"
            val contentType = headers["Content-Type"]
            if (keepAlive && contentLength == Int.MAX_VALUE) throw GarminHttpException("keep-alive without content-length for $target")
            body = readBody(read, contentLength)
            if (contentType == "application/x-www-form-urlencoded") {
                // Android rebuilds the URI with the body as its query: the body is then the query as it
                // is, not decoded a second time.
                query = body
                body = null
            } else if (contentType != "application/json" && body.isNotBlank()) {
                body = null
            }
        }
        return GarminHttpRequest(path, query, body?.takeUnless(String::isBlank))
    }

    /** The bytes of an answer: the status line, the length and type when there is a body, then the body. */
    fun response(code: Int, body: String?, contentType: String?): ByteArray {
        val bytes = body?.encodeToByteArray()
        val header = StringBuilder()
        header.append("HTTP/1.1 ").append(code).append(" OK\r\n")
        if (bytes != null) header.append("Content-Length: ").append(bytes.size).append("\r\n")
        if (contentType != null) header.append("Content-Type: ").append(contentType).append("\r\n")
        header.append("\r\n")
        return header.toString().encodeToByteArray() + (bytes ?: ByteArray(0))
    }

    /** Decodes `%xx` as UTF-8, as `java.net.URI.getQuery` does. A `+` stays a `+`. */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out.add(hex.toByte())
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { out.add(it) }
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun readLine(read: () -> Int): String {
        val buffer = ArrayList<Byte>()
        while (true) {
            when (val c = read()) {
                '\r'.code -> {}
                -1, '\n'.code -> break
                else -> buffer.add(c.toByte())
            }
        }
        return buffer.toByteArray().decodeToString()
    }

    private fun readBody(read: () -> Int, length: Int): String {
        val buffer = ArrayList<Byte>()
        var remaining = length
        while (remaining-- > 0) {
            val c = read()
            if (c == -1) break
            buffer.add(c.toByte())
        }
        return buffer.toByteArray().decodeToString()
    }
}
