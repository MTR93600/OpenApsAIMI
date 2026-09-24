package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AimiLineScannerTest {

    private fun collect(file: File): List<Triple<Long, Long, String>> {
        val out = mutableListOf<Triple<Long, Long, String>>()
        AimiLineScanner.forEachLine(file) { start, end, prefix ->
            out.add(Triple(start, end, String(prefix, Charsets.UTF_8)))
            true
        }
        return out
    }

    @Test
    fun reportsOffsetsThatIncludeTheNewline(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("abc\nde\n")

        val lines = collect(file)

        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo(Triple(0L, 4L, "abc"))
        assertThat(lines[1]).isEqualTo(Triple(4L, 7L, "de"))
    }

    @Test
    fun doesNotReportATrailingLineWithoutANewline(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("abc\nhalf-written")

        val lines = collect(file)

        assertThat(lines).hasSize(1)
        assertThat(lines[0].third).isEqualTo("abc")
    }

    @Test
    fun truncatesThePrefixButKeepsTheOffsetsExact(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val long = "x".repeat(AimiLineScanner.PREFIX_LIMIT + 500)
        file.writeText("$long\ntail\n")

        val lines = collect(file)

        assertThat(lines[0].third).hasLength(AimiLineScanner.PREFIX_LIMIT)
        assertThat(lines[0].second).isEqualTo(long.length + 1L)
        assertThat(lines[1].third).isEqualTo("tail")
    }

    @Test
    fun stopsWhenTheCallbackReturnsFalse(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("one\ntwo\nthree\n")
        var seen = 0

        AimiLineScanner.forEachLine(file) { _, _, _ ->
            seen++
            false
        }

        assertThat(seen).isEqualTo(1)
    }

    @Test
    fun handlesALineThatSpansSeveralReadBuffers(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val big = "y".repeat(200_000)
        file.writeText("$big\nlast\n")

        val lines = collect(file)

        assertThat(lines).hasSize(2)
        assertThat(lines[1]).isEqualTo(Triple(big.length + 1L, big.length + 6L, "last"))
    }
}
