package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Locks the rules of [AimiCorpusPruner], which decides what leaves the on device training corpus.
 *
 * The file these rules act on is what the SMB and basal models learn from, so the tests are written
 * from the losing side: every line that is not clearly a row of the named day has to survive.
 */
class AimiCorpusPrunerTest {

    @TempDir
    lateinit var dir: File

    private val header = "dateStr, bg, iob, smbGiven"
    private lateinit var csv: File
    private lateinit var backup: File
    private lateinit var setAside: File
    private val storage = AimiCorpusPruner.FileStorage

    @BeforeEach
    fun setUp() {
        csv = File(dir, "oapsaimiML2_records.csv")
        backup = File(dir, "backup_20260930_000700.csv")
        setAside = File(dir, AimiCorpusPruner.SET_ASIDE_FILE_NAME)
    }

    private fun csvWith(text: String) = csv.writeText(text)

    private val threeDays = "$header\n" +
        "28/09/2026 23:55,120,1.0,0.3\n" +
        "29/09/2026 00:05,118,1.1,0.2\n" +
        "29/09/2026 23:55,140,0.8,0.5\n" +
        "30/09/2026 00:05,130,0.9,0.4\n"

    // ---- setting a bad day aside ------------------------------------------------------------------

    @Test
    fun `the rows of a bad day leave the corpus and are kept aside`() {
        csvWith(threeDays)

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.REMOVED)
        assertThat(csv.readText()).isEqualTo("$header\n28/09/2026 23:55,120,1.0,0.3\n30/09/2026 00:05,130,0.9,0.4\n")
        assertThat(setAside.readText()).isEqualTo("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n")
    }

    @Test
    fun `a second run for the same day does nothing and makes no second backup`() {
        csvWith(threeDays)
        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")
        val corpusAfterFirst = csv.readText()
        val asideAfterFirst = setAside.readText()
        val secondBackup = File(dir, "backup_20260930_000800.csv")

        val result = AimiCorpusPruner.moveDay(storage, csv, secondBackup, setAside, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.NOTHING_REMOVED)
        assertThat(csv.readText()).isEqualTo(corpusAfterFirst)
        assertThat(setAside.readText()).isEqualTo(asideAfterFirst)
        assertThat(secondBackup.exists()).isFalse()
    }

    @Test
    fun `a second bad day is added under the same header`() {
        csvWith(threeDays)
        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "30/09/2026")

        assertThat(setAside.readText()).isEqualTo(
            "$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n30/09/2026 00:05,130,0.9,0.4\n",
        )
    }

    @Test
    fun `a day already set aside is not added twice`() {
        // The corpus rewrite failed once, so the day is still in the corpus on the next tick.
        csvWith(threeDays)
        setAside.writeText("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n")

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.REMOVED)
        assertThat(setAside.readText()).isEqualTo("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n")
    }

    @Test
    fun `when the rows cannot be set aside the corpus is not touched`() {
        csvWith(threeDays)
        val storage = SetAsideFailingStorage(setAside)

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.SET_ASIDE_FAILED)
        assertThat(csv.readText()).isEqualTo(threeDays)
    }

    @Test
    fun `a set aside file under an old header is kept and a new one started`() {
        csvWith(threeDays)
        val old = "dateStr, bg\n01/09/2026 10:00,150\n"
        setAside.writeText(old)

        AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertThat(File(setAside.path + ".previous").readText()).isEqualTo(old)
        assertThat(setAside.readText()).isEqualTo("$header\n29/09/2026 00:05,118,1.1,0.2\n29/09/2026 23:55,140,0.8,0.5\n")
    }

    // ---- choosing the rows ------------------------------------------------------------------------

    @Test
    fun `rows of the named day go and every other day stays`() {
        val lines = listOf(
            header,
            "28/09/2026 23:55,120,1.0,0.3",
            "29/09/2026 00:05,118,1.1,0.2",
            "29/09/2026 23:55,140,0.8,0.5",
            "30/09/2026 00:05,130,0.9,0.4",
        )
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
            .containsExactly(header, "28/09/2026 23:55,120,1.0,0.3", "30/09/2026 00:05,130,0.9,0.4").inOrder()
    }

    @Test
    fun `header survives even when it is the only line left`() {
        val lines = listOf(header, "29/09/2026 00:05,118,1.1,0.2")
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026")).containsExactly(header)
    }

    @Test
    fun `row with a date that cannot be read is kept`() {
        val lines = listOf(
            header,
            ",118,1.1,0.2",
            "null,118,1.1,0.2",
            "a line with no comma at all",
            "29/09/2026 00:05,118,1.1,0.2",
        )
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
            .containsExactly(header, ",118,1.1,0.2", "null,118,1.1,0.2", "a line with no comma at all").inOrder()
    }

    @Test
    fun `row written under another language is kept`() {
        // The same instant, written by a phone set to another language, is not the text we look for.
        val lines = listOf(header, "9/29/26 12:05 AM,118,1.1,0.2", "2026-09-29 00:05,118,1.1,0.2")
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026")).isEqualTo(lines)
    }

    @Test
    fun `a date that only starts with the day text is kept`() {
        val lines = listOf(header, "29/09/20260 00:05,118,1.1,0.2", "29/09/2026 00:05,118,1.1,0.2")
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026"))
            .containsExactly(header, "29/09/20260 00:05,118,1.1,0.2").inOrder()
    }

    @Test
    fun `a row that carries the day with no time is removed`() {
        val lines = listOf(header, "29/09/2026,118,1.1,0.2")
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "29/09/2026")).containsExactly(header)
    }

    @Test
    fun `an empty day text removes nothing`() {
        val lines = listOf(header, "29/09/2026 00:05,118,1.1,0.2")
        assertThat(AimiCorpusPruner.keepAllButDay(lines, "")).isEqualTo(lines)
    }

    @Test
    fun `an empty file and a header only file are safe`() {
        assertThat(AimiCorpusPruner.keepAllButDay(emptyList(), "29/09/2026")).isEmpty()
        assertThat(AimiCorpusPruner.keepAllButDay(listOf(header), "29/09/2026")).containsExactly(header)
    }

    // ---- the trailing line break ------------------------------------------------------------------

    @Test
    fun `rendered csv always ends with one line break`() {
        assertThat(AimiCorpusPruner.renderCsv(listOf(header, "row"))).isEqualTo("$header\nrow\n")
        assertThat(AimiCorpusPruner.renderCsv(emptyList())).isEqualTo("")
    }

    @Test
    fun `row prefix only adds a line break when one is missing`() {
        assertThat(AimiCorpusPruner.rowPrefix(null)).isEqualTo("")
        assertThat(AimiCorpusPruner.rowPrefix('\n')).isEqualTo("")
        assertThat(AimiCorpusPruner.rowPrefix('4')).isEqualTo("\n")
    }

    @Test
    fun `the row appended after a deletion is its own well formed line`() {
        csvWith("$header\n28/09/2026 23:55,120,1.0,0.3\n29/09/2026 00:05,118,1.1,0.2\n")
        AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        // What the tick writer does next: add one row, with the guard in front of it.
        val newRow = "30/09/2026 00:10,126,1.4,0.6"
        csv.appendText(AimiCorpusPruner.rowPrefix(storage.lastChar(csv)) + newRow + "\n")

        val lines = csv.readLines()
        assertThat(lines).containsExactly(header, "28/09/2026 23:55,120,1.0,0.3", newRow).inOrder()
        lines.drop(1).forEach { assertThat(it.split(",")).hasSize(4) }
    }

    @Test
    fun `an older file with no trailing line break does not glue the next row`() {
        csvWith("$header\n28/09/2026 23:55,120,1.0,0.3")
        val newRow = "30/09/2026 00:10,126,1.4,0.6"
        csv.appendText(AimiCorpusPruner.rowPrefix(storage.lastChar(csv)) + newRow + "\n")

        assertThat(csv.readLines()).containsExactly(header, "28/09/2026 23:55,120,1.0,0.3", newRow).inOrder()
    }

    // ---- the file level clean up ------------------------------------------------------------------

    @Test
    fun `remove day writes the backup before the file`() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n30/09/2026 00:05,130,0.9,0.4\n"
        csvWith(original)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.REMOVED)
        assertThat(result.removedRows).isEqualTo(1)
        assertThat(result.keptRows).isEqualTo(2)
        assertThat(backup.readText()).isEqualTo(original)
        assertThat(csv.readText()).isEqualTo("$header\n30/09/2026 00:05,130,0.9,0.4\n")
    }

    @Test
    fun `remove day leaves the file alone when no row matches`() {
        val original = "$header\n30/09/2026 00:05,130,0.9,0.4\n"
        csvWith(original)

        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.NOTHING_REMOVED)
        assertThat(result.removedRows).isEqualTo(0)
        assertThat(csv.readText()).isEqualTo(original)
        assertThat(backup.exists()).isFalse()
    }

    @Test
    fun `remove day reports a missing file`() {
        val result = AimiCorpusPruner.removeDay(storage, csv, backup, "29/09/2026")
        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.FILE_MISSING)
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun `nothing is rewritten when the backup cannot be made`() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n30/09/2026 00:05,130,0.9,0.4\n"
        csvWith(original)

        val result = AimiCorpusPruner.removeDay(CopyFailingStorage(), csv, backup, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.BACKUP_FAILED)
        assertThat(result.removedRows).isEqualTo(0)
        assertThat(csv.readText()).isEqualTo(original)
    }

    @Test
    fun `a failed rewrite is reported and the backup still holds everything`() {
        val original = "$header\n29/09/2026 00:05,118,1.1,0.2\n"
        csvWith(original)

        val result = AimiCorpusPruner.removeDay(WriteFailingStorage(), csv, backup, "29/09/2026")

        assertThat(result.outcome).isEqualTo(AimiCorpusPruner.Outcome.WRITE_FAILED)
        assertThat(result.isFinal).isFalse()
        assertThat(backup.readText()).isEqualTo(original)
        assertThat(csv.readText()).isEqualTo(original)
    }

    // ---- when to clean up: the bad day rule -------------------------------------------------------

    @Test
    fun `a day under 65 percent in range is a bad day`() {
        assertThat(AimiCorpusPruner.isBadDay(64.9, readingCount = 1440)).isTrue()
        assertThat(AimiCorpusPruner.isBadDay(65.0, readingCount = 1440)).isFalse()
        // 84 % was a bad day under the old 85 % rule. It is an ordinary day now.
        assertThat(AimiCorpusPruner.isBadDay(84.0, readingCount = 1440)).isFalse()
    }

    @Test
    fun `a day with no glucose reading is unknown and never a bad day`() {
        // The TIR code answers 0 % for an empty day, and the warm-up snapshot starts at 0 %.
        assertThat(AimiCorpusPruner.isBadDay(0.0, readingCount = 0)).isFalse()
        assertThat(AimiCorpusPruner.isBadDay(null, readingCount = 1440)).isFalse()
        assertThat(AimiCorpusPruner.isBadDay(Double.NaN, readingCount = 1440)).isFalse()
    }

    @Test
    fun `the bad day window is strictly between 00 05 and 00 10`() {
        assertThat(AimiCorpusPruner.isInsideBadDayWindow(LocalTime.of(0, 5))).isFalse()
        assertThat(AimiCorpusPruner.isInsideBadDayWindow(LocalTime.of(0, 7))).isTrue()
        assertThat(AimiCorpusPruner.isInsideBadDayWindow(LocalTime.of(0, 10))).isFalse()
        assertThat(AimiCorpusPruner.isInsideBadDayWindow(LocalTime.of(12, 7))).isFalse()
    }

    // ---- the delete note --------------------------------------------------------------------------

    @Test
    fun `only a note that starts with delete is a delete note`() {
        assertThat(AimiCorpusPruner.isDeleteNote("delete")).isTrue()
        assertThat(AimiCorpusPruner.isDeleteNote("  Delete 25/10/2026")).isTrue()
        assertThat(AimiCorpusPruner.isDeleteNote("DELETE")).isTrue()
        assertThat(AimiCorpusPruner.isDeleteNote("please delete yesterday")).isFalse()
        assertThat(AimiCorpusPruner.isDeleteNote("lunch, delete later")).isFalse()
        assertThat(AimiCorpusPruner.isDeleteNote("deleted the old pod")).isFalse()
        assertThat(AimiCorpusPruner.isDeleteNote(null)).isFalse()
    }

    @Test
    fun `a delete note with no day means yesterday`() {
        assertThat(AimiCorpusPruner.deleteNoteDay("delete")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Yesterday)
        assertThat(AimiCorpusPruner.deleteNoteDay(" Delete  ")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Yesterday)
    }

    @Test
    fun `a delete note reads the day written as dd mm yyyy`() {
        assertThat(AimiCorpusPruner.deleteNoteDay("delete 25/10/2026"))
            .isEqualTo(AimiCorpusPruner.DeleteNoteDay.OnDate(LocalDate.of(2026, 10, 25)))
        assertThat(AimiCorpusPruner.deleteNoteDay("DELETE   05/01/2026"))
            .isEqualTo(AimiCorpusPruner.DeleteNoteDay.OnDate(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `a delete note whose day cannot be read removes nothing`() {
        assertThat(AimiCorpusPruner.deleteNoteDay("delete 2026-10-25")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Unreadable)
        assertThat(AimiCorpusPruner.deleteNoteDay("delete 31/02/2026")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Unreadable)
        assertThat(AimiCorpusPruner.deleteNoteDay("delete 25/10/2026 and more")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Unreadable)
        assertThat(AimiCorpusPruner.deleteNoteDay("delete everything")).isEqualTo(AimiCorpusPruner.DeleteNoteDay.Unreadable)
    }

    @Test
    fun `a delete note removes only the named day, not the newest rows`() {
        // The old trigger removed the newest 200 lines on every tick. Now only the named day goes.
        val rows = (0 until 300).map { "30/09/2026 ${"%02d".format(it / 60)}:${"%02d".format(it % 60)},120,1.0,0.1" }
        csvWith((listOf(header, "29/09/2026 10:00,118,1.1,0.2") + rows).joinToString("\n", postfix = "\n"))

        val result = AimiCorpusPruner.moveDay(storage, csv, backup, setAside, "29/09/2026")

        assertThat(result.removedRows).isEqualTo(1)
        assertThat(csv.readLines()).hasSize(301)
        assertThat(csv.readText()).endsWith("\n")
    }

    /** Storage whose backup copy always fails, to check nothing is rewritten without a backup. */
    private class CopyFailingStorage : AimiCorpusPruner.Storage by AimiCorpusPruner.FileStorage {

        override fun copy(from: File, to: File): Boolean = false
    }

    /** Storage whose rewrite always fails, to check the backup is still there afterwards. */
    private class WriteFailingStorage : AimiCorpusPruner.Storage by AimiCorpusPruner.FileStorage {

        override fun writeText(file: File, text: String): Boolean = false
    }

    /** Storage that cannot write the set-aside file. */
    private class SetAsideFailingStorage(private val setAside: File) : AimiCorpusPruner.Storage by AimiCorpusPruner.FileStorage {

        override fun writeText(file: File, text: String): Boolean =
            if (file == setAside) false else AimiCorpusPruner.FileStorage.writeText(file, text)

        override fun appendText(file: File, text: String): Boolean =
            if (file == setAside) false else AimiCorpusPruner.FileStorage.appendText(file, text)
    }
}
