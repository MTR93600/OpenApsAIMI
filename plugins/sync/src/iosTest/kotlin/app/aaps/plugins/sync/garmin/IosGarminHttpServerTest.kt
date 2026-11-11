package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iPhone Garmin server on a real socket on 127.0.0.1, the address Garmin Connect calls: a request
 * goes in, the shared answer comes out, and a closed server no longer answers.
 */
@OptIn(ExperimentalForeignApi::class)
class IosGarminHttpServerTest {

    private object SilentLogger : AAPSLogger {

        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(tag: LTag, message: String) {}
        override fun error(tag: LTag, message: String, throwable: Throwable) {}
        override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(message: String) {}
        override fun error(message: String, throwable: Throwable) {}
        override fun error(format: String, vararg arguments: Any?) {}
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    }

    private val port = 28_991
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val seen = mutableListOf<GarminHttpRequest>()
    private val server = IosGarminHttpServer(SilentLogger, port, scope) { request ->
        seen += request
        if (request.path == "/get") 200 to """{"ok":true}""" else 404 to null
    }

    @AfterTest
    fun stop() {
        server.close()
        scope.cancel()
    }

    /** Sends [text] to the server and reads the whole answer, or null when nothing listens. */
    private fun call(text: String): String? = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_len = sizeOf<sockaddr_in>().convert()
        address.sin_family = AF_INET.convert()
        address.sin_port = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).convert()
        address.sin_addr.s_addr = 0x0100007Fu
        if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            close(fd)
            return null
        }
        val bytes = text.encodeToByteArray()
        bytes.usePinned { send(fd, it.addressOf(0), bytes.size.convert(), 0) }
        val answer = ArrayList<Byte>()
        val buffer = ByteArray(1024)
        while (true) {
            val received = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0).toInt() }
            if (received <= 0) break
            for (i in 0 until received) answer.add(buffer[i])
        }
        close(fd)
        answer.toByteArray().decodeToString()
    }

    /** The server starts on a background thread: try for up to 3 s. */
    private fun callWhenReady(text: String): String? {
        repeat(60) {
            call(text)?.let { return it }
            usleep(50_000u)
        }
        return null
    }

    @Test
    fun aWatchRequestGetsTheAnswer() {
        server.start()

        val answer = callWhenReady("GET /get?key=abc&wait=0 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}", answer)
        assertEquals(GarminHttpRequest("/get", "key=abc&wait=0", null), seen.single())
    }

    @Test
    fun anUnknownPathGetsNotFound() {
        server.start()

        assertEquals("HTTP/1.1 404 OK\r\n\r\n", callWhenReady("GET /nothing HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun aClosedServerNoLongerAnswers() {
        server.start()
        callWhenReady("GET /get HTTP/1.1\r\n\r\n")

        server.close()
        usleep(200_000u)

        assertNull(call("GET /get HTTP/1.1\r\n\r\n"))
    }
}
