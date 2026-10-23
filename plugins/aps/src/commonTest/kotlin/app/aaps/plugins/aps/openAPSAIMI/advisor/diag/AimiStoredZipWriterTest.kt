package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure Kotlin ZIP writer iOS uses for the support package. Runs on every target, so a broken
 * archive is caught on the simulator as well as on the JVM.
 */
class AimiStoredZipWriterTest {

    private val sink = InMemoryZipByteSink()
    private val writer = AimiStoredZipWriter(sink, AimiPath("/tmp/test.zip"), 0L, TimeZone.UTC)

    @Test
    fun `entries are read back with names sizes CRC and content`() {
        val report = "report text\nline two\n".encodeToByteArray()
        val big = ByteArray(300_000) { (it % 251).toByte() }
        writer.beginEntry("Diagnostic_Report.txt")
        writer.write(report)
        writer.endEntry()
        writer.beginEntry("AIMI_Decisions_Last24h.jsonl")
        // Several writes for one entry, as the decision log is streamed.
        var from = 0
        while (from < big.size) {
            val to = minOf(from + 65_536, big.size)
            writer.write(big.copyOfRange(from, to))
            from = to
        }
        writer.endEntry()
        writer.beginEntry("empty.csv")
        writer.endEntry()
        val size = writer.finish()

        val zip = sink.bytes()
        assertEquals(zip.size.toLong(), size)
        assertTrue(sink.closed)
        val entries = ZipTestReader.read(zip)
        assertEquals(listOf("Diagnostic_Report.txt", "AIMI_Decisions_Last24h.jsonl", "empty.csv"), entries.map { it.name })
        assertContentEquals(report, entries[0].content)
        assertContentEquals(big, entries[1].content)
        assertEquals(0, entries[2].content.size)
        entries.forEach { entry ->
            assertEquals(0, entry.method, "STORED")
            assertEquals(entry.size, entry.compressedSize)
            assertEquals(entry.content.size.toLong(), entry.size)
        }
        assertEquals(ZipTestReader.referenceCrc32(big), entries[1].crc)
        assertEquals(0, entries[2].crc)
    }

    @Test
    fun `names are stored as UTF-8 and flagged so`() {
        val name = "Rapport_été_日本.txt"
        writer.beginEntry(name)
        writer.write("x".encodeToByteArray())
        writer.endEntry()
        writer.finish()

        val entry = ZipTestReader.read(sink.bytes()).single()
        assertEquals(name, entry.name)
        assertEquals(1 shl 11, entry.flags and (1 shl 11))
    }

    @Test
    fun `an archive with no entry is still a valid ZIP`() {
        assertEquals(22L, writer.finish())
        assertEquals(0, ZipTestReader.read(sink.bytes()).size)
    }

    @Test
    fun `local time is stored in the DOS fields`() {
        val zone = TimeZone.UTC
        val ms = LocalDateTime(2026, 10, 23, 14, 37, 58).toInstant(zone).toEpochMilliseconds()
        val dated = AimiStoredZipWriter(sink, AimiPath("/tmp/test.zip"), ms, zone)
        dated.beginEntry("a")
        dated.endEntry()
        dated.finish()

        val zip = sink.bytes()
        val time = (zip[10].toInt() and 0xFF) or ((zip[11].toInt() and 0xFF) shl 8)
        val date = (zip[12].toInt() and 0xFF) or ((zip[13].toInt() and 0xFF) shl 8)
        assertEquals((14 shl 11) or (37 shl 5) or (58 / 2), time)
        assertEquals(((2026 - 1980) shl 9) or (10 shl 5) or 23, date)
    }

    @Test
    fun `writing outside an entry throws`() {
        assertFailsWith<IllegalStateException> { writer.write("x".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { writer.endEntry() }
    }

    @Test
    fun `finishing with an open entry throws`() {
        writer.beginEntry("a")
        assertFailsWith<IllegalStateException> { writer.finish() }
    }

    @Test
    fun `abort closes and removes the file and nothing more can be written`() {
        writer.beginEntry("a")
        writer.abort()
        assertTrue(sink.closed)
        assertTrue(sink.discarded, "the partial file holds health data and is removed")
        assertFailsWith<IllegalStateException> { writer.beginEntry("b") }
        // A second abort does nothing.
        writer.abort()
    }

    /** A sink failure leaves a hole in the entry, so the archive must never be finished after it. */
    @Test
    fun `after a sink failure the entry and the archive cannot be finished`() {
        writer.beginEntry("a")
        writer.write("first".encodeToByteArray())
        sink.failOnWrite = 3 // 1 header, 2 "first", 3 the next piece
        val before = sink.position
        assertFailsWith<IllegalStateException> { writer.write("second".encodeToByteArray()) }
        assertEquals(before, sink.position, "nothing was taken by the sink")

        assertFailsWith<IllegalStateException> { writer.write("third".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { writer.endEntry() }
        assertFailsWith<IllegalStateException> { writer.finish() }
        assertFalse(sink.closed, "not closed as if it were complete")

        writer.abort()
        assertTrue(sink.discarded)
    }

    @Test
    fun `abort after a finish that worked keeps the file`() {
        writer.beginEntry("a")
        writer.endEntry()
        writer.finish()

        writer.abort()

        assertFalse(sink.discarded)
    }
}
