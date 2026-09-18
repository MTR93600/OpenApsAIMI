package app.aaps.plugins.aps.openAPSAIMI.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File

/**
 * Covers the seven [AimiStorage] members added for the sweep in a later lot: [AimiStorage.delete],
 * [AimiStorage.replaceText], [AimiStorage.readTailLines], [AimiStorage.sizeBytes], [AimiStorage.list],
 * [AimiStorage.copy] and [AimiStorage.lastModifiedMs].
 *
 * None of these touch [AimiStorageHelper] - they operate on an [AimiPath] the caller already
 * resolved - so the helper here is a mock that is never asked to do anything.
 */
class AndroidAimiStorageTest {

    private val storage: AimiStorage = AndroidAimiStorage(mock<AimiStorageHelper>())

    private fun pathOf(file: File): AimiPath = AimiPath(file.absolutePath)

    // --- delete ---------------------------------------------------------------------------------

    @Test
    fun delete_on_a_missing_file_answers_true(@TempDir dir: File) {
        val missing = File(dir, "never_written.json")

        assertThat(storage.delete(pathOf(missing))).isTrue()
    }

    @Test
    fun delete_removes_an_existing_file(@TempDir dir: File) {
        val file = File(dir, "weights.json").apply { writeText("stale") }

        assertThat(storage.delete(pathOf(file))).isTrue()
        assertThat(file.exists()).isFalse()
    }

    // --- replaceText ------------------------------------------------------------------------------

    @Test
    fun replaceText_replaces_existing_content(@TempDir dir: File) {
        val file = File(dir, "weights.json").apply { writeText("old model") }

        val ok = storage.replaceText(pathOf(file), "new model")

        assertThat(ok).isTrue()
        assertThat(file.readText()).isEqualTo("new model")
    }

    @Test
    fun replaceText_creates_the_file_when_it_did_not_exist(@TempDir dir: File) {
        val file = File(dir, "fresh.json")

        val ok = storage.replaceText(pathOf(file), "first write")

        assertThat(ok).isTrue()
        assertThat(file.readText()).isEqualTo("first write")
    }

    @Test
    fun replaceText_leaves_the_previous_content_readable_when_the_write_fails(@TempDir dir: File) {
        val lockedDir = File(dir, "locked").apply { mkdirs() }
        val file = File(lockedDir, "weights.json").apply { writeText("previous model") }

        // No write permission on the directory means the sibling ".tmp" file this method writes
        // first can never be created there - a deterministic, permission-based write failure that
        // never touches the target file at all.
        assertThat(lockedDir.setWritable(false)).isTrue()
        try {
            val ok = storage.replaceText(pathOf(file), "new model")

            assertThat(ok).isFalse()
            assertThat(file.readText()).isEqualTo("previous model")
        } finally {
            // JUnit's @TempDir cleanup needs write access back to delete the tree.
            lockedDir.setWritable(true)
        }
    }

    // --- replaceKeepingBackup ---------------------------------------------------------------------

    @Test
    fun replaceKeepingBackup_keeps_the_previous_content_in_the_bak_sibling(@TempDir dir: File) {
        val file = File(dir, "weights.json").apply { writeText("old model") }

        val ok = storage.replaceKeepingBackup(pathOf(file), "new model")

        assertThat(ok).isTrue()
        assertThat(file.readText()).isEqualTo("new model")
        assertThat(File(dir, "weights.json.bak").readText()).isEqualTo("old model")
    }

    @Test
    fun replaceKeepingBackup_creates_the_file_when_it_did_not_exist(@TempDir dir: File) {
        val file = File(dir, "fresh.json")

        val ok = storage.replaceKeepingBackup(pathOf(file), "first write")

        assertThat(ok).isTrue()
        assertThat(file.readText()).isEqualTo("first write")
        assertThat(File(dir, "fresh.json.bak").exists()).isFalse()
    }

    @Test
    fun replaceKeepingBackup_leaves_the_target_readable_when_the_write_fails(@TempDir dir: File) {
        val lockedDir = File(dir, "locked").apply { mkdirs() }
        val file = File(lockedDir, "weights.json").apply { writeText("previous model") }

        // Same deterministic failure as replaceText's equivalent test: no write permission on the
        // directory means the sibling ".tmp" file can never be created, so the rotate-to-.bak step
        // never runs and the target is never touched.
        assertThat(lockedDir.setWritable(false)).isTrue()
        try {
            val ok = storage.replaceKeepingBackup(pathOf(file), "new model")

            assertThat(ok).isFalse()
            assertThat(file.readText()).isEqualTo("previous model")
        } finally {
            // JUnit's @TempDir cleanup needs write access back to delete the tree.
            lockedDir.setWritable(true)
        }
    }

