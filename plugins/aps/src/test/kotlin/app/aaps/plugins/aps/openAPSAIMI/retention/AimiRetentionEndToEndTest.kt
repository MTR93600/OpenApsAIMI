package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.advisor.data.JsonlTailReader
import app.aaps.plugins.aps.openAPSAIMI.comparison.ComparisonCsvParser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The other ~100 tests in this package prove `AimiCutPlanner` computes the right byte split. None
 * of them prove a TRIMMED file is still usable by the real code that reads it afterwards. This test
 * does, using the real consumer classes (not re-implementations):
 * - [JsonlTailReader], the advisor UI's tail view, against a trimmed `AIMI_Decisions.jsonl`.
 * - [ComparisonCsvParser], against a trimmed `comparison_aimi_smb.csv`.
 *
 * It also proves the one property the whole retention feature rests on: no line is lost anywhere -
 * what the archive holds, concatenated with the trimmed live file, reproduces the original file
 * exactly.
 *
 * `HormonitorReader` (the other in-app viewer) is not covered here: it needs an Android `Context`
 * to resolve its directory, which a plain JVM unit test cannot provide without Robolectric. Per the
 * task, that is left out rather than faked.
 */
class AimiRetentionEndToEndTest {

    private val now = 1_758_240_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun manager(dir: File) = AimiRetentionManager(dir, {}, { now })

