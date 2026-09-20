package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `autodrive_dataset.csv` has two writers on different WorkManager threads: the data lake appends a
 * row per Autodrive tick, and the backfiller reads the whole file, writes a temporary copy and
 * renames it over the original. A row appended between the backfiller's read and its rename lands in
 * a file that is about to be replaced, so it is lost — silently, and on the ticks the model needs.
 */
class AutodriveDatasetLockTest {

    @Test
    fun `concurrent sections never overlap`() {
        val threads = 8
        val iterations = 200
        val inside = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                repeat(iterations) {
                    AutodriveDatasetLock.withDataset {
                        val n = inside.incrementAndGet()
                        maxObserved.updateAndGet { m -> maxOf(m, n) }
                        inside.decrementAndGet()
                    }
                }
                done.countDown()
            }
        }
        start.countDown()

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()
        assertThat(maxObserved.get()).isEqualTo(1)
    }

    @Test
    fun `an append is never interleaved with a read-modify-rename`() {
        val rows = mutableListOf<Int>()
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)

        // Writer: appends rows.
        pool.execute {
            repeat(500) { i -> AutodriveDatasetLock.withDataset { rows.add(i) } }
            done.countDown()
        }
        // Rewriter: reads everything, transforms, replaces — the backfiller's shape.
        pool.execute {
            repeat(50) {
                AutodriveDatasetLock.withDataset {
                    val snapshot = rows.toList()
                    rows.clear()
                    rows.addAll(snapshot)
                }
            }
            done.countDown()
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdownNow()
        // Nothing lost: without the lock the rewriter's clear/addAll drops concurrent appends.
        assertThat(rows).hasSize(500)
        assertThat(rows).containsExactlyElementsIn(0 until 500).inOrder()
    }

    @Test
    fun `the lock is reentrant so nested access cannot deadlock`() {
        val result = AutodriveDatasetLock.withDataset {
            AutodriveDatasetLock.withDataset { "ok" }
        }

        assertThat(result).isEqualTo("ok")
    }
}
