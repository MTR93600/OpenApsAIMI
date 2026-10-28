package app.aaps.plugins.aps.openAPSAIMI.advisor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class CsvTailTest {

    private fun tailOf(text: String, maxRows: Int) = readCsvTail(BufferedReader(StringReader(text)), maxRows)

    @Test
    fun `empty file gives null`() {
        assertThat(tailOf("", 10)).isNull()
    }

    @Test
    fun `header only gives no rows`() {
        assertThat(tailOf("a,b\n", 10)).isEqualTo(CsvTail("a,b", emptyList(), 0))
    }

    @Test
    fun `short file keeps every row in order`() {
        assertThat(tailOf("h\n1\n2\n3\n", 10)).isEqualTo(CsvTail("h", listOf("1", "2", "3"), 3))
    }

    @Test
    fun `long file keeps only the last rows in order`() {
        val text = "h\n" + (1..1000).joinToString("\n")
        assertThat(tailOf(text, 3)).isEqualTo(CsvTail("h", listOf("998", "999", "1000"), 1000))
    }

    @Test
    fun `zero rows asked keeps the header and the count`() {
        assertThat(tailOf("h\n1\n2\n", 0)).isEqualTo(CsvTail("h", emptyList(), 2))
    }

    @Test
    fun `reads a real file`(@TempDir dir: File) {
        val file = File(dir, "x.csv").apply { writeText("h\n1\n2\n3\n") }
        assertThat(readCsvTail(file, 2)).isEqualTo(CsvTail("h", listOf("2", "3"), 3))
    }
}
