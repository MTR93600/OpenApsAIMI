package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AimiRetentionCandidateDirectoriesTest {

    @Test
    fun keepsEachDistinctExistingDirectoryOnce(@TempDir root: File) {
        val first = File(root, "one").apply { mkdirs() }
        val second = File(root, "two").apply { mkdirs() }

        val result = aimiRetentionCandidateDirectories(listOf(first, second))

        assertThat(result).containsExactly(first, second).inOrder()
    }

    @Test
    fun dropsADirectoryThatDoesNotExist(@TempDir root: File) {
        val existing = File(root, "one").apply { mkdirs() }
        val missing = File(root, "missing")

        val result = aimiRetentionCandidateDirectories(listOf(existing, missing))

        assertThat(result).containsExactly(existing)
    }

    @Test
    fun dropsAPathThatIsAFileNotADirectory(@TempDir root: File) {
        val directory = File(root, "one").apply { mkdirs() }
        val plainFile = File(root, "AIMI_Decisions.jsonl").apply { writeText("body") }

        val result = aimiRetentionCandidateDirectories(listOf(directory, plainFile))

        assertThat(result).containsExactly(directory)
    }

    @Test
    fun deduplicatesTheSameDirectoryGivenTwice(@TempDir root: File) {
        val directory = File(root, "one").apply { mkdirs() }

        val result = aimiRetentionCandidateDirectories(listOf(directory, directory))

        assertThat(result).containsExactly(directory)
    }

    @Test
    fun deduplicatesByCanonicalPathEvenWhenTheFileObjectsDiffer(@TempDir root: File) {
        val directory = File(root, "one").apply { mkdirs() }
        // Same directory, reached through a non-normalized path with a redundant "..". "two" must
        // exist for real: File.canonicalPath resolves ".." against the real filesystem, so with a
        // missing "two" the path would fail its own existence check and never reach the
        // de-duplication this test means to cover (that gap is why this test was rewritten).
        val two = File(root, "two").apply { mkdirs() }
        val sameDirectoryViaDotDot = File(two, "../one")
        // Confirms the setup actually exercises canonicalization, not just two equal File objects.
        assertThat(sameDirectoryViaDotDot.canonicalPath).isEqualTo(directory.canonicalPath)
        assertThat(sameDirectoryViaDotDot).isNotEqualTo(directory)

        val result = aimiRetentionCandidateDirectories(listOf(directory, sameDirectoryViaDotDot))

        assertThat(result).hasSize(1)
        assertThat(result.first().canonicalPath).isEqualTo(directory.canonicalPath)
    }

    @Test
    fun anEmptyCandidateListYieldsNoDirectories() {
        assertThat(aimiRetentionCandidateDirectories(emptyList())).isEmpty()
    }
}
