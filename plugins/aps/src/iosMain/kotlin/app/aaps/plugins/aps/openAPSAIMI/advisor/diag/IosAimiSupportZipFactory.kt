package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fseeko
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.strerror

/**
 * iOS half of [AimiSupportZipFactory]: [AimiStoredZipWriter] over a POSIX file in [directory].
 *
 * The default directory is the app temporary directory. The share sheet can read a file there, and
 * iOS empties it by itself, much as Android does with the cache directory the Android package goes
 * to. Public Foundation has no ZIP API, hence the pure Kotlin writer.
 *
 * POSIX `stdio` rather than `NSFileHandle`: an `NSFileHandle` write that fails raises an
 * Objective-C exception, which Kotlin/Native cannot catch and which ends the app. `fwrite` answers
 * an error code, which becomes a Kotlin exception and then a "could not build" message.
 */
class IosAimiSupportZipFactory(
    private val directory: String = NSTemporaryDirectory(),
) : AimiSupportZipFactory {

    override fun create(fileName: String): AimiSupportZipOutput {
        val path = directory.trimEnd('/') + "/" + fileName
        val sink = PosixZipByteSink.open(path)
        return AimiStoredZipWriter(sink, AimiPath(path), aimiWallClockMs())
    }
}

/** An [AimiZipByteSink] on a POSIX `FILE`. Every failure throws. */
@OptIn(ExperimentalForeignApi::class)
internal class PosixZipByteSink private constructor(
    private val file: CPointer<FILE>,
    private val path: String,
) : AimiZipByteSink {

    override var position: Long = 0
        private set

    private var closed = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writeRaw(bytes, offset, length)
        position += length
    }

    override fun overwrite(at: Long, bytes: ByteArray) {
        require(at >= 0 && at + bytes.size <= position) { "overwrite outside the written bytes" }
        if (fseeko(file, at, SEEK_SET) != 0) fail("seek")
        writeRaw(bytes, 0, bytes.size)
        if (fseeko(file, 0L, SEEK_END) != 0) fail("seek")
    }

    override fun close() {
        if (closed) return
        closed = true
        val flushed = fflush(file)
        val closedOk = fclose(file)
        if (flushed != 0 || closedOk != 0) fail("close")
    }

    override fun discard() {
        if (!closed) {
            closed = true
            fclose(file)
        }
        remove(path)
    }

    private fun writeRaw(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "the file is closed" }
        if (length == 0) return
        val written = bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(offset), 1.convert(), length.convert(), file)
        }
        if (written.toLong() != length.toLong()) fail("write")
    }

    private fun fail(what: String): Nothing =
        throw IllegalStateException("support package $what failed: ${strerror(errno)?.toKString()}")

    companion object {

        /** Creates (or empties) the file at [path]. Throws when it cannot be created. */
        fun open(path: String): PosixZipByteSink {
            val file = fopen(path, "wb")
                ?: throw IllegalStateException("cannot create the support package: ${strerror(errno)?.toKString()}")
            return PosixZipByteSink(file, path)
        }
    }
}
