package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the support package holds, on both phones: entry names, order, the 24 hour cut of the
 * decision log and the CSV tail. `scripts/aimi_replay_fixture.py` reads
 * `AIMI_Decisions_Last24h.jsonl` from this package, so its name and content are pinned here.
 */
class AimiSupportPackageContentTest {

    private val storage = InMemoryAimiStorage()
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    /** Records the entries written, as text. */
    private class RecordingZipOutput : AimiSupportZipOutput {

        val entries = linkedMapOf<String, StringBuilder>()
        private var current: StringBuilder? = null
        override val path = AimiPath("/tmp/recording.zip")

        override fun beginEntry(name: String) {
            check(current == null)
            current = StringBuilder().also { entries[name] = it }
        }

        override fun write(bytes: ByteArray) {
            checkNotNull(current).append(bytes.decodeToString())
        }

        override fun endEntry() {
            checkNotNull(current)
            current = null
        }

        override fun finish(): Long = 0L
        override fun abort() = Unit
    }

    private fun decision(ts: Long, tag: String) = """{"timestamp":$ts,"tag":"$tag"}"""

    private fun writeDecisions(vararg lines: String) {
        storage.files[storage.file("AIMI_Decisions.jsonl").value] = lines.joinToString("\n", postfix = "\n")
    }

    private fun build(report: String = "REPORT"): Map<String, String> {
        val out = RecordingZipOutput()
        AimiSupportPackageContent.write(out, report, storage, NoOpAapsLogger, now)
        return out.entries.mapValues { it.value.toString() }
    }

    @Test
    fun `entries come in the same order and with the same names as on Android`() {
        writeDecisions(decision(now - hour, "recent"))
        storage.files[storage.file("oapsaimiML2_records.csv").value] = "h1,h2\n1,2\n"

        assertEquals(
            listOf("Diagnostic_Report.txt", "AIMI_Decisions_Last24h.jsonl", "oapsaimiML2_records.csv"),
            build().keys.toList(),
        )
    }

    @Test
    fun `the report is written as it is`() {
        assertEquals("line 1\nété\n", build("line 1\nété\n")["Diagnostic_Report.txt"])
    }

    @Test
    fun `an empty report and missing files give an empty package`() {
        assertTrue(build(report = "").isEmpty())
    }

    @Test
    fun `missing journal and CSV leave only the report`() {
        assertEquals(listOf("Diagnostic_Report.txt"), build().keys.toList())
    }

    @Test
    fun `decision log keeps the last 24 hours only`() {
        writeDecisions(
            decision(now - 25 * hour, "too_old"),
            decision(now - 24 * hour - 1, "just_too_old"),
            decision(now - 24 * hour, "exactly_24h"),
            """{"no_timestamp":true}""",
            decision(now - hour, "recent"),
            decision(now, "now"),
        )

        assertEquals(
            listOf(
                decision(now - 24 * hour, "exactly_24h"),
                decision(now - hour, "recent"),
                decision(now, "now"),
            ).joinToString("") { it + "\n" },
            build()["AIMI_Decisions_Last24h.jsonl"],
        )
    }

    @Test
    fun `a journal with nothing recent still gives an empty entry`() {
        writeDecisions(decision(now - 48 * hour, "old"))

        assertEquals("", build()["AIMI_Decisions_Last24h.jsonl"])
    }

    @Test
    fun `a long journal is streamed in several writes with nothing lost`() {
        val lines = (0 until 5_000).map { decision(now - it * 1_000L, "tick_$it") }
        writeDecisions(*lines.toTypedArray())

        assertEquals(lines.joinToString("") { it + "\n" }, build()["AIMI_Decisions_Last24h.jsonl"])
    }

    @Test
    fun `CSV keeps the header and the newest rows`() {
        val rows = (1..AimiSupportCsvTail.MAX_CSV_ROWS_IN_PACKAGE + 5).map { "row$it" }
        storage.files[storage.file("oapsaimiML2_records.csv").value] = (listOf("header") + rows).joinToString("\n", postfix = "\n")

        val csv = build()["oapsaimiML2_records.csv"]!!.removeSuffix("\n").split("\n")
        assertEquals("header", csv.first())
        assertEquals(AimiSupportCsvTail.MAX_CSV_ROWS_IN_PACKAGE, csv.size - 1)
        assertEquals("row6", csv[1])
        assertEquals(rows.last(), csv.last())
    }

