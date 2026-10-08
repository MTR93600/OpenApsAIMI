package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.io.RandomAccessFile

/**
 * Parity harness for [AimiTrainingCsvWriter] against the `java.io.File` writer it replaced.
 *
 * The training CSVs are the corpus the SMB and basal models learn from on the phone, so "the port
 * compiles" is not enough: every case below writes the same rows twice, once through the code that
 * was in `DetermineBasalaimiSMB2` and once through the shared writer, and compares the two files
 * **byte for byte**. The old code is copied in as [oldAppendCsvToFile] rather than referenced,
 * because it no longer exists.
 *
 * Only the primary path is exercised here. The app scoped fallback needs a storage helper that can
 * answer a second directory, and its rule is locked on every target by `AimiTrainingCsvWriterTest`
 * in `commonTest`.
 */
class AimiTrainingCsvWriterFileTest {

    private val helper = mock<AimiStorageHelper>()
    private val storage: AimiStorage = AndroidAimiStorage(helper)
    private val logger = mock<AAPSLogger>()

    private val header = "dateStr,bg,iob,smbGiven\n"
    private val firstRow = "10/04/2026 12:34,120,1.5,0.4"
    private val secondRow = "10/04/2026 12:39,126,1.8,0.2"

    // --- the code this replaced -------------------------------------------------------------------

    /** `DetermineBasalaimiSMB2.lastCharOf`, unchanged. */
    private fun oldLastCharOf(file: File): Char? {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(length - 1)
                reader.read().takeIf { it >= 0 }?.toChar()
            }
        }.getOrNull()
    }

    /** `DetermineBasalaimiSMB2.appendCsvToFile` and `ensureCsvHeaderIsCurrent`, unchanged. */
    private fun oldAppendCsvToFile(file: File, valuesRow: String, checkedPaths: MutableSet<String>) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
            file.appendText(header)
        } else {
            if (checkedPaths.add(file.absolutePath)) {
                runCatching { TrainingCsvHeader.ensureCurrent(storage, AimiPath(file.absolutePath), header) }
            }
        }
        file.appendText(AimiCorpusPruner.rowPrefix(oldLastCharOf(file)) + valuesRow + "\n")
    }

    // --- the harness ------------------------------------------------------------------------------

    /**
     * Runs [rows] through both writers, each on its own file seeded with [startingContent], and
     * answers what the new one stored once the two are proved identical.
     */
    private fun bothWritersAgree(dir: File, startingContent: String?, vararg rows: String): String {
        val oldFile = File(dir, "old/oapsaimiML2_records.csv")
        val newFile = File(dir, "new/oapsaimiML2_records.csv")
        startingContent?.let {
            oldFile.parentFile.mkdirs()
            newFile.parentFile.mkdirs()
            oldFile.writeText(it)
            newFile.writeText(it)
        }

        val checkedPaths = mutableSetOf<String>()
        rows.forEach { oldAppendCsvToFile(oldFile, it, checkedPaths) }

        val writer = AimiTrainingCsvWriter(storage, logger)
        rows.forEach { assertThat(writer.appendRow(AimiPath(newFile.absolutePath), header, it)).isTrue() }

        assertThat(newFile.readBytes()).isEqualTo(oldFile.readBytes())
        return newFile.readText()
    }

    // --- the cases --------------------------------------------------------------------------------

    @Test
    fun a_missing_file_is_created_identically(@TempDir dir: File) {
        val stored = bothWritersAgree(dir, startingContent = null, firstRow)

        assertThat(stored).isEqualTo("dateStr,bg,iob,smbGiven\n10/04/2026 12:34,120,1.5,0.4\n")
    }

    @Test
    fun two_rows_land_identically_under_one_header(@TempDir dir: File) {
        val stored = bothWritersAgree(dir, startingContent = null, firstRow, secondRow)

        assertThat(stored).isEqualTo(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:34,120,1.5,0.4\n" +
                "10/04/2026 12:39,126,1.8,0.2\n"
        )
    }

    @Test
    fun a_file_left_without_a_line_break_gains_one_identically(@TempDir dir: File) {
        val stored = bothWritersAgree(
            dir,
            startingContent = "dateStr,bg,iob,smbGiven\n10/04/2026 12:29,118,1.2,0.1",
            firstRow,
        )

        assertThat(stored).isEqualTo(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,0.1\n" +
                "10/04/2026 12:34,120,1.5,0.4\n"
        )
    }

    @Test
    fun a_stale_header_is_replaced_identically_and_every_row_is_kept(@TempDir dir: File) {
        val stored = bothWritersAgree(
            dir,
            startingContent = "dateStr,bg,iob\n10/04/2026 12:29,118,1.2,0.1\n",
            firstRow,
        )

        assertThat(stored).isEqualTo(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,0.1\n" +
                "10/04/2026 12:34,120,1.5,0.4\n"
        )
    }

    @Test
    fun a_row_whose_last_field_ends_in_a_multi_byte_character_is_stored_identically(@TempDir dir: File) {
        // The last byte of "café" is 0xA9, not a character and not a line break. Both writers must
        // reach the same answer and neither may insert a break.
        val stored = bothWritersAgree(
            dir,
            startingContent = "dateStr,bg,iob,smbGiven\n10/04/2026 12:29,118,1.2,café",
            firstRow,
        )

        assertThat(stored).isEqualTo(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,café\n" +
                "10/04/2026 12:34,120,1.5,0.4\n"
        )
    }
}
