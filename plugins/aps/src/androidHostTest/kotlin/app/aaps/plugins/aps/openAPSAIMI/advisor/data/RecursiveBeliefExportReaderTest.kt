package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The last recursive belief export the Profile Advisor shows.
 *
 * The reader has to survive whatever is in the log, because it runs when the screen opens: a line
 * that is not JSON at all, a line that carries the word but not the block, and a log that has never
 * held an export. Each of those has its own test here, because each of them returns null the same
 * way and a mistake in one would be invisible.
 *
 * The fixture lines copy the real writer: the root `timestamp` and `adjustments` that
 * `DetermineBasalAIMI2` writes, and the `recursive_belief` block of `UnfoldExporter.toJsonObject`.
 */
class RecursiveBeliefExportReaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun a_missing_file_gives_null() {
        val export = RecursiveBeliefExportReader.loadLastExport(File(tempDir, "no_such_file.jsonl"))

        assertThat(export).isNull()
    }

    @Test
    fun a_log_without_any_export_gives_null() {
        val file = writeLines(listOf(lineWithoutExport(1L), lineWithoutExport(2L)))

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export).isNull()
    }

    @Test
    fun the_block_itself_is_returned_not_the_whole_line() {
        val file = writeLines(listOf(exportLine(1L, authority = "SOFT")))

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export).isNotNull()
        // The root keys must be gone - what comes back is the recursive_belief object.
        assertThat(export!!.containsKey("timestamp")).isFalse()
        assertThat(export.containsKey("adjustments")).isFalse()
        assertThat(export.optJsonObjectCompat("resolution")?.optStringCompat("release_authority"))
            .isEqualTo("SOFT")
    }

    @Test
    fun the_newest_export_wins() {
        val file = writeLines(
            listOf(
                exportLine(1L, authority = "NONE"),
                exportLine(2L, authority = "SOFT"),
                exportLine(3L, authority = "FULL"),
            ),
        )

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export?.optJsonObjectCompat("resolution")?.optStringCompat("release_authority"))
            .isEqualTo("FULL")
    }

    @Test
    fun a_line_that_is_not_json_is_skipped_and_an_older_export_is_used() {
        val file = writeLines(
            listOf(
                exportLine(1L, authority = "SOFT"),
                """{"timestamp": 2, "adjustments": {"recursive_belief": {""",
            ),
        )

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export?.optJsonObjectCompat("resolution")?.optStringCompat("release_authority"))
            .isEqualTo("SOFT")
    }

    @Test
    fun a_line_naming_the_block_without_carrying_it_is_skipped() {
        // Valid JSON, and it contains the word, so the cheap text filter lets it through - only the
        // parsed lookup can tell it apart.
        val decoy = """{"timestamp": 2, "adjustments": {"note": "recursive_belief was skipped"}}"""
        val file = writeLines(listOf(exportLine(1L, authority = "SOFT"), decoy))

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export?.optJsonObjectCompat("resolution")?.optStringCompat("release_authority"))
            .isEqualTo("SOFT")
    }

    @Test
    fun a_line_with_no_adjustments_at_all_is_skipped() {
        val decoy = """{"timestamp": 2, "recursive_belief": {"shadow_only": false}}"""
        val file = writeLines(listOf(exportLine(1L, authority = "SOFT"), decoy))

        val export = RecursiveBeliefExportReader.loadLastExport(file)

        assertThat(export?.optJsonObjectCompat("resolution")?.optStringCompat("release_authority"))
            .isEqualTo("SOFT")
    }

    private fun writeLines(lines: List<String>): File =
        File(tempDir, "AIMI_Decisions.jsonl").apply { writeText(lines.joinToString(separator = "\n", postfix = "\n")) }

    private fun exportLine(timestampMs: Long, authority: String): String =
        """{"timestamp": $timestampMs, "adjustments": {"recursive_belief": {""" +
            """"version": 1, "shadow_only": false, "authority_applied": true, "paradoxes": [], """ +
            """"resolution": {"smb_demand_u": 0.35, "release_authority": "$authority"}}}}"""

    private fun lineWithoutExport(timestampMs: Long): String =
        """{"timestamp": $timestampMs, "adjustments": {"t3c_runtime_ownership": {"mode": "NATIVE_APPLIED"}}}"""
}
