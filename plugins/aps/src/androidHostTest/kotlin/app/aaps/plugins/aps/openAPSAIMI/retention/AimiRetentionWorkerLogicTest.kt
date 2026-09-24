package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AimiRetentionWorkerLogicTest {

    @Test
    fun aPassThatThrowsIsReportedAsHandledNotRetried(@TempDir dir: File) {
        val logged = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, { logged.add(it) }, { 1_758_240_000_000L }) {
            override fun onFileVisited(file: File) = error("disk on fire")
        }
        File(dir, "AIMI_Decisions.jsonl").writeText("body\n")

        val changed = subject.runOnce()

        // onFileVisited throws for every rule before any of them can be trimmed or retired, and the
        // file is fresh (not dropped as an orphan), so nothing is changed: this pins the actual
        // behaviour instead of the vacuous "isAtLeast(0)" every Int satisfies.
        assertThat(changed).isEqualTo(0)
        assertThat(logged.any { it.contains("AIMI_Decisions.jsonl") }).isTrue()
    }

    @Test
    fun anEmptyDirectoryIsAValidPass(@TempDir dir: File) {
        val subject = AimiRetentionManager(dir, {}, { 1_758_240_000_000L })

        assertThat(subject.runOnce()).isEqualTo(0)
    }
}
