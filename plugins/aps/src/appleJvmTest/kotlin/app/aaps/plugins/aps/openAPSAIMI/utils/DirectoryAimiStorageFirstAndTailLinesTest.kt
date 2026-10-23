package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DirectoryAimiStorage.readFirstLine] and [DirectoryAimiStorage.readTailLines] on a real file, on the
 * JVM and on the iOS simulator.
 *
 * They no longer read the whole file (the first one runs on every loop tick against CSVs that only
 * grow), so these tests pin that they still give exactly what [DirectoryAimiStorage.readLines] gives.
 */
class DirectoryAimiStorageFirstAndTailLinesTest {

    private val root = aimiTestTempDirectory().trimEnd('/') + "/aimi-firsttail-" + Random.nextLong().toULong()
    private val storage = DirectoryAimiStorage(root)
    private val files = aimiLocalFiles()

    @AfterTest
    fun cleanUp() {
        files.delete(storage.file("data.csv").value)
        files.delete(root)
    }

    private fun write(text: String): AimiPath {
        val path = storage.file("data.csv")
        assertTrue(files.writeText(path.value, text))
        return path
    }

    /** First line and tails of every size must match the whole-file reader. */
    private fun assertSameAsReadLines(text: String) {
        val path = write(text)
        val all = storage.readLines(path)
        assertEquals(all.firstOrNull(), storage.readFirstLine(path), "first line of ${text.take(40)}")
        for (n in listOf(1, 2, 3, 5, 50, all.size, all.size + 3)) {
            if (n <= 0) continue
            assertEquals(all.takeLast(n).asReversed(), storage.readTailLines(path, n), "tail $n of ${text.take(40)}")
        }
    }

    @Test
    fun `small files with LF and CRLF and edge cases match the whole file reader`() {
        assertSameAsReadLines("a\nb\nc\n")
        assertSameAsReadLines("a\nb\nc")
        assertSameAsReadLines("a\r\nb\r\nc\r\n")
        assertSameAsReadLines("header\r\n1\n2\r\n3")
        assertSameAsReadLines("only one line")
        assertSameAsReadLines("only one line\n")
        assertSameAsReadLines("\n")
        assertSameAsReadLines("\n\n\n")
        assertSameAsReadLines("a\n\nb\n\n")
        assertSameAsReadLines("été,日本\nsecond é\n")
        assertSameAsReadLines("inner\rreturn\nnext")
    }

    @Test
    fun `a large file with lines longer than the read buffer matches the whole file reader`() {
        val text = StringBuilder()
        var i = 0
        while (text.length < 3_000_000) {
            val line = when (i % 300) {
                5 -> "long_$i," + "x".repeat(100_000)
                9 -> ""
                12 -> "été 日本 $i"
                else -> "$i,${i * 2},${i % 7}"
            }
            text.append(line).append(if (i % 3 == 0) "\r\n" else "\n")
            i++
        }
        text.append("last row no break")
        assertSameAsReadLines(text.toString())
    }

    @Test
    fun `an empty or missing file gives no line`() {
        val empty = write("")
        assertNull(storage.readFirstLine(empty))
        assertEquals(emptyList(), storage.readTailLines(empty, 10))
        val missing = storage.file("missing.csv")
        assertNull(storage.readFirstLine(missing))
        assertEquals(emptyList(), storage.readTailLines(missing, 10))
    }

    @Test
    fun `zero or negative max lines gives no line`() {
        val path = write("a\nb\n")
        assertEquals(emptyList(), storage.readTailLines(path, 0))
        assertEquals(emptyList(), storage.readTailLines(path, -1))
    }
}
