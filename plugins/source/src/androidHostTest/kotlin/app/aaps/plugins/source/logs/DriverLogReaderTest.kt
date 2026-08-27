package app.aaps.plugins.source.logs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the log screen is allowed to show, and what it must not lose on the way. */
class DriverLogReaderTest {

    @Test
    fun `keeps only the lines of the driver that was asked for`() {
        val lines = listOf(
            "10:00:00.001 [main] I/LIBRE3: LIBRE3_SESSION: link is up",
            "10:00:00.002 [main] I/DEXCOM_ONEPLUS: DEXCOM_ONEPLUS_SESSION: link is up",
            "10:00:00.003 [main] D/LoopPlugin: something else entirely",
        )

        assertThat(DriverLogReader.keep(lines, DriverLogFilter.LIBRE3.loggerNames, 100))
            .containsExactly("10:00:00.001 [main] I/LIBRE3: LIBRE3_SESSION: link is up")
        assertThat(DriverLogReader.keep(lines, DriverLogFilter.DEXCOM_ONE_PLUS.loggerNames, 100))
            .containsExactly("10:00:00.002 [main] I/DEXCOM_ONEPLUS: DEXCOM_ONEPLUS_SESSION: link is up")
        assertThat(DriverLogReader.keep(lines, DriverLogFilter.ALL.loggerNames, 100)).hasSize(2)
    }

    @Test
    fun `a stack trace stays with the line it belongs to`() {
        val lines = listOf(
            "10:00:00.001 [main] E/LIBRE3: LIBRE3_ERROR: the handshake stopped",
            "java.io.IOException: link closed",
            "\tat app.aaps.plugins.libre3.session.Libre3BleSession.open(Libre3BleSession.kt:120)",
            "10:00:00.002 [main] E/OtherPlugin: not ours",
            "java.lang.IllegalStateException: not ours either",
        )

        val kept = DriverLogReader.keep(lines, DriverLogFilter.LIBRE3.loggerNames, 100)

        assertThat(kept).hasSize(3)
        assertThat(kept[1]).isEqualTo("java.io.IOException: link closed")
        assertThat(kept).doesNotContain("java.lang.IllegalStateException: not ours either")
    }

    @Test
    fun `when there are too many lines the newest are the ones kept`() {
        val lines = (1..10).map { "10:00:00.00$it [main] I/LIBRE3: line $it" }

        val kept = DriverLogReader.keep(lines, DriverLogFilter.LIBRE3.loggerNames, 3)

        assertThat(kept).hasSize(3)
        assertThat(kept.last()).endsWith("line 10")
        assertThat(kept.first()).endsWith("line 8")
    }

    @Test
    fun `a whole small file is read, and a big one only from its end`(@TempDir folder: File) {
        val file = File(folder, DriverLogReader.CURRENT_FILE)
        file.writeText("first line\nsecond line\nthird line\n")

        assertThat(DriverLogReader.tail(file, 1024L)).containsAtLeast("first line", "third line")

        // Reading from an offset lands in the middle of a line, so that half line is dropped.
        val fromTheEnd = DriverLogReader.tail(file, 15L)
        assertThat(fromTheEnd).doesNotContain("first line")
        assertThat(fromTheEnd).contains("third line")
    }

    @Test
    fun `a build that has not written anything yet answers with nothing`(@TempDir folder: File) {
        assertThat(DriverLogReader.read(folder.absolutePath, DriverLogFilter.ALL)).isEmpty()
    }

    @Test
    fun `reads the driver lines back out of a real file`(@TempDir folder: File) {
        File(folder, DriverLogReader.CURRENT_FILE).writeText(
            "10:00:00.001 [main] I/LIBRE3: LIBRE3_NFC: sensor read\n" +
                "10:00:00.002 [main] I/SomethingElse: ignore me\n" +
                "10:00:00.003 [main] I/DEXCOM_ONEPLUS: DEXCOM_ONEPLUS_BG: value taken\n"
        )

        val all = DriverLogReader.read(folder.absolutePath, DriverLogFilter.ALL)

        assertThat(all).hasSize(2)
        assertThat(all.first()).contains("LIBRE3_NFC")
        assertThat(all.last()).contains("DEXCOM_ONEPLUS_BG")
    }
}
