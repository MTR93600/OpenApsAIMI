package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.plugins.aps.openAPSAIMI.retention.AimiByteReader
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlin.math.min

/**
 * Reads the last [maxLines] complete lines from a text file without loading the whole file.
 * Returns lines in **newest-first** order (same iteration order as `readLines().takeLast(n).asReversed()`).
 *
 * Reads backward through [AimiByteReader]; [AimiStorage.readTailLines] documents the same
 * contract for callers that only need the shared storage half.
 */
object JsonlTailReader {

    private const val CHUNK_SIZE = 8192
    private const val MAX_TAIL_BYTES = 16 * 1024 * 1024L

    fun readTailLines(path: AimiPath, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val reader = runCatching { AimiByteReader(path) }.getOrNull() ?: return emptyList()
        try {
            val fileLength = reader.length()
            if (fileLength == 0L) return emptyList()

            val newestFirst = ArrayList<String>(maxLines)
            var filePos = fileLength
            var carry = ""
            var bytesScanned = 0L

            while (filePos > 0 && newestFirst.size < maxLines && bytesScanned < MAX_TAIL_BYTES) {
                val readSize = min(CHUNK_SIZE.toLong(), filePos).toInt()
                filePos -= readSize
                bytesScanned += readSize
                val chunk = ByteArray(readSize)
                var got = 0
                while (got < readSize) {
                    val read = reader.readAt(filePos + got, chunk, got, readSize - got)
                    if (read <= 0) break
                    got += read
                }
                if (got <= 0) break

                val text = chunk.decodeToString(0, got) + carry
                carry = ""
                var end = text.length
                while (end > 0 && newestFirst.size < maxLines) {
                    val newlineIdx = text.lastIndexOf('\n', end - 1)
                    if (newlineIdx == -1) {
                        carry = text.substring(0, end) + carry
                        break
                    }
                    val lineEnd = if (newlineIdx > 0 && text[newlineIdx - 1] == '\r') {
                        newlineIdx - 1
                    } else {
                        newlineIdx
                    }
                    val lineStart = newlineIdx + 1
                    if (lineStart < end) {
                        val line = text.substring(lineStart, end)
                        if (line.isNotEmpty()) {
                            newestFirst.add(line)
                        }
                    }
                    end = lineEnd
                }
            }

            if (carry.isNotEmpty() && newestFirst.size < maxLines) {
                newestFirst.add(carry)
            }
            return newestFirst
        } finally {
            reader.close()
        }
    }
}
