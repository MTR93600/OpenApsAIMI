package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JsonlTailReaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun readTailLines_returns_newest_first_without_loading_whole_file() {
        val file = File(tempDir, "test.jsonl")
        file.writeText((1..200).joinToString("\n") { """{"line":$it}""" })

        val tail = JsonlTailReader.readTailLines(file, maxLines = 5)

        assertThat(tail).hasSize(5)
        assertThat(tail[0]).contains("\"line\":200")
        assertThat(tail[4]).contains("\"line\":196")
    }

    @Test
    fun readTailLines_matches_readLines_takeLast_for_small_file() {
        val file = File(tempDir, "small.jsonl")
        val lines = (1..12).map { """{"n":$it}""" }
        file.writeText(lines.joinToString("\n"))

        val expected = lines.takeLast(4).asReversed()
        val actual = JsonlTailReader.readTailLines(file, maxLines = 4)

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun readTailLines_handles_windows_line_endings() {
        val file = File(tempDir, "crlf.jsonl")
        file.writeBytes("{\"a\":1}\r\n{\"a\":2}\r\n{\"a\":3}\r\n".toByteArray(Charsets.UTF_8))

        val tail = JsonlTailReader.readTailLines(file, maxLines = 2)

        assertThat(tail).containsExactly("{\"a\":3}", "{\"a\":2}")
    }

    @Test
    fun readTailLines_empty_or_missing_file_returns_empty() {
        assertThat(JsonlTailReader.readTailLines(File(tempDir, "missing.jsonl"), 10)).isEmpty()
        val empty = File(tempDir, "empty.jsonl")
        empty.writeText("")
        assertThat(JsonlTailReader.readTailLines(empty, 10)).isEmpty()
    }
}
