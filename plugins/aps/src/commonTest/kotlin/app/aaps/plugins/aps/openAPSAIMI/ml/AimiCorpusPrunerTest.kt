package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the rules of [AimiCorpusPruner], which decides what leaves the on device training corpus.
 *
 * The file these rules act on is what the SMB and basal models learn from, so the tests are written
 * from the losing side: every line that is not clearly a row of the named day has to survive.
 *
 * Test names are camelCase on purpose. A backtick name holding a comma does not compile on Kotlin
 * Native, and this source set is built for the iOS simulator as well.
 */
class AimiCorpusPrunerTest {

    private val header = "dateStr, bg, iob, smbGiven"
    private val csv = AimiPath("/aimi/oapsaimiML2_records.csv")
    private val backup = AimiPath("/aimi/backup_20260930_000700.csv")

    private fun storageWith(text: String): InMemoryAimiStorage =
        InMemoryAimiStorage().also { it.files[csv.value] = text }

    // ---- choosing the rows ----------------------------------------------------------------------

    @Test
    fun rowsOfTheNamedDayGoAndEveryOtherDayStays() {
        val lines = listOf(
            header,
            "28/09/2026 23:55,120,1.0,0.3",
            "29/09/2026 00:05,118,1.1,0.2",
            "29/09/2026 23:55,140,0.8,0.5",
            "30/09/2026 00:05,130,0.9,0.4",
        )
        val kept = AimiCorpusPruner.keepAllButDay(lines, "29/09/2026")
        assertEquals(
            listOf(header, "28/09/2026 23:55,120,1.0,0.3", "30/09/2026 00:05,130,0.9,0.4"),
            kept,
        )
    }

    @Test
    fun headerSurvivesEvenWhenItIsTheOnlyLineLeft() {
        val lines = listOf(header, "29/09/2026 00:05,118,1.1,0.2")
        assertEquals(listOf(header), AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
    }

    @Test
    fun rowWithADateThatCannotBeReadIsKept() {
        val lines = listOf(
            header,
            ",118,1.1,0.2",
            "null,118,1.1,0.2",
            "a line with no comma at all",
            "29/09/2026 00:05,118,1.1,0.2",
        )
        val kept = AimiCorpusPruner.keepAllButDay(lines, "29/09/2026")
        assertEquals(
            listOf(header, ",118,1.1,0.2", "null,118,1.1,0.2", "a line with no comma at all"),
            kept,
        )
    }

    @Test
    fun rowWrittenUnderAnotherLanguageIsKept() {
        // The same instant, written by a phone set to another language, is not the text we look for.
        val lines = listOf(header, "9/29/26 12:05 AM,118,1.1,0.2", "2026-09-29 00:05,118,1.1,0.2")
        assertEquals(lines, AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
    }

    @Test
    fun aDateThatOnlyStartsWithTheDayTextIsKept() {
        val lines = listOf(header, "29/09/20260 00:05,118,1.1,0.2", "29/09/2026 00:05,118,1.1,0.2")
        assertEquals(
            listOf(header, "29/09/20260 00:05,118,1.1,0.2"),
            AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"),
        )
    }

    @Test
    fun aRowThatCarriesTheDayWithNoTimeIsRemoved() {
        val lines = listOf(header, "29/09/2026,118,1.1,0.2")
        assertEquals(listOf(header), AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
    }

    @Test
    fun anEmptyDayTextRemovesNothing() {
        val lines = listOf(header, "29/09/2026 00:05,118,1.1,0.2")
        assertEquals(lines, AimiCorpusPruner.keepAllButDay(lines, ""))
    }

    @Test
    fun anEmptyFileAndAHeaderOnlyFileAreSafe() {
        assertEquals(emptyList(), AimiCorpusPruner.keepAllButDay(emptyList(), "29/09/2026"))
        assertEquals(listOf(header), AimiCorpusPruner.keepAllButDay(listOf(header), "29/09/2026"))
    }

    @Test
    fun theCountRuleStillDropsTheNewestLinesAndOnlyAboveTheCount() {
        val lines = (0 until 5).map { "row$it" }
        assertEquals(listOf("row0", "row1"), AimiCorpusPruner.keepAllButNewest(lines, 3))
        assertEquals(lines, AimiCorpusPruner.keepAllButNewest(lines, 5))
        assertEquals(lines, AimiCorpusPruner.keepAllButNewest(lines, 6))
    }

    // ---- the trailing line break ----------------------------------------------------------------

    @Test
    fun renderedCsvAlwaysEndsWithOneLineBreak() {
        assertEquals("$header\nrow\n", AimiCorpusPruner.renderCsv(listOf(header, "row")))
        assertEquals("", AimiCorpusPruner.renderCsv(emptyList()))
    }

    @Test
    fun rowPrefixOnlyAddsALineBreakWhenOneIsMissing() {
        assertEquals("", AimiCorpusPruner.rowPrefix(null))
        assertEquals("", AimiCorpusPruner.rowPrefix('\n'))
        assertEquals("\n", AimiCorpusPruner.rowPrefix('4'))
    }

    @Test
    fun theRowAppendedAfterADeletionIsItsOwnWellFormedLine() {
        val storage = storageWith(
            "$header\n" +
                "28/09/2026 23:55,120,1.0,0.3\n" +
                "29/09/2026 00:05,118,1.1,0.2\n",
        )
        AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        // What the tick writer does next: add one row, with the guard in front of it.
        val stored = storage.readText(csv).orEmpty()
        val newRow = "30/09/2026 00:10,126,1.4,0.6"
        storage.appendText(csv, AimiCorpusPruner.rowPrefix(stored.lastOrNull()) + newRow + "\n")

        val lines = storage.readLines(csv)
        assertEquals(listOf(header, "28/09/2026 23:55,120,1.0,0.3", newRow), lines)
        lines.drop(1).forEach { assertEquals(4, it.split(",").size) }
    }

    @Test
    fun anOlderFileWithNoTrailingLineBreakDoesNotGlueTheNextRow() {
        val storage = storageWith("$header\n28/09/2026 23:55,120,1.0,0.3")
        val stored = storage.readText(csv).orEmpty()
        val newRow = "30/09/2026 00:10,126,1.4,0.6"
        storage.appendText(csv, AimiCorpusPruner.rowPrefix(stored.lastOrNull()) + newRow + "\n")

        assertEquals(listOf(header, "28/09/2026 23:55,120,1.0,0.3", newRow), storage.readLines(csv))
    }

    // ---- the file level clean up ----------------------------------------------------------------

    @Test
    fun removeDayWritesTheBackupBeforeTheFile() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n30/09/2026 00:05,130,0.9,0.4\n"
        val storage = storageWith(original)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.REMOVED, result.outcome)
        assertEquals(1, result.removedRows)
        assertEquals(2, result.keptRows)
        assertEquals(original, storage.files[backup.value])
        assertEquals("$header\n30/09/2026 00:05,130,0.9,0.4\n", storage.files[csv.value])
    }

    @Test
    fun removeDayLeavesTheFileAloneWhenNoRowMatches() {
        val original = "$header\n30/09/2026 00:05,130,0.9,0.4\n"
        val storage = storageWith(original)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.NOTHING_REMOVED, result.outcome)
        assertEquals(0, result.removedRows)
        assertEquals(original, storage.files[csv.value])
        assertNull(storage.files[backup.value])
    }

