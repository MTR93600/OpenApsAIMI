package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.retention.AimiByteReader

/**
 * Reads the lines of a text file one by one, with bounded memory.
 *
 * Memory use is one read buffer plus the longest line, never the whole file. The decision journal
 * can be close to a gigabyte, so a reader that loads the file first is not an option for it.
 *
 * Lines are cut the same way `DirectoryAimiStorage.readLines` cuts them:
 * - a line ends at `\n`; one `\r` just before it is removed, so CRLF and LF files read the same;
 * - a last line without a final line break is still given;
 * - a final line break does not make an extra empty line, and an empty file gives no line;
 * - a `\r` inside a line stays in the line.
 *
 * Each line is decoded as UTF-8 on its own. A `\n` byte never appears inside a multi byte UTF-8
 * character, so no character is ever cut in two.
 */
internal object AimiStreamingLines {

    private const val BUFFER_SIZE = 64 * 1024
    private const val NEWLINE = '\n'.code.toByte()
    private const val CARRIAGE_RETURN = '\r'.code.toByte()

    /**
     * Calls [action] once per line of [path], in file order.
     *
     * Throws when the file cannot be opened or read, and passes on what [action] throws. The file
     * is closed in every case.
     */
    fun forEach(path: AimiPath, action: (String) -> Unit) {
        val reader = AimiByteReader(path)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            val pending = PendingLine()
            var position = 0L
            while (true) {
                val read = reader.readAt(position, buffer, 0, BUFFER_SIZE)
                if (read <= 0) break
                var start = 0
                for (i in 0 until read) {
                    if (buffer[i] != NEWLINE) continue
                    if (pending.size == 0) {
                        action(decodeLine(buffer, start, i))
                    } else {
                        pending.append(buffer, start, i)
                        action(pending.takeLine())
                    }
                    start = i + 1
                }
                if (start < read) pending.append(buffer, start, read)
                position += read
            }
            if (pending.size > 0) action(pending.takeLine())
        } finally {
            reader.close()
        }
    }

    /**
     * The first line of [path], or `null` for an empty file. Reads only up to the first line break,
     * so a file that only grows costs the same every time. Same cut as [forEach].
     * Throws when the file cannot be opened or read.
     */
    fun firstLine(path: AimiPath): String? {
        val reader = AimiByteReader(path)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            val pending = PendingLine()
            var position = 0L
            while (true) {
                val read = reader.readAt(position, buffer, 0, BUFFER_SIZE)
                if (read <= 0) break
                for (i in 0 until read) {
                    if (buffer[i] != NEWLINE) continue
                    if (pending.size == 0) return decodeLine(buffer, 0, i)
                    pending.append(buffer, 0, i)
                    return pending.takeLine()
                }
                pending.append(buffer, 0, read)
                position += read
            }
            return if (pending.size > 0) pending.takeLine() else null
        } finally {
            reader.close()
        }
    }

    /**
     * The last [maxLines] lines of [path], newest first, the same lines [forEach] would give last.
     *
     * Reads the file backwards from its end in blocks and stops as soon as enough line breaks were
     * seen, so the cost depends on [maxLines], not on the file size. The block that is kept always
     * starts just after a line break (or at the file start), so no UTF-8 character is cut.
     * Throws when the file cannot be opened or read.
     */
    fun lastLines(path: AimiPath, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val reader = AimiByteReader(path)
        try {
            val size = reader.length()
            if (size <= 0L) return emptyList()
            // A final line break does not start a new line, so do not count it.
            val last = ByteArray(1)
            val end = if (reader.readAt(size - 1, last, 0, 1) == 1 && last[0] == NEWLINE) size - 1 else size
            val chunks = ArrayList<ByteArray>()
            var start = end
            var breaks = 0
            var cut = -1L
            val buffer = ByteArray(BUFFER_SIZE)
            while (start > 0L && cut < 0L) {
                val length = minOf(BUFFER_SIZE.toLong(), start).toInt()
                val from = start - length
                var got = 0
                while (got < length) {
                    val read = reader.readAt(from + got, buffer, got, length - got)
                    if (read <= 0) error("short read in ${path.value}")
                    got += read
                }
                for (i in length - 1 downTo 0) {
                    if (buffer[i] != NEWLINE) continue
                    breaks++
                    if (breaks == maxLines) {
                        cut = from + i + 1
                        chunks.add(0, buffer.copyOfRange(i + 1, length))
                        break
                    }
                }
                if (cut < 0L) chunks.add(0, buffer.copyOf(length))
                start = from
            }
            val total = chunks.sumOf { it.size }
            val tail = ByteArray(total)
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(tail, at)
                at += chunk.size
            }
            val lines = ArrayList<String>()
            var lineStart = 0
            for (i in 0 until total) {
                if (tail[i] != NEWLINE) continue
                lines.add(decodeLine(tail, lineStart, i))
                lineStart = i + 1
            }
            lines.add(decodeLine(tail, lineStart, total))
            return lines.takeLast(maxLines).asReversed()
        } finally {
            reader.close()
        }
    }

    /** The bytes `[start, end)` as one line: a final `\r` is removed, the rest is UTF-8. */
    private fun decodeLine(bytes: ByteArray, start: Int, end: Int): String {
        var stop = end
        if (stop > start && bytes[stop - 1] == CARRIAGE_RETURN) stop--
        return bytes.decodeToString(start, stop)
    }

    /** The start of a line that goes on into the next read buffer. Grows only as long as the line. */
    private class PendingLine {

        private var bytes = ByteArray(256)
        var size = 0
            private set

        fun append(source: ByteArray, start: Int, end: Int) {
            val length = end - start
            if (size + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
            source.copyInto(bytes, size, start, end)
            size += length
        }

        fun takeLine(): String {
            val line = decodeLine(bytes, 0, size)
            size = 0
            return line
        }
    }
}
