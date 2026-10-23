package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

/**
 * The Android support package file: a finished one is kept, an aborted one is deleted, because a
 * partial package still holds health data.
 */
class AndroidAimiSupportZipFactoryTest {

    @Test
    fun a_finished_package_is_kept_and_reads_back(@TempDir dir: File) {
        val zip = AndroidAimiSupportZipFactory(dir).create("package.zip")
        zip.beginEntry("Diagnostic_Report.txt")
        zip.write("report".encodeToByteArray())
        zip.endEntry()
        val size = zip.finish()

        val file = File(zip.path.value)
        assertThat(size).isEqualTo(file.length())
        ZipFile(file).use { assertThat(it.getEntry("Diagnostic_Report.txt")).isNotNull() }
        zip.abort()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun abort_deletes_the_partial_file(@TempDir dir: File) {
        val zip = AndroidAimiSupportZipFactory(dir).create("package.zip")
        zip.beginEntry("AIMI_Decisions_Last24h.jsonl")
        zip.write(ByteArray(200_000) { 'x'.code.toByte() })

        zip.abort()

        assertThat(File(zip.path.value).exists()).isFalse()
        // A second abort does nothing.
        zip.abort()
    }
}
