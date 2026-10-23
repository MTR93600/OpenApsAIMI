package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiCrc32
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A file the ZIP writer writes to.
 *
 * It needs one thing more than a plain stream: going back to fill in bytes it already wrote. The
 * checksum and the size of an entry are only known at its end, but they belong in the header
 * before it.
 */
interface AimiZipByteSink {

    /** How many bytes were written so far. */
    val position: Long

    /** Adds [length] bytes of [bytes], starting at [offset], at the end. Throws on failure. */
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /**
     * Writes [bytes] at [at], over bytes that were already written. [position] does not change.
     * Throws on failure.
     */
    fun overwrite(at: Long, bytes: ByteArray)

    /** Flushes and closes. Throws when the data could not be saved. */
    fun close()

    /**
     * Closes without saving, when still open, and removes the file. Never throws.
     *
     * Used after a failure. The file holds health data, so a broken half of it must not stay on
     * the phone.
     */
    fun discard()
}

/**
 * A small ZIP writer in pure Kotlin, for the platforms that have no ZIP library.
 *
 * iOS uses it for the support package: public Foundation has no ZIP API. Android does not, it keeps
 * `java.util.zip`. It is in shared code, not in iosMain, so that the same tests check it on every
 * target, and on the JVM against `java.util.zip` itself.
 *
 * What it writes, and why:
 * - **STORED entries, no compression.** Compression would need the whole entry in memory (Foundation
 *   only compresses a complete buffer), and the decision log can be several megabytes. The
 *   package is bigger than on Android, but the content is the same.
 * - **No data descriptor.** The checksum and sizes are filled into the local header with
 *   [AimiZipByteSink.overwrite] after the entry. Some readers, `java.util.zip.ZipInputStream` among
 *   them, refuse a STORED entry that has a data descriptor.
 * - **UTF-8 names**, flagged as such (general purpose bit 11).
 * - **No ZIP64.** A file or archive over 4 GiB, or more than 65535 entries, throws instead of
 *   writing a broken archive. The support package is a few megabytes.
 *
 * Entries are streamed: each [write] goes straight to the sink. Only the names and offsets of the
 * entries are kept until [finish].
 *
 * After the sink throws once, the writer is failed: every later call throws too, so a package with
 * a hole in it can never be finished and shared as complete. The size and checksum of an entry
 * only count bytes the sink really took.
 *
 * @param modifiedAtMs the time stored on every entry, in epoch milliseconds.
 * @param zone the time zone of that stored time. ZIP stores local time, as `java.util.zip` does.
 */
