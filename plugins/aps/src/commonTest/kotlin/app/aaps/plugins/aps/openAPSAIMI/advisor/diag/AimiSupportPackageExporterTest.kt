package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the export answers, on both phones, for the ZIP part of
 * [AimiSupportPackageExporter.writePackage]. Only [AimiSupportPackageResult.Ready] is shared by
 * the screen, so a broken or empty package must never answer it.
 */
class AimiSupportPackageExporterTest {

    private val storage = InMemoryAimiStorage()
    private val now = 1_800_000_000_000L
    private val sink = InMemoryZipByteSink()
    private val factory = AimiSupportZipFactory { name ->
        AimiStoredZipWriter(sink, AimiPath("/tmp/$name"), now, TimeZone.UTC)
    }

    private fun writeJournal(lines: Int) {
        storage.files[storage.file("AIMI_Decisions.jsonl").value] =
            (0 until lines).joinToString("") { """{"timestamp":${now - it * 1_000L},"tag":"tick_$it"}""" + "\n" }
    }

    @Test
    fun `a full package answers Ready`() {
        writeJournal(10)

        val result = AimiSupportPackageExporter.writePackage(factory, "REPORT", storage, NoOpAapsLogger, now)

        assertEquals(AimiSupportPackageResult.Ready(AimiPath("/tmp/AIMI_Support_Package_$now.zip")), result)
        assertFalse(sink.discarded)
        assertEquals(2, ZipTestReader.read(sink.bytes()).size)
    }

    @Test
    fun `a sink that throws in the middle of the journal answers Failed and removes the file`() {
        writeJournal(5_000)
        // 1 report header, 2 report, 3 journal header, 4 the first journal piece.
        sink.failOnWrite = 4

        val result = AimiSupportPackageExporter.writePackage(factory, "REPORT", storage, NoOpAapsLogger, now)

        assertEquals(AimiSupportPackageResult.Failed("disk full"), result)
        assertTrue(sink.discarded, "the partial file is removed")
    }

    @Test
    fun `a sink that throws while the end is written answers Failed and removes the file`() {
        // 1 report header, 2 report, 3 central directory.
        sink.failOnWrite = 3

        val result = AimiSupportPackageExporter.writePackage(factory, "REPORT", storage, NoOpAapsLogger, now)

        assertIs<AimiSupportPackageResult.Failed>(result)
        assertTrue(sink.discarded)
    }

    @Test
    fun `nothing to send answers Empty and leaves no file`() {
        val result = AimiSupportPackageExporter.writePackage(factory, "", storage, NoOpAapsLogger, now)

        assertEquals(AimiSupportPackageResult.Empty, result)
        assertTrue(sink.discarded, "the empty archive is removed, not shared")
    }

    @Test
    fun `a factory that cannot create the file answers Failed`() {
        val broken = AimiSupportZipFactory { throw IllegalStateException("no space") }

        val result = AimiSupportPackageExporter.writePackage(broken, "REPORT", storage, NoOpAapsLogger, now)

        assertEquals(AimiSupportPackageResult.Failed("no space"), result)
    }
}