    // --- readTailLines ----------------------------------------------------------------------------

    @Test
    fun readTailLines_returns_the_last_lines_newest_first(@TempDir dir: File) {
        val file = File(dir, "journal.jsonl").apply {
            writeText((1..5).joinToString("\n") { "line-$it" } + "\n")
        }

        val tail = storage.readTailLines(pathOf(file), maxLines = 3)

        assertThat(tail).isEqualTo(listOf("line-5", "line-4", "line-3"))
    }

    @Test
    fun readTailLines_returns_every_line_when_the_file_has_fewer_than_maxLines(@TempDir dir: File) {
        val file = File(dir, "journal.jsonl").apply { writeText("line-1\nline-2\n") }

        val tail = storage.readTailLines(pathOf(file), maxLines = 10)

        assertThat(tail).isEqualTo(listOf("line-2", "line-1"))
    }

    @Test
    fun readTailLines_on_a_missing_file_answers_an_empty_list(@TempDir dir: File) {
        val missing = File(dir, "never_written.jsonl")

        assertThat(storage.readTailLines(pathOf(missing), maxLines = 5)).isEmpty()
    }

    // --- sizeBytes ----------------------------------------------------------------------------------

    @Test
    fun sizeBytes_on_a_missing_file_answers_zero(@TempDir dir: File) {
        val missing = File(dir, "never_written.csv")

        assertThat(storage.sizeBytes(pathOf(missing))).isEqualTo(0L)
    }

    @Test
    fun sizeBytes_matches_the_content_length(@TempDir dir: File) {
        val file = File(dir, "data.csv").apply { writeText("12345") }

        assertThat(storage.sizeBytes(pathOf(file))).isEqualTo(5L)
    }

    // --- list -----------------------------------------------------------------------------------

    // --- forEachLine ----------------------------------------------------------------------------

    @Test
    fun forEachLine_walks_every_line_in_file_order(@TempDir dir: File) {
        val file = File(dir, "journal.jsonl").apply { writeText("a\nb\nc\n") }
        val seen = mutableListOf<String>()

        val ok = storage.forEachLine(pathOf(file)) { seen += it }

        assertThat(ok).isTrue()
        assertThat(seen).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun forEachLine_on_a_missing_file_answers_false_and_calls_nothing(@TempDir dir: File) {
        val seen = mutableListOf<String>()

        val ok = storage.forEachLine(pathOf(File(dir, "never_created"))) { seen += it }

        assertThat(ok).isFalse()
        assertThat(seen).isEmpty()
    }

    // --- copy -----------------------------------------------------------------------------------

    @Test
    fun copy_duplicates_the_content_to_the_new_path(@TempDir dir: File) {
        val source = File(dir, "source.json").apply { writeText("payload") }
        val destination = File(File(dir, "backup").apply { mkdirs() }, "source.json")

        val ok = storage.copy(pathOf(source), pathOf(destination))

        assertThat(ok).isTrue()
        assertThat(destination.readText()).isEqualTo("payload")
        assertThat(source.readText()).isEqualTo("payload")
    }

    @Test
    fun copy_of_a_missing_source_answers_false(@TempDir dir: File) {
        val missing = File(dir, "never_written.json")
        val destination = File(dir, "destination.json")

        assertThat(storage.copy(pathOf(missing), pathOf(destination))).isFalse()
    }

    // --- lastModifiedMs -----------------------------------------------------------------------------

    @Test
    fun lastModifiedMs_on_a_missing_file_answers_null(@TempDir dir: File) {
        val missing = File(dir, "never_written.json")

        assertThat(storage.lastModifiedMs(pathOf(missing))).isNull()
    }

    @Test
    fun lastModifiedMs_matches_the_file_system_timestamp(@TempDir dir: File) {
        val file = File(dir, "data.json").apply { writeText("x") }

        assertThat(storage.lastModifiedMs(pathOf(file))).isEqualTo(file.lastModified())
    }
}
