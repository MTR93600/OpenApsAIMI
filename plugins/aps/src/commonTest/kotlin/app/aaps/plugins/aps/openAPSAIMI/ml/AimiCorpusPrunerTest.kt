package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.aimiEpochAtLocalTime
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ---- setting a bad day aside ---------------------------------------------------------------

    private val setAside = AimiPath("/aimi/oapsaimiML2_records_excluded.csv")

    private val threeDays = "$header\n" +
        "28/09/2026 23:55,120,1.0,0.3\n" +
        "29/09/2026 00:05,118,1.1,0.2\n" +
        "29/09/2026 23:55,140,0.8,0.5\n" +
        "30/09/2026 00:05,130,0.9,0.4\n"

    @Test
    fun theRowsOfABadDayLeaveTheCorpusAndAreKeptAside() {
        val storage = storageWith(threeDays)

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.REMOVED, result.outcome)
        assertEquals("$header\n28/09/2026 23:55,120,1.0,0.3\n30/09/2026 00:05,130,0.9,0.4\n", storage.files[csv.value])
        assertEquals("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n", storage.files[setAside.value])
    }

    @Test
    fun aSecondRunForTheSameDayDoesNothingAndMakesNoSecondBackup() {
        val storage = storageWith(threeDays)
        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")
        val corpusAfterFirst = storage.files[csv.value]
        val asideAfterFirst = storage.files[setAside.value]
        val secondBackup = AimiPath("/aimi/backup_20260930_000800.csv")

        val result = AimiCorpusPruner.moveDay(storage, csv, secondBackup, setAside, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.NOTHING_REMOVED, result.outcome)
        assertTrue(result.isFinal)
        assertEquals(corpusAfterFirst, storage.files[csv.value])
        assertEquals(asideAfterFirst, storage.files[setAside.value])
        assertNull(storage.files[secondBackup.value])
    }

    @Test
    fun aSecondBadDayIsAddedUnderTheSameHeader() {
        val storage = storageWith(threeDays)
        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "30/09/2026")

        assertEquals(
            "$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n30/09/2026 00:05,130,0.9,0.4\n",
            storage.files[setAside.value],
        )
    }

    @Test
    fun aDayAlreadySetAsideIsNotAddedTwice() {
        // The corpus rewrite failed once, so the day is still in the corpus on the next tick.
        val storage = storageWith(threeDays)
        storage.files[setAside.value] = "$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n"

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.REMOVED, result.outcome)
        assertEquals("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n", storage.files[setAside.value])
    }

    @Test
    fun whenTheRowsCannotBeSetAsideTheCorpusIsNotTouched() {
        val storage = storageWith(threeDays)
        storage.deniedPaths += setAside.value

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertEquals(AimiCorpusPruner.Outcome.SET_ASIDE_FAILED, result.outcome)
        assertFalse(result.isFinal)
        assertEquals(threeDays, storage.files[csv.value])
    }

    @Test
    fun aSetAsideFileUnderAnOldHeaderIsKeptAndANewOneStarted() {
        val storage = storageWith(threeDays)
        val old = "dateStr, bg\n01/09/2026 10:00,150\n"
        storage.files[setAside.value] = old

        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertEquals(old, storage.files["${setAside.value}.previous"])
        assertEquals("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n", storage.files[setAside.value])
    }

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

    // ---- when to clean up: the bad day rule -----------------------------------------------------

    @Test
    fun aDayUnder65PercentInRangeIsABadDay() {
        assertTrue(AimiCorpusPruner.isBadDay(64.9, readingCount = 1440))
        assertFalse(AimiCorpusPruner.isBadDay(65.0, readingCount = 1440))
        // 84 % was a bad day under the old 85 % rule. It is an ordinary day now.
        assertFalse(AimiCorpusPruner.isBadDay(84.0, readingCount = 1440))
    }

    @Test
    fun aDayWithNoGlucoseReadingIsUnknownAndNeverABadDay() {
        // The TIR code answers 0 % for an empty day, and the warm-up snapshot starts at 0 %.
        assertFalse(AimiCorpusPruner.isBadDay(0.0, readingCount = 0))
        assertFalse(AimiCorpusPruner.isBadDay(null, readingCount = 1440))
        assertFalse(AimiCorpusPruner.isBadDay(Double.NaN, readingCount = 1440))
    }

    @Test
    fun theBadDayWindowIsStrictlyBetween0005And0010() {
        val utc = TimeZone.UTC
        assertFalse(AimiCorpusPruner.isInsideBadDayWindow(aimiEpochAtLocalTime(0, 5, zone = utc), utc))
        assertTrue(AimiCorpusPruner.isInsideBadDayWindow(aimiEpochAtLocalTime(0, 7, zone = utc), utc))
        assertFalse(AimiCorpusPruner.isInsideBadDayWindow(aimiEpochAtLocalTime(0, 10, zone = utc), utc))
        assertFalse(AimiCorpusPruner.isInsideBadDayWindow(aimiEpochAtLocalTime(12, 7, zone = utc), utc))
    }

    // ---- the delete note ------------------------------------------------------------------------

    @Test
    fun onlyANoteThatStartsWithDeleteIsADeleteNote() {
        assertTrue(AimiCorpusPruner.isDeleteNote("delete"))
        assertTrue(AimiCorpusPruner.isDeleteNote("  Delete 25/10/2026"))
        assertTrue(AimiCorpusPruner.isDeleteNote("DELETE"))
        assertFalse(AimiCorpusPruner.isDeleteNote("please delete yesterday"))
        assertFalse(AimiCorpusPruner.isDeleteNote("lunch, delete later"))
        assertFalse(AimiCorpusPruner.isDeleteNote("deleted the old pod"))
        assertFalse(AimiCorpusPruner.isDeleteNote(null))
    }

    @Test
    fun aDeleteNoteWithNoDayMeansYesterday() {
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Yesterday, AimiCorpusPruner.deleteNoteDay("delete"))
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Yesterday, AimiCorpusPruner.deleteNoteDay(" Delete  "))
    }

    @Test
    fun aDeleteNoteReadsTheDayWrittenAsDdMmYyyy() {
        assertEquals(
            AimiCorpusPruner.DeleteNoteDay.OnDate(LocalDate(2026, 10, 25)),
            AimiCorpusPruner.deleteNoteDay("delete 25/10/2026"),
        )
        assertEquals(
            AimiCorpusPruner.DeleteNoteDay.OnDate(LocalDate(2026, 1, 5)),
            AimiCorpusPruner.deleteNoteDay("DELETE   05/01/2026"),
        )
    }

    @Test
    fun aDeleteNoteWhoseDayCannotBeReadRemovesNothing() {
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Unreadable, AimiCorpusPruner.deleteNoteDay("delete 2026-10-25"))
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Unreadable, AimiCorpusPruner.deleteNoteDay("delete 31/02/2026"))
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Unreadable, AimiCorpusPruner.deleteNoteDay("delete 25/10/2026 and more"))
        assertEquals(AimiCorpusPruner.DeleteNoteDay.Unreadable, AimiCorpusPruner.deleteNoteDay("delete everything"))
    }

    @Test
    fun aDeleteNoteRemovesOnlyTheNamedDayNotTheNewestRows() {
        // The old trigger removed the newest 200 lines on every tick. Now only the named day goes.
        val rows = (0 until 300).map {
            "30/09/2026 ${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')},120,1.0,0.1"
        }
        val storage = storageWith((listOf(header, "29/09/2026 10:00,118,1.1,0.2") + rows).joinToString("\n", postfix = "\n"))

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertEquals(1, result.removedRows)
        assertEquals(301, storage.readLines(csv).size)
        assertTrue(storage.files[csv.value].orEmpty().endsWith("\n"))
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
