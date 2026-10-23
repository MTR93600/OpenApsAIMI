package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An [AimiZipByteSink] that keeps the bytes in memory, for tests that run on every target.
 */
internal class InMemoryZipByteSink : AimiZipByteSink {

    private var buffer = ByteArray(1024)
    private var size = 0
    var closed = false
        private set

    override val position: Long get() = size.toLong()

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "closed" }
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    override fun overwrite(at: Long, bytes: ByteArray) {
        check(!closed) { "closed" }
        require(at >= 0 && at + bytes.size <= size)
        bytes.copyInto(buffer, at.toInt())
    }

    /** Set by [discard]: the file would have been removed. */
    var discarded = false
        private set

    /** When set, the [write] call with this number (counted from 1) throws, as a full disk would. */
    var failOnWrite: Int? = null

    private var writes = 0

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writes++
        if (writes == failOnWrite) throw IllegalStateException("disk full")
        append(bytes, offset, length)
    }

    override fun close() {
        closed = true
    }

    override fun discard() {
        closed = true
        discarded = true
    }

    fun bytes(): ByteArray = buffer.copyOf(size)
}

/**
 * Reads back a ZIP written by [AimiStoredZipWriter], and checks it on the way.
 *
 * It does not trust the writer: it reads the end record, walks the central directory, then checks
 * that every local header says the same thing as its central entry, that the data of each entry
 * follows its header with no gap, and that each stored CRC-32 matches the data. The CRC is
 * computed here bit by bit, not with `AimiCrc32`, so a wrong table in the writer would be caught.
 */
internal object ZipTestReader {

    class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Int,
        val compressedSize: Long,
        val size: Long,
        val content: ByteArray,
    )

    fun read(zip: ByteArray): List<Entry> {
        val end = zip.size - 22
        assertTrue(end >= 0, "too short for an end record")
        assertEquals(0x06054b50, int(zip, end), "end record signature")
        val count = short(zip, end + 10)
        assertEquals(count, short(zip, end + 8), "entries on this disk")
        val directorySize = uint(zip, end + 12)
        val directoryOffset = uint(zip, end + 16)
        assertEquals(end.toLong(), directoryOffset + directorySize, "central directory ends at the end record")

        val entries = mutableListOf<Entry>()
        var at = directoryOffset.toInt()
        var expectedLocal = 0L
        repeat(count) {
            assertEquals(0x02014b50, int(zip, at), "central header signature")
            val flags = short(zip, at + 8)
            val method = short(zip, at + 10)
            val crc = int(zip, at + 16)
            val compressed = uint(zip, at + 20)
            val size = uint(zip, at + 24)
            val nameLength = short(zip, at + 28)
            val extraLength = short(zip, at + 30)
            val commentLength = short(zip, at + 32)
            val localOffset = uint(zip, at + 42)
            val nameBytes = zip.copyOfRange(at + 46, at + 46 + nameLength)

            assertEquals(expectedLocal, localOffset, "entries follow each other with no gap")
            val local = localOffset.toInt()
            assertEquals(0x04034b50, int(zip, local), "local header signature")
            assertEquals(flags, short(zip, local + 6), "local flags")
            assertEquals(method, short(zip, local + 8), "local method")
            assertEquals(crc, int(zip, local + 14), "local CRC-32")
            assertEquals(compressed, uint(zip, local + 18), "local compressed size")
            assertEquals(size, uint(zip, local + 22), "local size")
            val localNameLength = short(zip, local + 26)
            val localExtraLength = short(zip, local + 28)
            assertEquals(nameLength, localNameLength, "local name length")
            assertTrue(
                nameBytes.contentEquals(zip.copyOfRange(local + 30, local + 30 + localNameLength)),
                "local name",
            )
            val dataStart = local + 30 + localNameLength + localExtraLength
            val content = zip.copyOfRange(dataStart, dataStart + compressed.toInt())
            assertEquals(referenceCrc32(content), crc, "CRC-32 of ${nameBytes.decodeToString()}")
            expectedLocal = dataStart.toLong() + compressed

            entries += Entry(nameBytes.decodeToString(), flags, method, crc, compressed, size, content)
            at += 46 + nameLength + extraLength + commentLength
        }
        assertEquals(directoryOffset, expectedLocal, "central directory follows the last entry")
        assertEquals(end, at, "central directory size")
        return entries
    }

    /** CRC-32 bit by bit, the slow way, independent of the table in `AimiCrc32`. */
    fun referenceCrc32(bytes: ByteArray): Int {
        var c = -1
        for (b in bytes) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
        }
        return c.inv()
    }

    private fun short(zip: ByteArray, at: Int): Int =
        (zip[at].toInt() and 0xFF) or ((zip[at + 1].toInt() and 0xFF) shl 8)

    private fun int(zip: ByteArray, at: Int): Int = short(zip, at) or (short(zip, at + 2) shl 16)

    private fun uint(zip: ByteArray, at: Int): Long = int(zip, at).toLong() and 0xFFFFFFFFL
}