    private fun archivedConcat(dir: File, prefix: String): String =
        AimiArchive.directory(dir).listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) }
            .sortedBy { it.name } // "yyyy-MM" sorts lexicographically the same as chronologically
            .joinToString("") { GZIPInputStream(it.inputStream()).use { s -> s.readBytes().toString(Charsets.UTF_8) } }

    // --- AIMI_Decisions.jsonl / JsonlTailReader ---

    /** A few KB each, like the real lines; "notes" pads the size without the real, larger payload. */
    private fun decisionLine(id: Int, ts: Long): String {
        val notes = "x".repeat(2500)
        return """{"event_id":"e-$id","timestamp":$ts,"trigger":"loop",""" +
            """"baseline_state":{"bg":120,"iob":1.5,"cob":0,"notes":"$notes"},""" +
            """"adjustments":{"smb":0.1,"basal_rate":0.8},""" +
            """"outcome":{"delivered":true,"eventual_bg":110}}"""
    }

    @Test
    fun trimmedDecisionsFileStillServesTheSameTailAndLosesNoLineToArchive(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        val rule = AimiRetentionPolicy.ruleFor("AIMI_Decisions.jsonl")!!

        var id = 0
        val lines = mutableListOf<String>()
        // Several older months, all well outside the 7-day hot window - these get archived.
        listOf(5L, 4L, 3L, 2L, 1L).forEach { monthsAgo ->
            val base = now - monthsAgo * 30 * day
            repeat(70) { i -> lines += decisionLine(id++, base + i * 6 * 60 * 60 * 1000L) }
        }
        // The oldest line inside the 7-day window - this is the line trim() must keep first.
        val firstInWindowLine = decisionLine(id, now - 149 * 5 * 60 * 1000L)
        lines += firstInWindowLine
        id++
        // The rest of the hot window: 149 more lines, so the kept tail is comfortably over 120
        // lines and the JsonlTailReader(120) comparison below never touches the archived part.
        repeat(149) { i -> lines += decisionLine(id++, now - (148 - i) * 5 * 60 * 1000L) }

        val originalContent = lines.joinToString("\n") + "\n"
        file.writeText(originalContent)
        assertThat(lines).hasSize(500)

        val tailBefore = JsonlTailReader.readTailLines(file, 120)
        assertThat(tailBefore).hasSize(120)

        val rewritten = manager(dir).trim(file, rule)
        assertThat(rewritten).isTrue()

        // The real consumer sees exactly the same tail as before the trim.
        val tailAfter = JsonlTailReader.readTailLines(file, 120)
        assertThat(tailAfter).isEqualTo(tailBefore)
        assertThat(file.readText().lineSequence().first()).isEqualTo(firstInWindowLine)

        // No line is lost anywhere: archive + trimmed live file reproduce the original exactly.
        assertThat(archivedConcat(dir, "AIMI_Decisions") + file.readText()).isEqualTo(originalContent)

        // The archives are readable on their own (this file has no header).
        val members = AimiArchive.directory(dir).listFiles().orEmpty().filter { it.name.startsWith("AIMI_Decisions") }
        assertThat(members).isNotEmpty()
        members.forEach { member -> GZIPInputStream(member.inputStream()).use { it.readBytes() } }
    }

    // --- comparison_aimi_smb.csv / ComparisonCsvParser ---

    // The real header AimiSmbComparator writes, copied verbatim from its `logFile` initializer.
    private val csvHeader =
        "SchemaVersion,Timestamp,Date,BG,Delta,ShortAvgDelta,LongAvgDelta,IOB,COB," +
            "AIMI_Rate,AIMI_SMB,AIMI_Duration,AIMI_EventualBG,AIMI_TargetBG," +
            "SMB_Rate,SMB_SMB,SMB_Duration,SMB_EventualBG,SMB_TargetBG," +
            "Diff_Rate,Diff_SMB,Diff_EventualBG," +
            "MaxIOB,MaxBasal,MicroBolus_Allowed," +
            "AIMI_Insulin_30min,SMB_Insulin_30min,Cumul_Diff," +
            "AIMI_Active,SMB_Active,Both_Active," +
            "AIMI_UAM_Last,SMB_UAM_Last," +
            "Verdict,Artifact_Flag,Diff_Sign," +
            "AIMI_Flag_MealPriority,AIMI_Flag_Refractory,AIMI_Flag_Throttle,AIMI_Flag_CBF," +
            "SMB_Flag_Refractory,SMB_Flag_Throttle,SMB_Flag_CBF," +
            "Context_MealRise,Context_COB_Active,Context_UAM_Bias,SMB_LastBolusAgeMin," +
            "Reason_AIMI,Reason_SMB"

    /**
     * A realistically-shaped row: 49 columns, matching AimiSmbComparator's real header count.
     *
     * `ComparisonCsvParser.parseLine`, as it stands today, reads column 0 as the entry's
     * `timestamp` and column 2 as `bg` - it is off by one against AimiSmbComparator's own header
     * (which has `SchemaVersion` at column 0 and `BG` at column 3). That mismatch is a pre-existing
     * bug unrelated to retention - see the closeout report - and is not fixed here; this helper just
     * puts real numbers in the two columns the parser actually requires (0 and 2) so a row survives
     * parsing, matching the ACTUAL contract this test exercises rather than the header's nominal one.
     */
    private fun csvRow(uniqueId: Long, bg: Double): String {
        val values = MutableList(49) { "0" }
        values[0] = uniqueId.toString()
        values[1] = "2026-09-01 10:00:00"
        values[2] = bg.toString()
        values[47] = "\"loop\""
        values[48] = "\"loop\""
        return values.joinToString(",")
    }

    @Test
    fun trimmedComparisonCsvStillParsesToTheTailOfTheOriginalEntries(@TempDir dir: File) {
        val file = File(dir, "comparison_aimi_smb.csv")
        val rule = AimiRetentionPolicy.ruleFor("comparison_aimi_smb.csv")!!
        val totalRows = rule.hotLines + 100 // exceeds the rule's line budget

        val csvLines = (1..totalRows).map { i -> csvRow(uniqueId = i.toLong(), bg = 100.0 + i) }
        val originalContent = csvHeader + "\n" + csvLines.joinToString("\n") + "\n"
        file.writeText(originalContent)

        val entriesBefore = ComparisonCsvParser().parse(file)
        assertThat(entriesBefore).hasSize(totalRows)

        val rewritten = manager(dir).trim(file, rule)
        assertThat(rewritten).isTrue()

        // The real consumer still parses the trimmed file, and gets exactly the tail of what it
        // parsed before. This pins the header-preservation contract: if the header were dropped,
        // the first surviving data row would be read as a header and skipped instead, shifting
        // every entry after it and shrinking the list by one.
        val entriesAfter = ComparisonCsvParser().parse(file)
        assertThat(entriesAfter).isEqualTo(entriesBefore.takeLast(rule.hotLines))

        // No line is lost anywhere: archive + trimmed live file reproduce the original exactly.
        // Unlike the header-less decisions file, trim() deliberately writes the header at the top of
        // BOTH the archive member and the live file (see AimiRetentionManagerTrimTest's
        // "theHeaderStaysAtTheTopOfTheLiveFileAndOpensTheArchive"), so the live file's own copy is
        // stripped before concatenating, or the header would count twice.
        val liveWithoutDuplicateHeader = file.readText().removePrefix("$csvHeader\n")
        assertThat(archivedConcat(dir, "comparison_aimi_smb") + liveWithoutDuplicateHeader).isEqualTo(originalContent)

        // The archives are readable on their own, and the header is their first line.
        val members = AimiArchive.directory(dir).listFiles().orEmpty().filter { it.name.startsWith("comparison_aimi_smb") }
        assertThat(members).isNotEmpty()
        members.forEach { member ->
            val content = GZIPInputStream(member.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            assertThat(content.lineSequence().first()).isEqualTo(csvHeader)
        }
    }
}
