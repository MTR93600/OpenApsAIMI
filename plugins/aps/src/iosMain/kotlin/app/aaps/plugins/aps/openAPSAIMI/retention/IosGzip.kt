package app.aaps.plugins.aps.openAPSAIMI.retention

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.compressedDataUsingAlgorithm
import platform.Foundation.create

/**
 * Builds gzip members on iOS without a zlib cinterop.
 *
 * `platform.Foundation` only exposes zlib compression as
 * `-[NSData compressedDataUsingAlgorithm:]`, which produces the RFC 1950 zlib framing
 * (2-byte header + raw deflate + 4-byte ADLER32 trailer). A gzip member needs the raw
 * deflate stream wrapped in the 10-byte gzip header and the 8-byte trailer
 * (CRC32 + ISIZE), so the zlib framing is stripped here and the gzip framing is
 * written by hand. The CRC32 is the standard IEEE polynomial, computed in pure Kotlin,
 * so it matches what `java.util.zip` produces on Android.
 *
 * The output is a complete, self-contained gzip member. Concatenated members form a
 * valid multi-member gzip stream, which is exactly how `AimiArchive` on Android lays
 * out archive files, so archives written here stay readable by the same tools.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosGzip {

    /**
     * Compresses [data] into one gzip member, or `null` when compression failed.
     *
     * Callers must treat `null` as "nothing was written": it is the fail-closed signal
     * the janitor uses to leave the source file untouched.
     */
    fun member(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflate = rawDeflate(data) ?: return null
        val out = ByteArray(GZIP_HEADER.size + deflate.size + TRAILER_SIZE)
        GZIP_HEADER.copyInto(out, 0)
        deflate.copyInto(out, GZIP_HEADER.size)
        putIntLe(out, GZIP_HEADER.size + deflate.size, crc32(data))
        putIntLe(out, GZIP_HEADER.size + deflate.size + 4, data.size)
        return out
    }

    /**
     * Raw deflate bytes for [data]: the zlib framing is stripped off the Foundation output.
     *
     * RFC 1950 fixes the framing at a 2-byte header and a 4-byte ADLER32 trailer, so the
     * slice is deterministic. Anything at or below that framing size cannot hold deflate
     * output and is rejected.
     */
    private fun rawDeflate(data: ByteArray): ByteArray? {
        val zlib = data.toNSData().compressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)
            ?.toByteArray() ?: return null
        if (zlib.size <= ZLIB_HEADER_SIZE + ADLER32_SIZE) return null
        return zlib.copyOfRange(ZLIB_HEADER_SIZE, zlib.size - ADLER32_SIZE)
    }

    /** IEEE CRC32. Same result as `java.util.zip.CRC32` on the same bytes. */
    private fun crc32(data: ByteArray): Int {
        var crc = CRC32_INIT
        for (b in data) {
            crc = CRC32_TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor CRC32_INIT
    }

    private fun putIntLe(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        out[at + 2] = ((value ushr 16) and 0xFF).toByte()
        out[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.toNSData(): NSData = memScoped {
        // dataWithBytes:length: copies, so the NSData outlives this scope.
        NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray =
        bytes?.readBytes(length.toInt()) ?: ByteArray(0)

    private val GZIP_HEADER = byteArrayOf(
        0x1F, 0x8B.toByte(), 0x08, 0x00, // magic, deflate, no flags
        0x00, 0x00, 0x00, 0x00, // mtime = 0, like java.util.zip
        0x00, 0x03, // XFL = 0, OS = Unix
    )

    private const val TRAILER_SIZE = 8 // CRC32 + ISIZE
    private const val ZLIB_HEADER_SIZE = 2
    private const val ADLER32_SIZE = 4

    private const val CRC32_INIT = 0xFFFFFFFF.toInt()

    private val CRC32_TABLE: IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }
}
