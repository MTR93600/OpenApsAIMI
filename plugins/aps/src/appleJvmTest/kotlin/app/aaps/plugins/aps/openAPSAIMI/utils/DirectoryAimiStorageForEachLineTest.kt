package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DirectoryAimiStorage.forEachLine] on a real file, on the JVM and on the iOS simulator.
 *
 * It streams the file now instead of reading it whole, so these tests pin that the lines are still
 * exactly the ones [DirectoryAimiStorage.readLines] gives: CRLF and LF, a last line without a line
 * break, lines longer than the read buffer, UTF-8 text, an empty file and a missing file.
 */
class DirectoryAimiStorageForEachLineTest {

    private val root = aimiTestTempDirectory().trimEnd('/') + "/aimi-foreachline-" + Random.nextLong().toULong()
    private val storage = DirectoryAimiStorage(root)
    private val files = aimiLocalFiles()

    @AfterTest
    fun cleanUp() {
        files.delete(storage.file("journal.jsonl").value)
        files.delete(root)
    }

    private fun write(text: String): AimiPath {
        val path = storage.file("journal.jsonl")
        assertTrue(files.writeText(path.value, text))
        return path
    }

    private fun collect(path: AimiPath): Pair<Boolean, List<String>> {
        val lines = mutableListOf<String>()
        val complete = storage.forEachLine(path) { lines += it }
        return complete to lines
    }

    @Test
    fun `a file of several megabytes with CRLF and LF and no final line break reads line by line`() {
        val expected = mutableListOf<String>()
        val text = StringBuilder()
        var i = 0
        while (text.length < 6_000_000) {
            val line = when (i % 500) {
                // Longer than the 64 KiB read buffer, so it spans several reads.
                7 -> "long_$i:" + "x".repeat(150_000)
                11 -> ""
                13 -> "été 日本 $i"
                17 -> "inner\rreturn $i"
                else -> """{"timestamp":$i,"tag":"tick_$i"}"""
            }
            expected += line
            text.append(line).append(if (i % 2 == 0) "\r\n" else "\n")
            i++
        }
        expected += "last line with no break"
        text.append("last line with no break")
        val path = write(text.toString())

        val (complete, lines) = collect(path)

        assertTrue(complete)
        assertEquals(expected.size, lines.size)
        assertEquals(expected, lines)
        assertEquals(storage.readLines(path), lines, "the same lines as readLines")
    }

    @Test
    fun `a final line break gives no extra empty line`() {
        assertEquals(true to listOf("a", "b"), collect(write("a\nb\n")))
        assertEquals(true to listOf("a", ""), collect(write("a\r\n\r\n")))
        assertEquals(true to listOf(""), collect(write("\n")))
    }

    @Test
    fun `an empty file gives no line and answers true`() {
        assertEquals(true to emptyList(), collect(write("")))
    }

    @Test
    fun `a missing file gives no line and answers false`() {
        assertEquals(false to emptyList(), collect(storage.file("no_such_file.jsonl")))
    }

    @Test
    fun `a throw from the callback stops the walk and answers false`() {
        val path = write("a\nb\nc\n")
        val seen = mutableListOf<String>()

        val complete = storage.forEachLine(path) { line ->
            seen += line
            if (line == "b") throw IllegalStateException("stop")
        }

        assertFalse(complete)
        assertEquals(listOf("a", "b"), seen)
    }
}
