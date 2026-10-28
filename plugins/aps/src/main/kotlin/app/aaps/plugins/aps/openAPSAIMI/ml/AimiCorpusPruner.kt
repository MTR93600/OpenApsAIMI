package app.aaps.plugins.aps.openAPSAIMI.ml

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Removes rows from the AIMI training CSV, and never more than it was asked to.
 *
 * These files are the corpus the SMB and the basal models train on, on the phone, not a log. A row
 * deleted here is a row the model will never see again, so every rule below is written to keep a row
 * when it is not sure: a line whose date cannot be read stays, the first line always stays, and
 * nothing is written at all when the backup could not be made.
 *
 * Rows are never thrown away: [moveDay] writes the rows of the day to a separate file
 * ([SET_ASIDE_FILE_NAME]) first, so the model stops learning from them but the user can still read
 * them, and put them back by hand. Two callers use it:
 *
 * - the "bad day" clean up, once a night, for yesterday, when the time in range was under
 *   [BAD_DAY_TIR_THRESHOLD_PCT] (see [isBadDay]);
 * - a therapy note that starts with "delete", once per note, for the day written in the note
 *   (see [deleteNoteDay]).
 *
 * ## How a day is matched
 *
 * Column 0 of a row is `dateStr`, and the writer fills it with `DateUtil.dateAndTimeString`, that is
 * the short date **in the phone's own language and time zone** followed by the time. There is no
 * fixed pattern to parse, so this class does not try to parse one: the caller formats the wanted day
 * with the very same date function (`DateUtil.dateString`) and passes the text in, and a row matches
 * when its date part is exactly that text. Text that was written under another language, or that
 * cannot be read at all, therefore never matches, and the row is kept.
 *
 * ## The trailing line break
 *
 * [renderCsv] always ends with a line break, and [rowPrefix] adds one before a new row when the file
 * on disk does not end with one. Before this, a rewrite ended without a line break while the writer
 * appended `row + "\n"`, so the next row was glued onto the last kept one.
 */
internal object AimiCorpusPruner {

    /**
     * A day whose time in range (65-180 mg/dL) is under this is taken out of the training corpus.
     *
     * 65 %, not 85 %: at 85 % most ordinary days went too, and the model was left with the easy ones
     * (owner decision 2026-10-26).
     */
    const val BAD_DAY_TIR_THRESHOLD_PCT = 65.0

    /** Where the rows of a removed day go, next to the corpus, instead of being deleted. */
    const val SET_ASIDE_FILE_NAME = "oapsaimiML2_records_excluded.csv"

    /** The bad day clean up runs only strictly between these two local times. */
    val BAD_DAY_WINDOW_START: LocalTime = LocalTime.of(0, 5)
    val BAD_DAY_WINDOW_END: LocalTime = LocalTime.of(0, 10)

    /** What a clean up did to the file. */
    enum class Outcome {

        /** The file is not there, so there was nothing to clean up. */
        FILE_MISSING,

        /** The file was read and no row had to go. It was not rewritten and no backup was made. */
        NOTHING_REMOVED,

        /** The backup could not be written, so the file was left exactly as it was. */
        BACKUP_FAILED,

        /** The rows could not be written to the set-aside file, so the corpus was left exactly as it was. */
        SET_ASIDE_FAILED,

        /** The backup was made but the rewrite failed. The backup still holds every row. */
        WRITE_FAILED,

        /** Rows were removed and the rest was written back. */
        REMOVED,
    }

    /** Result of a clean up: what happened, and how many rows went and stayed. */
    data class Result(
        val outcome: Outcome,
        val removedRows: Int,
        val keptRows: Int,
    ) {

        /** True when the work is finished, so the same request must not be tried again. */
        val isFinal: Boolean
            get() = outcome == Outcome.REMOVED || outcome == Outcome.NOTHING_REMOVED || outcome == Outcome.FILE_MISSING
    }

    /** The few file operations the pruner needs. Tests replace one of them to make it fail. */
    interface Storage {

        fun exists(file: File): Boolean
        fun readLines(file: File): List<String>
        fun readFirstLine(file: File): String?
        fun lastChar(file: File): Char?
        fun copy(from: File, to: File): Boolean
        fun writeText(file: File, text: String): Boolean
        fun appendText(file: File, text: String): Boolean
    }

    /** [Storage] on the real file system. Every failure is answered with `false`, never thrown. */
    object FileStorage : Storage {

        override fun exists(file: File): Boolean = file.exists()

