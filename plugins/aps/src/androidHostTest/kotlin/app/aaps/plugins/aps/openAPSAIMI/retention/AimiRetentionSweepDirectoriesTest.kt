package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AimiRetentionSweepDirectoriesTest {

    @Test
    fun aDirectoryThatThrowsDoesNotStopTheOthers(@TempDir root: File) {
        val a = File(root, "a").apply { mkdirs() }
        val b = File(root, "b").apply { mkdirs() }
        val c = File(root, "c").apply { mkdirs() }
        val failed = mutableListOf<File>()

        val total = sweepDirectories(
            directories = listOf(a, b, c),
            onFailure = { directory, _ -> failed.add(directory) },
        ) { directory ->
            if (directory == b) error("disk on fire") else 1
        }

        assertThat(total).isEqualTo(2)
        assertThat(failed).containsExactly(b)
    }

    @Test
    fun theReturnedTotalIsTheSumOfTheSuccessfulPasses(@TempDir root: File) {
        val a = File(root, "a").apply { mkdirs() }
        val b = File(root, "b").apply { mkdirs() }
        val c = File(root, "c").apply { mkdirs() }
        val results = mapOf(a to 3, b to 5, c to 7)

        val total = sweepDirectories(directories = listOf(a, b, c)) { directory -> results.getValue(directory) }

        assertThat(total).isEqualTo(15)
    }

    @Test
    fun stopsEarlyWithoutRunningLaterDirectoriesWhenToldToStop(@TempDir root: File) {
        val a = File(root, "a").apply { mkdirs() }
        val b = File(root, "b").apply { mkdirs() }
        val visited = mutableListOf<File>()
        var stopped = false

        val total = sweepDirectories(
            directories = listOf(a, b),
            shouldStop = { true },
            onStopped = { stopped = true },
        ) { directory ->
            visited.add(directory)
            1
        }

        assertThat(visited).isEmpty()
        assertThat(total).isEqualTo(0)
        assertThat(stopped).isTrue()
    }

    @Test
    fun keepsWhatWasAlreadySweptWhenStopFiresPartway(@TempDir root: File) {
        val a = File(root, "a").apply { mkdirs() }
        val b = File(root, "b").apply { mkdirs() }
        val c = File(root, "c").apply { mkdirs() }
        val visited = mutableListOf<File>()

        val total = sweepDirectories(
            directories = listOf(a, b, c),
            shouldStop = { visited.size >= 1 },
        ) { directory ->
            visited.add(directory)
            1
        }

        assertThat(visited).containsExactly(a)
        assertThat(total).isEqualTo(1)
    }

    @Test
    fun cancellationIsRethrownInsteadOfTreatedAsAFailedDirectory(@TempDir root: File) {
        val a = File(root, "a").apply { mkdirs() }
        val failed = mutableListOf<File>()

        assertThrows(CancellationException::class.java) {
            sweepDirectories(directories = listOf(a), onFailure = { directory, _ -> failed.add(directory) }) {
                throw CancellationException("worker stopped")
            }
        }

        assertThat(failed).isEmpty()
    }
}