class AimiStoredZipWriter(
    private val sink: AimiZipByteSink,
    override val path: AimiPath,
    modifiedAtMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) : AimiSupportZipOutput {

    private class Entry(val name: ByteArray, val headerOffset: Long) {

        var crc: Int = 0
        var size: Long = 0
    }

    private val entries = mutableListOf<Entry>()
    private var current: Entry? = null
    private val crc = AimiCrc32()
    private var closed = false
    private var finished = false
    private var failed = false
    private val dosTime: Int
    private val dosDate: Int

    init {
        val local = Instant.fromEpochMilliseconds(modifiedAtMs).toLocalDateTime(zone)
        if (local.year < 1980) {
            // The earliest time a ZIP can hold: 1980-01-01 00:00.
            dosTime = 0
            dosDate = (1 shl 5) or 1
        } else {
            dosTime = (local.hour shl 11) or (local.minute shl 5) or (local.second / 2)
            dosDate = ((local.year - 1980) shl 9) or (local.monthNumber shl 5) or local.day
        }
    }

    override fun beginEntry(name: String) = guarded {
        check(!closed) { "the archive is closed" }
        check(current == null) { "the previous entry was not ended" }
        check(entries.size < MAX_ENTRIES) { "too many entries for a ZIP without ZIP64" }
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size <= MAX_16) { "entry name too long" }
        val entry = Entry(nameBytes, sink.position)
        checkFits(entry.headerOffset)
        val header = LittleEndianBytes(LOCAL_HEADER_SIZE + nameBytes.size)
            .int(LOCAL_HEADER_SIGNATURE)
            .short(VERSION_NEEDED)
            .short(FLAG_UTF8_NAMES)
            .short(METHOD_STORED)
            .short(dosTime)
            .short(dosDate)
            .int(0) // CRC-32, filled in by endEntry
            .int(0) // compressed size, filled in by endEntry
            .int(0) // size, filled in by endEntry
            .short(nameBytes.size)
            .short(0) // no extra field
            .bytes(nameBytes)
        sink.write(header.array, 0, header.array.size)
        crc.reset()
        current = entry
    }

    override fun write(bytes: ByteArray) = guarded {
        val entry = checkNotNull(current) { "no entry was begun" }
        if (bytes.isEmpty()) return@guarded
        val newSize = entry.size + bytes.size
        checkFits(newSize)
        sink.write(bytes, 0, bytes.size)
        // Counted only once the sink has taken the bytes.
        entry.size = newSize
        crc.update(bytes)
    }

    override fun endEntry() = guarded {
        val entry = checkNotNull(current) { "no entry was begun" }
        entry.crc = crc.value
        val sizes = LittleEndianBytes(12)
            .int(entry.crc)
            .int(entry.size.toInt()) // compressed size: the same, the entry is STORED
            .int(entry.size.toInt())
        sink.overwrite(entry.headerOffset + LOCAL_HEADER_CRC_OFFSET, sizes.array)
        entries += entry
        current = null
    }

    override fun finish(): Long = guarded {
        check(!closed) { "the archive is closed" }
        check(current == null) { "the last entry was not ended" }
        val directoryOffset = sink.position
        checkFits(directoryOffset)
        for (entry in entries) {
            val header = LittleEndianBytes(CENTRAL_HEADER_SIZE + entry.name.size)
                .int(CENTRAL_HEADER_SIGNATURE)
                .short(VERSION_MADE_BY)
                .short(VERSION_NEEDED)
                .short(FLAG_UTF8_NAMES)
                .short(METHOD_STORED)
                .short(dosTime)
                .short(dosDate)
                .int(entry.crc)
                .int(entry.size.toInt())
                .int(entry.size.toInt())
                .short(entry.name.size)
                .short(0) // no extra field
                .short(0) // no comment
                .short(0) // disk number
                .short(0) // internal attributes
                .int(0) // external attributes
                .int(entry.headerOffset.toInt())
                .bytes(entry.name)
            sink.write(header.array, 0, header.array.size)
        }
        val directorySize = sink.position - directoryOffset
        checkFits(directorySize)
        val end = LittleEndianBytes(END_RECORD_SIZE)
            .int(END_RECORD_SIGNATURE)
            .short(0) // this disk
            .short(0) // disk with the central directory
            .short(entries.size)
            .short(entries.size)
            .int(directorySize.toInt())
            .int(directoryOffset.toInt())
            .short(0) // no comment
        sink.write(end.array, 0, end.array.size)
        val size = sink.position
        closed = true
        sink.close()
        finished = true
        size
    }

    /** Removes the file, also when [finish] itself failed. Does nothing after a [finish] that worked. */
    override fun abort() {
        if (finished) return
        closed = true
        failed = true
        sink.discard()
    }

    /** Runs [block], refusing to start when an earlier call failed, and marking the writer failed when it throws. */
    private inline fun <T> guarded(block: () -> T): T {
        check(!failed) { "an earlier write to the support package failed" }
        try {
            return block()
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    private fun checkFits(value: Long) {
        check(value <= MAX_32) { "the support package is too big for a ZIP without ZIP64" }
    }

    /** A fixed size buffer filled in ZIP byte order (little endian). */
    private class LittleEndianBytes(size: Int) {

        val array = ByteArray(size)
        private var at = 0

        fun short(value: Int): LittleEndianBytes {
            array[at++] = value.toByte()
            array[at++] = (value ushr 8).toByte()
            return this
        }

        fun int(value: Int): LittleEndianBytes {
            short(value and 0xFFFF)
            short(value ushr 16)
            return this
        }

        fun bytes(value: ByteArray): LittleEndianBytes {
            value.copyInto(array, at)
            at += value.size
            return this
        }
    }

    private companion object {

        const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        const val END_RECORD_SIGNATURE = 0x06054b50

        const val LOCAL_HEADER_SIZE = 30
        const val CENTRAL_HEADER_SIZE = 46
        const val END_RECORD_SIZE = 22

        /** Where the CRC-32 sits in a local header; both sizes follow it. */
        const val LOCAL_HEADER_CRC_OFFSET = 14

        /** ZIP 1.0 is enough for a STORED entry. `java.util.zip` writes the same for one. */
        const val VERSION_NEEDED = 10

        /** Written by ZIP 2.0 rules, on MS-DOS / FAT attributes (high byte 0). */
        const val VERSION_MADE_BY = 20

        const val FLAG_UTF8_NAMES = 1 shl 11
        const val METHOD_STORED = 0

        const val MAX_16 = 0xFFFF
        const val MAX_ENTRIES = 0xFFFF
        const val MAX_32 = 0xFFFFFFFFL
    }
}