    @Test
    fun `the whole package read back from a real ZIP has the same content`() {
        writeDecisions(decision(now - 30 * hour, "old"), decision(now - hour, "recent"))
        storage.files[storage.file("oapsaimiML2_records.csv").value] = "h\n1\n"
        val sink = InMemoryZipByteSink()
        val zip = AimiStoredZipWriter(sink, AimiPath("/tmp/p.zip"), now, TimeZone.UTC)

        AimiSupportPackageContent.write(zip, "REPORT", storage, NoOpAapsLogger, now)
        zip.finish()

        val entries = ZipTestReader.read(sink.bytes()).associate { it.name to it.content.decodeToString() }
        assertEquals(
            mapOf(
                "Diagnostic_Report.txt" to "REPORT",
                "AIMI_Decisions_Last24h.jsonl" to decision(now - hour, "recent") + "\n",
                "oapsaimiML2_records.csv" to "h\n1\n",
            ),
            entries,
        )
    }

    /**
     * A large journal and a large CSV are read line by line, never as one piece. On iOS a whole
     * file read of the journal (up to about 1 GiB) would end the app.
     */
    @Test
    fun `a large journal and CSV are streamed and never read whole`() {
        val lines = (0 until 40_000).map { decision(now - it * 1_000L, "tick_$it") }
        writeDecisions(*lines.toTypedArray())
        val rows = (1..20_000).map { "row$it,1.0,2.0" }
        storage.files[storage.file("oapsaimiML2_records.csv").value] = (listOf("header") + rows).joinToString("\n", postfix = "\n")

        val entries = build()

        assertEquals(0, storage.wholeFileReads, "no whole file read")
        assertEquals(lines.joinToString("") { it + "\n" }, entries["AIMI_Decisions_Last24h.jsonl"])
        val csv = entries["oapsaimiML2_records.csv"]!!.removeSuffix("\n").split("\n")
        assertEquals("header", csv.first())
        assertEquals(rows.takeLast(AimiSupportCsvTail.MAX_CSV_ROWS_IN_PACKAGE), csv.drop(1))
    }

    @Test
    fun `a CSV without a final line break keeps its last row`() {
        storage.files[storage.file("oapsaimiML2_records.csv").value] = "h\n1\n2"

        assertEquals("h\n1\n2\n", build()["oapsaimiML2_records.csv"])
    }

    @Test
    fun `the number of written entries is answered`() {
        writeDecisions(decision(now - hour, "recent"))
        storage.files[storage.file("oapsaimiML2_records.csv").value] = "h\n1\n"
        val out = RecordingZipOutput()

        assertEquals(3, AimiSupportPackageContent.write(out, "REPORT", storage, NoOpAapsLogger, now))
        assertEquals(0, AimiSupportPackageContent.write(RecordingZipOutput(), "", InMemoryAimiStorage(), NoOpAapsLogger, now))
    }

    /**
     * The storage catches what the line callback throws and only answers `false`. A ZIP write that
     * fails inside the callback must still fail the package, not leave a hole in a "complete" one.
     */
    @Test
    fun `a write failure while the journal is streamed is thrown and stops the walk`() {
        val lines = (0 until 5_000).map { decision(now - it * 1_000L, "tick_$it") }
        writeDecisions(*lines.toTypedArray())
        storage.files[storage.file("oapsaimiML2_records.csv").value] = "h\n1\n"
        // 1 report header, 2 report, 3 journal header, 4 the first journal piece: written in the callback.
        val sink = InMemoryZipByteSink().apply { failOnWrite = 4 }
        val zip = AimiStoredZipWriter(sink, AimiPath("/tmp/p.zip"), now, TimeZone.UTC)

        val error = assertFailsWith<IllegalStateException> {
            AimiSupportPackageContent.write(zip, "REPORT", storage, NoOpAapsLogger, now)
        }
        assertEquals("disk full", error.message)
        assertFailsWith<IllegalStateException> { zip.finish() }
    }
}
