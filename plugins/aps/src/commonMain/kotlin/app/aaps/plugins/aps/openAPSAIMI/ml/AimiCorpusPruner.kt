package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Removes rows from the AIMI training CSV, and never more than it was asked to.
 *
 * These files are the corpus the SMB and the basal models train on, on the phone, not a log. A row
 * deleted here is a row the model will never see again, so every rule below is written to keep a row
 * when it is not sure: a line whose date cannot be read stays, the first line always stays, and
 * nothing is written at all when the backup could not be made.
 *
 * Two ways of choosing rows live here, because the code has two callers with two different meanings:
 *
 * - [keepAllButDay] is the "bad day" clean up. It removes the rows of one named day, which is what
 *   the user is told happened.
 * - [keepAllButNewest] is the older count based clean up, kept unchanged for the therapy note
 *   trigger, which asks for a deletion without naming a day.
 *
 * ## How a day is matched
 *
 * Column 0 of a row is `dateStr` (see [SmbRefinementFeatureSchema.trainingCsvColumnNames]) and the
 * writer fills it with `DateUtil.dateAndTimeString`, that is the short date **in the phone's own
 * language and time zone** followed by the time. There is no fixed pattern to parse, so this class
 * does not try to parse one: the caller formats the wanted day with the very same date function and
 * passes the text in, and a row matches when its date part is exactly that text. Text that was
 * written under another language, or that cannot be read at all, therefore never matches, and the
 * row is kept.
 *
 * ## The trailing line break
 *
 * [renderCsv] always ends with a line break, and [rowPrefix] adds one before a new row when the file
 * on disk does not end with one. Before this, a rewrite ended without a line break while the writer
 * appended `row + "\n"`, so the next row was glued onto the last kept one. The reader
 * ([AimiSmbCorpus.buildTrainingCorpus]) drops a line with more fields than the header, so nothing
 * was learned wrongly, but two rows were lost on every clean up and the broken line stayed in the
 * corpus for good.
 */
internal object AimiCorpusPruner {

    /** What a clean up did to the file. */
    enum class Outcome {

        /** The file is not there, so there was nothing to clean up. */
        FILE_MISSING,

        /** The file was read and no row had to go. It was not rewritten and no backup was made. */
        NOTHING_REMOVED,

        /** The backup could not be written, so the file was left exactly as it was. */
        BACKUP_FAILED,

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
    )

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

    /**
     * [lines] without the newest [count] lines, or [lines] unchanged when there are not more than
     * [count] of them.
     *
     * This is the rule the file has always used for the therapy note trigger, kept as it was: the
     * newest lines are the last ones, and the size check means the header can never be one of them.
     */
    fun keepAllButNewest(lines: List<String>, count: Int): List<String> =
        if (lines.size <= count) lines else lines.dropLast(count)

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

    /**
     * Removes the rows of [dayText] from [csv], keeping a copy of the whole file at [backup] first.
     *
     * Nothing is written when no row matches, and nothing is written when the backup failed.
     */
    fun removeDay(storage: AimiStorage, csv: AimiPath, backup: AimiPath, dayText: String): Result =
        rewrite(storage, csv, backup) { lines -> keepAllButDay(lines, dayText) }

    /**
     * Removes the newest [count] lines of [csv], keeping a copy of the whole file at [backup] first.
     *
     * Nothing is written when the file does not hold more than [count] lines.
     */
    fun removeNewest(storage: AimiStorage, csv: AimiPath, backup: AimiPath, count: Int): Result =
        rewrite(storage, csv, backup) { lines -> keepAllButNewest(lines, count) }

    private fun rewrite(
        storage: AimiStorage,
        csv: AimiPath,
        backup: AimiPath,
        choose: (List<String>) -> List<String>,
    ): Result {
        if (!storage.exists(csv)) return Result(Outcome.FILE_MISSING, removedRows = 0, keptRows = 0)
        val lines = storage.readLines(csv)
        val kept = choose(lines)
        val removed = lines.size - kept.size
        if (removed <= 0) return Result(Outcome.NOTHING_REMOVED, removedRows = 0, keptRows = lines.size)
        if (!storage.copy(csv, backup)) return Result(Outcome.BACKUP_FAILED, removedRows = 0, keptRows = lines.size)
        if (!storage.writeText(csv, renderCsv(kept))) {
            return Result(Outcome.WRITE_FAILED, removedRows = 0, keptRows = lines.size)
        }
        return Result(Outcome.REMOVED, removedRows = removed, keptRows = kept.size)
    }
}
