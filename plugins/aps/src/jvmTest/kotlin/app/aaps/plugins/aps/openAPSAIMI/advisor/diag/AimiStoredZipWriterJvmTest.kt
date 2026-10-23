package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iOS ZIP writer, read by `java.util.zip` - the reader nothing in this module wrote.
 *
 * Two readers on purpose. [ZipInputStream] reads the local headers only and refuses a STORED entry
 * with a data descriptor; [ZipFile] reads the central directory. Mail apps and unzip tools use one
 * or the other, so the package must pass both.
 */
class AimiStoredZipWriterJvmTest {

    private val report = "Diagnostic report\nété\n".encodeToByteArray()
    private val log = ByteArray(2_000_000) { (it * 7 % 256).toByte() }

    private fun writeZip(): ByteArray {
        val sink = InMemoryZipByteSink()
        val writer = AimiStoredZipWriter(sink, AimiPath("/tmp/test.zip"), System.currentTimeMillis())
        writer.beginEntry("Diagnostic_Report.txt")
        writer.write(report)
        writer.endEntry()
        writer.beginEntry("AIMI_Decisions_Last24h.jsonl")
        var from = 0
        while (from < log.size) {
            val to = minOf(from + 65_536, log.size)
            writer.write(log.copyOfRange(from, to))
            from = to
        }
        writer.endEntry()
        writer.finish()
        return sink.bytes()
    }

    @Test
    fun `ZipInputStream reads every entry from the local headers`() {
        ZipInputStream(ByteArrayInputStream(writeZip())).use { input ->
            val first = input.nextEntry
            assertEquals("Diagnostic_Report.txt", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertContentEquals(report, input.readBytes())
            val second = input.nextEntry
            assertEquals("AIMI_Decisions_Last24h.jsonl", second.name)
            assertContentEquals(log, input.readBytes())
            assertNull(input.nextEntry)
        }
    }

    @Test
    fun `ZipFile reads every entry from the central directory with the right CRC`() {
        val file = File.createTempFile("aimi_support", ".zip")
        try {
            file.writeBytes(writeZip())
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("Diagnostic_Report.txt", "AIMI_Decisions_Last24h.jsonl"), names)
                val entry = zip.getEntry("AIMI_Decisions_Last24h.jsonl")
                assertEquals(log.size.toLong(), entry.size)
                assertEquals(CRC32().apply { update(log) }.value, entry.crc)
                assertContentEquals(log, zip.getInputStream(entry).readBytes())
                assertContentEquals(report, zip.getInputStream(zip.getEntry("Diagnostic_Report.txt")).readBytes())
            }
        } finally {
            file.delete()
        }
    }
}
