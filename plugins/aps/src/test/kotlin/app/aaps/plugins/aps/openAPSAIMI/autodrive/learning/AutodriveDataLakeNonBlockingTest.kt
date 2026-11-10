package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The data lake writes from the APS decision thread, on every tick, into a file the backfiller holds
 * across a full read-modify-rename of the whole corpus. Measured as pure I/O on a desktop SSD, that
 * transaction takes about 14 ms over 17 068 rows; on a phone, with the `copyTo` fallback when
 * `renameTo` fails, it is an order of magnitude worse and unbounded from the caller's side.
 *
 * A lost training row is cheap. A delayed dose is not. So the write never waits.
 */
class AutodriveDataLakeNonBlockingTest {

    @TempDir
    lateinit var dir: File

    private val aapsLogger = mockk<AAPSLogger>(relaxed = true)

    private fun dataLake(): AutodriveDataLake {
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any<String>()) } answers { File(dir, firstArg<String>()) }
        return AutodriveDataLake(aapsLogger, storage)
    }

    private fun state() = AutoDriveState(
        bg = 150.0, bgVelocity = 0.5, iob = 1.2, physiologicalStressMask = doubleArrayOf(0.1, 0.2, 0.3),
    )

    private fun rows() = File(dir, AutodriveDataLake.FILE_NAME)
        .takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()

    @Test
    fun `a write during the backfiller's transaction returns without waiting for it`() {
        val lake = dataLake()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder = Thread {
            AutodriveDatasetLock.withDataset {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue()

        val startNs = System.nanoTime()
        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1_000L)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // The lock is still held; the call must have come straight back.
        assertThat(elapsedMs).isLessThan(500L)
        assertThat(lake.contendedWriteCount).isEqualTo(1)
        assertThat(lake.deferredRowCount).isEqualTo(1)
        assertThat(rows()).isEmpty()

        release.countDown()
        holder.join(10_000)

        // The carried row is not lost: it goes out with the next write.
        lake.recordSnapshot(state(), null, null, engaged = false, currentTimestamp = 2_000L)
        assertThat(lake.deferredRowCount).isEqualTo(0)
        assertThat(rows()).hasSize(2)
    }

    @Test
    fun `the carry-forward buffer is bounded and counts what it drops`() {
        val lake = dataLake()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder = Thread {
            AutodriveDatasetLock.withDataset {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue()

        val attempts = AutodriveDataLake.MAX_DEFERRED_ROWS + 10
        repeat(attempts) { i ->
            lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1_000L + i)
        }

        assertThat(lake.deferredRowCount).isEqualTo(AutodriveDataLake.MAX_DEFERRED_ROWS)
        assertThat(lake.droppedRowCount).isEqualTo(10)

        release.countDown()
        holder.join(10_000)
    }

    @Test
    fun `a row carries the current schema version and the canonical header`() {
        val lake = dataLake()

        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1_000L)

        val lines = File(dir, AutodriveDataLake.FILE_NAME).readLines()
        assertThat(lines[0]).isEqualTo(AutodriveDatasetSchema.HEADER)
        val cols = lines[1].split(",")
        assertThat(cols).hasSize(AutodriveDatasetSchema.COLUMN_COUNT)
        assertThat(cols[AutodriveDatasetSchema.IDX_SCHEMA_VERSION])
            .isEqualTo(AutodriveDatasetSchema.CURRENT_VERSION.toString())
        assertThat(cols[AutodriveDatasetSchema.IDX_ENGAGED]).isEqualTo("1")
        // Outcomes are the backfiller's job; writing "0" here is a claim the tick cannot make.
        assertThat(cols[AutodriveDatasetSchema.IDX_HYPO]).isEmpty()
    }
}
