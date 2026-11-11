package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import kotlin.concurrent.Volatile

/**
 * The local HTTP server the Garmin watch apps call on the iPhone, as `HttpServer` does on Android.
 *
 * Garmin Connect passes the watch's `makeWebRequest` to `http://127.0.0.1:<port>` on the phone, so
 * this listens on that address only (IPv4, as on Android: Garmin does not use `::1`). What is read
 * and written is [GarminHttp], shared with Android; this class only moves the bytes.
 *
 * iOS suspends the app in the background. The socket stays open, and a request that comes while
 * AAPS is suspended waits or fails, depending on Garmin Connect. If iOS takes the listening socket
 * away during a suspension, [accept] fails and the server opens a new one.
 *
 * ⚠️ ASYNC IMPACT: the accept loop and every request run on [Dispatchers.IO] and block there.
 */
@OptIn(ExperimentalForeignApi::class)
class IosGarminHttpServer(
    private val aapsLogger: AAPSLogger,
    val port: Int,
    private val scope: CoroutineScope,
    /** The answer to a request: the HTTP code and the JSON body, or a null body for none. */
    private val handle: (GarminHttpRequest) -> Pair<Int, String?>,
) {

    @Volatile
    private var listenFd = -1

    @Volatile
    private var closed = false

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (!closed) {
                val fd = openListener()
                if (fd < 0) {
                    delay(REOPEN_DELAY_MS)
                    continue
                }
                listenFd = fd
                aapsLogger.info(LTag.GARMIN, "accept connections on 127.0.0.1:$port")
                acceptLoop(fd)
                if (!closed) {
                    aapsLogger.warn(LTag.GARMIN, "HTTP server stopped listening (errno=$errno), opening it again")
                    closeSocket(fd)
                    delay(REOPEN_DELAY_MS)
                }
            }
        }
    }

    fun close() {
        closed = true
        val fd = listenFd
        listenFd = -1
        // shutdown first: a plain close does not always wake a thread blocked in accept on Darwin.
        if (fd >= 0) closeSocket(fd)
    }

    private fun openListener(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            aapsLogger.error(LTag.GARMIN, "HTTP server: socket failed, errno=$errno")
            return -1
        }
        val bound = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            // A quick restart on a port change must not fail with "address already in use".
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val address = alloc<sockaddr_in>()
            memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = hostToNetwork(port).convert()
            address.sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
            bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
        }
        if (!bound || listen(fd, BACKLOG) != 0) {
            aapsLogger.error(LTag.GARMIN, "HTTP server: cannot listen on 127.0.0.1:$port, errno=$errno")
            closeSocket(fd)
            return -1
        }
        return fd
    }

    private fun acceptLoop(fd: Int) {
        while (!closed) {
            val client = accept(fd, null, null)
            if (client < 0) {
                if (errno == EINTR) continue
                return
            }
            scope.launch(Dispatchers.IO) { serve(client) }
        }
    }

    private fun serve(client: Int) {
        try {
            memScoped {
                val one = alloc<IntVar>().apply { value = 1 }
                // A watch that hangs up before the answer must not end the app with SIGPIPE.
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
                val timeout = alloc<timeval>().apply {
                    tv_sec = READ_TIMEOUT_S.convert()
                    tv_usec = 0
                }
                setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            }
            val reader = SocketReader(client)
            val request = try {
                GarminHttp.readRequest(reader::read)
            } catch (e: GarminHttpException) {
                // A client may connect and send nothing; Android answers nothing then either.
                if (reader.bytesRead > 0) {
                    aapsLogger.error(LTag.GARMIN, "Invalid request: ${e.message}")
                    write(client, GarminHttp.response(HTTP_BAD_REQUEST, null, null))
                }
                return
            }
            if (request.path == "favicon.ico" || request.path == "/favicon.ico") {
                write(client, GarminHttp.response(HTTP_NOT_FOUND, null, null))
                return
            }
            val (code, body) = try {
                handle(request)
            } catch (e: Exception) {
                aapsLogger.error(LTag.GARMIN, "endpoint ${request.path} failed", e)
                HTTP_INTERNAL_ERROR to null
            }
            write(client, GarminHttp.response(code, body, if (body != null) "application/json" else null))
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "response failed", e)
        } finally {
            close(client)
        }
    }

    private fun write(fd: Int, bytes: ByteArray) {
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val sent = send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), 0).toInt()
                if (sent <= 0) {
                    if (errno == EINTR) continue
                    aapsLogger.warn(LTag.GARMIN, "HTTP server: send failed, errno=$errno")
                    return
                }
                offset += sent
            }
        }
    }

    private fun closeSocket(fd: Int) {
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }

    /** Reads a socket a block at a time and hands out single bytes, -1 at the end or on a timeout. */
    private class SocketReader(private val fd: Int) {

        private val buffer = ByteArray(BUFFER_SIZE)
        private var length = 0
        private var position = 0
        var bytesRead = 0
            private set

        fun read(): Int {
            if (position == length) {
                val received = buffer.usePinned { recv(fd, it.addressOf(0), BUFFER_SIZE.convert(), 0).toInt() }
                if (received <= 0) return -1
                length = received
                position = 0
            }
            bytesRead++
            return buffer[position++].toInt() and 0xff
        }
    }

    private companion object {

        /** 127.0.0.1 as `in_addr.s_addr` holds it on a little endian iPhone: bytes 7F 00 00 01 in memory. */
        const val LOOPBACK_NETWORK_ORDER: UInt = 0x0100007Fu
        const val BACKLOG = 8
        const val READ_TIMEOUT_S = 10
        const val BUFFER_SIZE = 4096
        const val REOPEN_DELAY_MS = 5_000L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_INTERNAL_ERROR = 500

        /** The port in network byte order, from the host order of a little endian iPhone. */
        fun hostToNetwork(port: Int): Int = ((port and 0xff) shl 8) or ((port shr 8) and 0xff)
    }
}
