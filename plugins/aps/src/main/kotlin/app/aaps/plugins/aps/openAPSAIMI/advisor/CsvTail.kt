package app.aaps.plugins.aps.openAPSAIMI.advisor

import java.io.BufferedReader
import java.io.File

/**
 * The header and the last rows of a CSV file.
 *
 * @property header the first line of the file
 * @property rows the last rows after the header, oldest first, at most the asked number
 * @property totalRows how many rows the file has after the header
 */
internal data class CsvTail(val header: String, val rows: List<String>, val totalRows: Int)

/**
 * Reads the header and the last [maxRows] rows of [file], one line at a time.
 *
 * Only [maxRows] lines are kept in memory, so a CSV file of many MB is not loaded whole.
 * Returns null when the file is empty.
 */
internal fun readCsvTail(file: File, maxRows: Int): CsvTail? =
    file.bufferedReader(Charsets.UTF_8).use { readCsvTail(it, maxRows) }

/** Same as [readCsvTail] for a file, for any reader. */
internal fun readCsvTail(reader: BufferedReader, maxRows: Int): CsvTail? {
    val header = reader.readLine() ?: return null
    val keep = ArrayDeque<String>(maxRows.coerceAtLeast(0))
    var total = 0
    while (true) {
        val line = reader.readLine() ?: break
        total++
        if (maxRows <= 0) continue
        if (keep.size == maxRows) keep.removeFirst()
        keep.addLast(line)
    }
    return CsvTail(header, keep.toList(), total)
}
