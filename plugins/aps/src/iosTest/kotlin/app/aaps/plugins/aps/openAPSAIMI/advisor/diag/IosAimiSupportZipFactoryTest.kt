package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS support package writer on a real file: written through POSIX, read back from disk and
 * checked entry by entry, so a broken ZIP is caught on the simulator.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAimiSupportZipFactoryTest {

    private val directory = NSTemporaryDirectory()
    private val fileName = "AIMI_Support_Package_test_${NSUUID().UUIDString}.zip"
    private val factory = IosAimiSupportZipFactory(directory)

    @AfterTest
    fun cleanUp() {
        remove(directory.trimEnd('/') + "/" + fileName)
    }

    @Test
    fun `a package written to disk reads back with the same entries`() {
        val report = "Diagnostic report\nété\n".encodeToByteArray()
        // Several megabytes, written in pieces, as the decision log is.
        val log = ByteArray(3_000_000) { (it * 13 % 256).toByte() }
        val csv = "header\nrow1\n".encodeToByteArray()

        val zip = factory.create(fileName)
        zip.beginEntry("Diagnostic_Report.txt")
        zip.write(report)
        zip.endEntry()
        zip.beginEntry("AIMI_Decisions_Last24h.jsonl")
        var from = 0
        while (from < log.size) {
            val to = minOf(from + 65_536, log.size)
            zip.write(log.copyOfRange(from, to))
            from = to
        }
        zip.endEntry()
        zip.beginEntry("oapsaimiML2_records.csv")
        zip.write(csv)
        zip.endEntry()
        val size = zip.finish()

        assertTrue(zip.path.value.endsWith(fileName))
        val bytes = assertNotNull(readFile(zip.path.value))
        assertEquals(bytes.size.toLong(), size)
        val entries = ZipTestReader.read(bytes)
        assertEquals(
            listOf("Diagnostic_Report.txt", "AIMI_Decisions_Last24h.jsonl", "oapsaimiML2_records.csv"),
            entries.map { it.name },
        )
        assertContentEquals(report, entries[0].content)
        assertContentEquals(log, entries[1].content)
        assertContentEquals(csv, entries[2].content)
        assertEquals(log.size.toLong(), entries[1].size)
        assertEquals(ZipTestReader.referenceCrc32(log), entries[1].crc)
    }

    @Test
    fun `an existing file of the same name is replaced`() {
        val first = factory.create(fileName)
        first.beginEntry("old.txt")
        first.write(ByteArray(10_000))
        first.endEntry()
        first.finish()

        val second = factory.create(fileName)
        second.beginEntry("new.txt")
        second.write("new".encodeToByteArray())
        second.endEntry()
        second.finish()

        val entries = ZipTestReader.read(assertNotNull(readFile(second.path.value)))
        assertEquals(listOf("new.txt"), entries.map { it.name })
    }

    @Test
    fun `abort deletes the partial file`() {
        val zip = factory.create(fileName)
        zip.beginEntry("AIMI_Decisions_Last24h.jsonl")
        zip.write(ByteArray(200_000) { 'x'.code.toByte() })
        assertNotNull(readFile(zip.path.value), "the file exists while it is written")

        zip.abort()

        assertNull(readFile(zip.path.value), "the partial package holds health data and is removed")
    }

    @Test
    fun `abort after a finish keeps the file`() {
        val zip = factory.create(fileName)
        zip.beginEntry("a.txt")
        zip.endEntry()
        zip.finish()

        zip.abort()

        assertNotNull(readFile(zip.path.value))
    }

    @Test
    fun `a directory that does not exist fails loudly`() {
        val missing = IosAimiSupportZipFactory(directory.trimEnd('/') + "/no_such_dir_${NSUUID().UUIDString}")
        assertFailsWith<IllegalStateException> { missing.create(fileName) }
    }

    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            if (fseeko(file, 0L, SEEK_END) != 0) return null
            val size = ftello(file)
            if (fseeko(file, 0L, SEEK_SET) != 0) return null
            val bytes = ByteArray(size.toInt())
            if (bytes.isEmpty()) return bytes
            val read = bytes.usePinned { fread(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
            return if (read.toLong() == size) bytes else null
        } finally {
            fclose(file)
        }
    }
}
