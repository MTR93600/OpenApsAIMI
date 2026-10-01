package app.aaps.plugins.aps.openAPSAIMI.utils

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `AndroidAimiStorage.rewriteLines` against real files.
 *
 * The method exists for the Autodrive training corpus, which only grows, so it streams the new
 * content into a scratch file and swaps that in. Two properties are checked here and they are the
 * reason it is written that way:
 *
 * - a finished rewrite leaves the target holding exactly the lines, each closed by `\n`, and no
 *   scratch file next to it
 * - a rewrite that dies halfway leaves the **previous** target untouched. That is the whole point of
 *   building the content somewhere else first: a full disk in the middle of the write must not cost
 *   the user the training data of the last sixty days.
 */
class AndroidAimiStorageRewriteTest {

    @TempDir
    lateinit var tempDir: File

    private fun storage(): AimiStorage {
        val helper = mock<AimiStorageHelper>()
        whenever(helper.getAimiFile(any())).thenAnswer { invocation ->
            File(tempDir, invocation.getArgument<String>(0))
        }
        return AndroidAimiStorage(helper)
    }

    @Test
    fun a_finished_rewrite_writes_every_line_and_removes_the_scratch_file() {
        val storage = storage()
        val target = storage.file("corpus.csv")
        val scratch = storage.file("corpus_tmp.csv")

        val written = storage.rewriteLines(target, scratch, sequenceOf("header", "row one", "row two"))

        assertThat(written).isTrue()
        assertThat(File(tempDir, "corpus.csv").readText()).isEqualTo("header\nrow one\nrow two\n")
        assertThat(File(tempDir, "corpus_tmp.csv").exists()).isFalse()
    }

    @Test
    fun a_rewrite_replaces_what_the_file_held_before() {
        val storage = storage()
        val target = storage.file("corpus.csv")
        File(tempDir, "corpus.csv").writeText("old header\nold row\n")

        val written = storage.rewriteLines(target, storage.file("corpus_tmp.csv"), sequenceOf("new header"))

        assertThat(written).isTrue()
        assertThat(File(tempDir, "corpus.csv").readText()).isEqualTo("new header\n")
    }

    /**
     * The failure that matters: the source of the lines gives up partway through.
     *
     * A sequence that throws stands in for the disk filling up or the permission going away. What is
     * checked is not the return value alone but that the corpus of the last sixty days is still on
     * disk, byte for byte, and that no half-written scratch file was left in the AIMI directory where
     * the backup scanner would pick it up as a CSV.
     */
    @Test
    fun a_rewrite_that_dies_halfway_leaves_the_previous_file_untouched() {
        val storage = storage()
        val target = storage.file("corpus.csv")
        val previous = "old header\nold row one\nold row two\n"
        File(tempDir, "corpus.csv").writeText(previous)

        val lines = sequence {
            yield("new header")
            yield("new row one")
            throw IllegalStateException("disk full")
        }
        val written = storage.rewriteLines(target, storage.file("corpus_tmp.csv"), lines)

        assertThat(written).isFalse()
        assertThat(File(tempDir, "corpus.csv").readText()).isEqualTo(previous)
        assertThat(File(tempDir, "corpus_tmp.csv").exists()).isFalse()
    }

    /** A target that did not exist yet is simply created. */
    @Test
    fun a_rewrite_creates_a_target_that_was_not_there() {
        val storage = storage()

        val written = storage.rewriteLines(
            storage.file("corpus.csv"),
            storage.file("corpus_tmp.csv"),
            sequenceOf("header"),
        )

        assertThat(written).isTrue()
        assertThat(File(tempDir, "corpus.csv").readText()).isEqualTo("header\n")
    }

    /** No lines at all still gives an empty file rather than leaving the old one in place. */
    @Test
    fun a_rewrite_with_no_lines_empties_the_file() {
        val storage = storage()
        File(tempDir, "corpus.csv").writeText("old header\n")

        val written = storage.rewriteLines(
            storage.file("corpus.csv"),
            storage.file("corpus_tmp.csv"),
            emptySequence(),
        )

        assertThat(written).isTrue()
        assertThat(File(tempDir, "corpus.csv").readText()).isEmpty()
    }
}
