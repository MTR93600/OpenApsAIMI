package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock

class AimiRetentionWorkerLogicTest {

    /** The manager speaks AimiPath now; these tests still build their fixtures with java.io.File. */
    private fun File.asAimiPath() = AimiPath(absolutePath)

    /**
     * The manager only asks the storage about paths, text and sizes, and the real Android storage
     * answers all of that from the path alone, so the helper never gets called.
     */
    private val storage: AimiStorage = AndroidAimiStorage(mock<AimiStorageHelper>())

    @Test
    fun aPassThatThrowsIsReportedAsHandledNotRetried(@TempDir dir: File) {
        val logged = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir.asAimiPath(), storage, { logged.add(it) }, { 1_758_240_000_000L }) {
            override fun onFileVisited(path: AimiPath) = error("disk on fire")
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
        val subject = AimiRetentionManager(dir.asAimiPath(), storage, {}, { 1_758_240_000_000L })

        assertThat(subject.runOnce()).isEqualTo(0)
    }
}
