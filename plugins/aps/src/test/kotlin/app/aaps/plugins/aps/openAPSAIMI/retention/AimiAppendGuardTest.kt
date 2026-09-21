package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AimiAppendGuardTest {

    @BeforeEach
    fun setUp() {
        AimiAppendGuard.resetForTest()
    }

    @Test
    fun statsTheFileOncePerMegabyteNotOncePerLine(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("body\n")

        repeat(1000) { AimiAppendGuard.beforeAppend(file, 2048) }

        assertThat(AimiAppendGuard.statCount).isAtMost(3)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun rotatesTheFileAsideOnceItPassesItsHardCap(@TempDir dir: File) {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_Decisions.jsonl")!!
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("body\n")

        val outcome = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

        assertThat(outcome).isEqualTo(AimiAppendOutcome.ROTATED)
        assertThat(file.exists()).isFalse()
        assertThat(File(dir, "AIMI_Decisions.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX).exists()).isTrue()
        assertThat(rule.hardCapBytes).isGreaterThan(1L)
    }

    @Test
    fun anUnmanagedFileIsNeverTouched(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")
        file.writeText("body\n")

        val outcome = AimiAppendGuard.beforeAppend(file, 1024 * 1024 * 4)

        assertThat(outcome).isEqualTo(AimiAppendOutcome.NOT_MANAGED)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun anExistingOverflowFileIsNotOverwritten(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("second\n")
        val overflow = File(dir, "AIMI_Decisions.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX)
        overflow.writeText("first\n")

        val outcome = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

        assertThat(outcome).isEqualTo(AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT)
        assertThat(overflow.readText()).isEqualTo("first\n")
        assertThat(file.readText()).isEqualTo("second\n")
    }

    @Test
    fun theOverflowCheckAndTheRenameAreAtomicUnderTheLock(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("live content\n")
        val overflow = File(dir, "AIMI_Decisions.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX)

        // Part 1: while another thread holds the file's lock (as the janitor would while trimming),
        // the guard must not rotate at all - not even read the overflow file.
        val holderHasLock = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            AimiFileLock.withFile(file) {
                holderHasLock.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holderThread.isDaemon = true
        holderThread.start()
        try {
            assertThat(holderHasLock.await(2, TimeUnit.SECONDS)).isTrue()

            val outcomeWhileBusy = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

            assertThat(outcomeWhileBusy).isEqualTo(AimiAppendOutcome.SKIPPED_BUSY)
            assertThat(file.exists()).isTrue()
            assertThat(overflow.exists()).isFalse()
        } finally {
            releaseHolder.countDown()
            holderThread.join(2_000)
        }

        // Part 2: the lock is free now. Rotate for real, once.
        val firstOutcome = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)
        assertThat(firstOutcome).isEqualTo(AimiAppendOutcome.ROTATED)
        assertThat(overflow.exists()).isTrue()
        val overflowBytesAfterFirstRotation = overflow.readBytes()

        // The writer creates a fresh live file at the same path with different content, the same
        // way it would after the rename above.
        file.writeText("fresh content after rotation\n")

        // A second rotation attempt, with the overflow already present and the lock free, must see
        // that under the SAME lock acquisition as its own check, and must never reach renameTo: the
        // existing overflow's bytes must stay exactly what they were, not just "still exist".
        val secondOutcome = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

        assertThat(secondOutcome).isEqualTo(AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT)
        assertThat(overflow.readBytes()).isEqualTo(overflowBytesAfterFirstRotation)
        assertThat(file.readText()).isEqualTo("fresh content after rotation\n")
    }

    @Test
    fun neverBlocksWhenTheFileIsLockedByAnotherThread(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("body\n")

        // Holds AimiFileLock for `file` on another thread, the same way the janitor would while
        // trimming it, so beforeAppend must find it busy and give up at once rather than wait.
        val holderHasLock = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Thread {
            AimiFileLock.withFile(file) {
                holderHasLock.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holderThread.isDaemon = true
        holderThread.start()
        try {
            assertThat(holderHasLock.await(2, TimeUnit.SECONDS)).isTrue()

            val startNanos = System.nanoTime()
            val outcome = AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            assertThat(elapsedMs).isLessThan(500L)
            assertThat(outcome).isEqualTo(AimiAppendOutcome.SKIPPED_BUSY)
            assertThat(file.exists()).isTrue()
        } finally {
            releaseHolder.countDown()
            holderThread.join(2_000)
        }
    }
}