        override fun readLines(file: File): List<String> = file.readLines(Charsets.UTF_8)

        override fun readFirstLine(file: File): String? =
            runCatching { file.bufferedReader(Charsets.UTF_8).use { it.readLine() } }.getOrNull()

        override fun lastChar(file: File): Char? = runCatching {
            if (!file.exists() || file.length() == 0L) return@runCatching null
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 1)
                raf.read().takeIf { it >= 0 }?.toChar()
            }
        }.getOrNull()

        override fun copy(from: File, to: File): Boolean = runCatching {
            from.copyTo(to, overwrite = true)
            true
        }.getOrDefault(false)

        override fun writeText(file: File, text: String): Boolean = runCatching {
            file.writeText(text, Charsets.UTF_8)
            true
        }.getOrDefault(false)

        override fun appendText(file: File, text: String): Boolean = runCatching {
            file.appendText(text, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    // ---- when to clean up -------------------------------------------------------------------------

    /**
     * True when the day must leave the corpus: its time in range is known and under
     * [BAD_DAY_TIR_THRESHOLD_PCT].
     *
     * A day with no glucose reading at all ([readingCount] 0), or with no percentage, is unknown, not
     * bad. The TIR code answers 0 % for an empty day, and the warm-up snapshot also starts at 0 %
     * before the first read. Without this rule a sensor gap, or the first ticks after an app start,
     * looked like the worst day possible.
     */
    fun isBadDay(inRangePct: Double?, readingCount: Int): Boolean {
        if (readingCount <= 0) return false
        if (inRangePct == null || !inRangePct.isFinite()) return false
        return inRangePct < BAD_DAY_TIR_THRESHOLD_PCT
    }

    /** True strictly between [BAD_DAY_WINDOW_START] and [BAD_DAY_WINDOW_END]. */
    fun isInsideBadDayWindow(time: LocalTime): Boolean =
        time.isAfter(BAD_DAY_WINDOW_START) && time.isBefore(BAD_DAY_WINDOW_END)

    /** What a "delete" note asks for. */
    sealed class DeleteNoteDay {

        /** The note names no day: remove yesterday. */
        object Yesterday : DeleteNoteDay()

        /** The note names this day. */
        data class OnDate(val date: LocalDate) : DeleteNoteDay()

        /** The text after "delete" is not a day we can read. Nothing is removed. */
        object Unreadable : DeleteNoteDay()
    }

    private val NOTE_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    /**
     * True when [note] is a corpus delete request: it STARTS with "delete" (any case) followed by
     * the end of the text or a space.
     *
     * Only the start counts. Before, "delete" anywhere in a note was enough, so a note about
     * something else could remove training rows.
     */
    fun isDeleteNote(note: String?): Boolean {
        val text = note?.trim() ?: return false
        if (!text.startsWith(DELETE_KEYWORD, ignoreCase = true)) return false
        return text.length == DELETE_KEYWORD.length || text[DELETE_KEYWORD.length].isWhitespace()
    }

    /**
     * The day a delete note asks for. "delete" alone means yesterday, "delete 25/10/2026" means that
     * day (dd/MM/yyyy). Anything else after "delete" is [DeleteNoteDay.Unreadable]: we do not guess.
     */
    fun deleteNoteDay(note: String): DeleteNoteDay {
        val rest = note.trim().drop(DELETE_KEYWORD.length).trim()
        if (rest.isEmpty()) return DeleteNoteDay.Yesterday
        return try {
            DeleteNoteDay.OnDate(LocalDate.parse(rest, NOTE_DATE_FORMAT))
        } catch (_: DateTimeParseException) {
            DeleteNoteDay.Unreadable
        }
    }

    private const val DELETE_KEYWORD = "delete"

    // ---- choosing the rows ------------------------------------------------------------------------

    /**
     * True when [line] is a data row of the day written as [dayText].
     *
     * The date part is column 0 up to the first comma. It matches when it is [dayText] itself, or
     * [dayText] followed by a space and the time. Anything else - another day, another language, an
     * empty field, a line that is not a row at all - is not a match, so the row is kept.
     */
    fun isRowOfDay(line: String, dayText: String): Boolean {
        if (dayText.isEmpty()) return false
        val dateField = line.substringBefore(',').trim()
        if (dateField.isEmpty()) return false
        if (dateField == dayText) return true
        return dateField.length > dayText.length &&
            dateField.startsWith(dayText) &&
            dateField[dayText.length] == ' '
    }

    /**
     * [lines] without the data rows of [dayText]. The first line is the header and always stays.
     *
     * An empty file stays empty and a file holding only a header is returned unchanged.
     */
    fun keepAllButDay(lines: List<String>, dayText: String): List<String> {
        if (lines.size <= 1) return lines
        val kept = ArrayList<String>(lines.size)
        kept.add(lines.first())
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (!isRowOfDay(line, dayText)) kept.add(line)
        }
        return kept
    }

    /** [lines] as CSV text, always ending with one line break so the next row starts on its own. */
    fun renderCsv(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")

    /**
     * What to write in front of a new row, given the last character already stored ([lastChar], or
     * `null` when the file is empty or its last character could not be read).
     *
     * It is a line break only when the stored text does not already end with one. This is what keeps
     * an older file, written before [renderCsv] ended with a line break, from gluing its last row to
     * the next one.
     */
    fun rowPrefix(lastChar: Char?): String = if (lastChar == null || lastChar == '\n') "" else "\n"

    // ---- the file level clean up ------------------------------------------------------------------

    /**
     * Removes the rows of [dayText] from [csv], keeping a copy of the whole file at [backup] first.
     *
     * Nothing is written, and no backup is made, when no row matches. Nothing is written when the
     * backup failed.
     */
    fun removeDay(storage: Storage, csv: File, backup: File, dayText: String): Result {
        if (!storage.exists(csv)) return Result(Outcome.FILE_MISSING, removedRows = 0, keptRows = 0)
        val lines = storage.readLines(csv)
        val kept = keepAllButDay(lines, dayText)
        val removed = lines.size - kept.size
        if (removed <= 0) return Result(Outcome.NOTHING_REMOVED, removedRows = 0, keptRows = lines.size)
        if (!storage.copy(csv, backup)) return Result(Outcome.BACKUP_FAILED, removedRows = 0, keptRows = lines.size)
        if (!storage.writeText(csv, renderCsv(kept))) {
            return Result(Outcome.WRITE_FAILED, removedRows = 0, keptRows = lines.size)
        }
        return Result(Outcome.REMOVED, removedRows = removed, keptRows = kept.size)
    }

    /**
     * Moves the rows of [dayText] from [csv] to [setAside], then takes them out of [csv].
     *
     * The order is what keeps a row from being lost: the rows are written to [setAside] first, and
     * [csv] is only rewritten when that worked (and after the usual full copy at [backup]).
     *
     * A second call for the same day does nothing: the rows are no longer in [csv], so no backup is
     * made and nothing is written.
     *
     * [setAside] starts with the corpus header, so it reads like the corpus. Rows of a day that is
     * already in it are not added again: a rewrite that failed is tried again on the next tick, and the
     * day must not appear twice. When the corpus header has changed since [setAside] was started, the
     * old file is kept as `<name>.previous` and a new one is started, because rows under two headers
     * in one file could not be read back.
     */
    fun moveDay(storage: Storage, csv: File, backup: File, setAside: File, dayText: String): Result {
        if (!storage.exists(csv)) return Result(Outcome.FILE_MISSING, removedRows = 0, keptRows = 0)
        val lines = storage.readLines(csv)
        if (lines.size <= 1) return Result(Outcome.NOTHING_REMOVED, removedRows = 0, keptRows = lines.size)
        val header = lines.first()
        val dayRows = lines.drop(1).filter { isRowOfDay(it, dayText) }
        if (dayRows.isEmpty()) return Result(Outcome.NOTHING_REMOVED, removedRows = 0, keptRows = lines.size)
        if (!writeSetAside(storage, setAside, header, dayRows, dayText)) {
            return Result(Outcome.SET_ASIDE_FAILED, removedRows = 0, keptRows = lines.size)
        }
        return removeDay(storage, csv, backup, dayText)
    }

    private fun writeSetAside(storage: Storage, setAside: File, header: String, rows: List<String>, dayText: String): Boolean {
        val existingHeader = if (storage.exists(setAside)) storage.readFirstLine(setAside) else null
        if (existingHeader == header) {
            val alreadyThere = storage.readLines(setAside).drop(1).any { isRowOfDay(it, dayText) }
            if (alreadyThere) return true
            return storage.appendText(setAside, rowPrefix(storage.lastChar(setAside)) + renderCsv(rows))
        }
        val previous = File(setAside.path + ".previous")
        if (existingHeader != null && !storage.copy(setAside, previous)) return false
        return storage.writeText(setAside, renderCsv(listOf(header) + rows))
    }
}
