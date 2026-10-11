package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AimiFileLockTest {

    /** The lock speaks AimiPath now; these tests still build their fixtures with java.io.File. */
    private fun File.asAimiPath() = AimiPath(absolutePath)

    @Test
    fun tryWithFileReturnsNullWhileAnotherThreadHoldsTheSameFile(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            AimiFileLock.withFile(file.asAimiPath()) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        held.await(5, TimeUnit.SECONDS)

        assertThat(AimiFileLock.tryWithFile(file.asAimiPath()) { "taken" }).isNull()

        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun differentFilesDoNotBlockEachOther(@TempDir dir: File) {
        val a = File(dir, "a.jsonl")
        val b = File(dir, "b.jsonl")
        val result = AimiFileLock.withFile(a.asAimiPath()) { AimiFileLock.tryWithFile(b.asAimiPath()) { "free" } }
        assertThat(result).isEqualTo("free")
    }

    @Test
    fun theSameThreadCanTakeTheSameFileTwice(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val result = AimiFileLock.withFile(file.asAimiPath()) { AimiFileLock.withFile(file.asAimiPath()) { "reentrant" } }
        assertThat(result).isEqualTo("reentrant")
    }

    @Test
    fun twoDifferentSpellingsOfTheSameFileShareOneLock(@TempDir dir: File) {
        // A stand-in for the real case: storageHelper.getAimiDirectory() and the hardcoded
        // Documents/AAPS path can resolve to the same real directory through different spellings
        // (on Android, /sdcard is itself a symlink to /storage/emulated/0). File.canonicalPath needs
        // an existing intermediate directory to resolve "..", so "two" is created for real.
        val subDir = File(dir, "two").apply { mkdirs() }
        val file = File(dir, "a.jsonl").apply { writeText("body") }
        val sameFileViaDotDot = File(subDir, "../a.jsonl")
        assertThat(sameFileViaDotDot.canonicalPath).isEqualTo(file.canonicalPath)

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            AimiFileLock.withFile(file.asAimiPath()) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        held.await(5, TimeUnit.SECONDS)

        assertThat(AimiFileLock.tryWithFile(sameFileViaDotDot.asAimiPath()) { "taken" }).isNull()

        release.countDown()
        worker.join(5_000)
    }
}