    @Test
    fun removeDayReportsAMissingFile() {
        val storage = InMemoryAimiStorage()
        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")
        assertEquals(AimiCorpusPruner.Outcome.FILE_MISSING, result.outcome)
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun nothingIsRewrittenWhenTheBackupCannotBeMade() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n30/09/2026 00:05,130,0.9,0.4\n"
        val inner = storageWith(original)
        val storage = CopyFailingStorage(inner)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.BACKUP_FAILED, result.outcome)
        assertEquals(0, result.removedRows)
        assertEquals(original, inner.files[csv.value])
    }

    @Test
    fun aFailedRewriteIsReportedAndTheBackupStillHoldsEverything() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n"
        val inner = storageWith(original)
        val storage = WriteFailingStorage(inner)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.WRITE_FAILED, result.outcome)
        assertEquals(original, inner.files[backup.value])
    }

    @Test
    fun removeNewestKeepsTheCountRuleAndBacksUpTheFile() {
        val original = "$header\nrow1\nrow2\nrow3\n"
        val storage = storageWith(original)

        val result = AimiCorpusPruner.removeNewest(storage, csv, backup, 2)

        assertEquals(AimiCorpusPruner.Outcome.REMOVED, result.outcome)
        assertEquals(2, result.removedRows)
        assertEquals(original, storage.files[backup.value])
        assertEquals("$header\nrow1\n", storage.files[csv.value])
    }

    @Test
    fun removeNewestDoesNothingWhenTheFileIsNotLongerThanTheCount() {
        val original = "$header\nrow1\n"
        val storage = storageWith(original)

        val result = AimiCorpusPruner.removeNewest(storage, csv, backup, 2)

        assertEquals(AimiCorpusPruner.Outcome.NOTHING_REMOVED, result.outcome)
        assertEquals(original, storage.files[csv.value])
        assertNull(storage.files[backup.value])
    }

    /** Storage whose backup copy always fails, to check nothing is rewritten without a backup. */
    private class CopyFailingStorage(inner: AimiStorage) : AimiStorage by inner {

        override fun copy(from: AimiPath, to: AimiPath): Boolean = false
    }

    /** Storage whose rewrite always fails, to check the backup is still there afterwards. */
    private class WriteFailingStorage(inner: AimiStorage) : AimiStorage by inner {

        override fun writeText(path: AimiPath, text: String): Boolean = false
    }
}
