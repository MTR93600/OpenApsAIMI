package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Walks a telemetry file line by line and reports each line's byte range.
 *
 * The decision file is 2.10 GB and a single line can be hundreds of kilobytes, so nothing here
 * materialises a whole line, let alone a whole file. The caller gets the byte offsets plus at most
 * [PREFIX_LIMIT] bytes from the start of the line, which is all any timestamp extractor needs.
 */
internal object AimiLineScanner {

    /** How many bytes of each line the caller sees. The timestamp keys all sit in the first few. */
    const val PREFIX_LIMIT = 4096

    private const val BUFFER_SIZE = 64 * 1024
    private const val NEWLINE = '\n'.code.toByte()

    /**
     * Calls [onLine] once per complete line, with `endExclusive` pointing just past the newline.
     *
     * A trailing line with no newline is **not** reported. The writers append with `appendText`, so
     * a file read while a write is in flight can end mid-line; leaving that line out keeps a partial
     * record on the live side of any cut instead of archiving half of it.
     *
     * Returning `false` from [onLine] stops the walk.
     */
    fun forEachLine(file: File, onLine: (start: Long, endExclusive: Long, prefix: ByteArray) -> Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        val prefix = ByteArrayOutputStream(256)
        var lineStart = 0L
        var absolute = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) return
                var i = 0
                while (i < read) {
                    if (buffer[i] == NEWLINE) {
                        val end = absolute + i + 1
                        if (!onLine(lineStart, end, prefix.toByteArray())) return
                        prefix.reset()
                        lineStart = end
                    } else if (prefix.size() < PREFIX_LIMIT) {
                        prefix.write(buffer[i].toInt())
                    }
                    i++
                }
                absolute += read
            }
        }
    }
}
