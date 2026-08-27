package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Reads the last [maxLines] complete lines from a text file without loading the whole file.
 * Returns lines in **newest-first** order (same iteration order as `readLines().takeLast(n).asReversed()`).
 */
object JsonlTailReader {

    private const val CHUNK_SIZE = 8192
    private const val MAX_TAIL_BYTES = 16 * 1024 * 1024L

    fun readTailLines(file: File, maxLines: Int): List<String> {
        if (maxLines <= 0 || !file.exists() || !file.canRead()) return emptyList()
        val fileLength = file.length()
        if (fileLength == 0L) return emptyList()

        val newestFirst = ArrayList<String>(maxLines)
        RandomAccessFile(file, "r").use { raf ->
            var filePos = fileLength
            var carry = ""
            var bytesScanned = 0L

            while (filePos > 0 && newestFirst.size < maxLines && bytesScanned < MAX_TAIL_BYTES) {
                val readSize = min(CHUNK_SIZE.toLong(), filePos).toInt()
                filePos -= readSize
                bytesScanned += readSize
                raf.seek(filePos)
                val chunk = ByteArray(readSize)
                val bytesRead = raf.read(chunk)
                if (bytesRead <= 0) break

                val text = String(chunk, 0, bytesRead, Charsets.UTF_8) + carry
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
        }
        return newestFirst
    }
}
