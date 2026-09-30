package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * The data lake calls this from the APS decision thread on every tick, while the backfiller can
     * hold the dataset across a read-modify-rename of the whole corpus. Two things must hold, and
     * both are safety properties, not conveniences:
     *
     * - it returns at once instead of waiting, so a training row never delays a dose
     * - it does **not** run the block when the dataset is busy, so the row is carried forward rather
     *   than appended into a file that is about to be replaced
     *
     * This is the property that keeps [AutodriveDatasetLock] on the JVM. `AapsLock` offers only
     * `lock`/`unlock`, so any move to shared code has to bring a non-blocking attempt with it, and
     * this test is what would catch a replacement that quietly waits or quietly writes.
     */
    @Test
    fun `tryWithDataset gives up at once and skips the block while another thread holds the dataset`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            AutodriveDatasetLock.withDataset {
                held.countDown()
                release.await()
            }
        }
        holder.start()
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue()

        val blockRan = AtomicBoolean(false)
        val startedAtNanos = System.nanoTime()
        val result = AutodriveDatasetLock.tryWithDataset {
            blockRan.set(true)
            "appended"
        }
        val waitedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L

        release.countDown()
        holder.join(5_000)

        assertThat(result).isNull()
        assertThat(blockRan.get()).isFalse()
        // The holder is still inside its section above, so anything that waited for it would sit
        // here until `release`. A quarter of a second is far longer than a failed attempt needs and
        // far shorter than a real corpus transaction.
        assertThat(waitedMillis).isLessThan(250L)
    }

    @Test
    fun `tryWithDataset runs the block and returns its value when the dataset is free`() {
        val result = AutodriveDatasetLock.tryWithDataset { "appended" }

        assertThat(result).isEqualTo("appended")
    }

    @Test
    fun `a failed attempt does not leave the dataset held`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            AutodriveDatasetLock.withDataset {
                held.countDown()
                release.await()
            }
        }
        holder.start()
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue()

        // Released in `finally`: an assertion that throws here would otherwise leave the holder
        // parked inside its section, and every later test in this class would wait on a lock that
        // is never given back.
        val whileBusy = try {
            AutodriveDatasetLock.tryWithDataset { "appended" }
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        assertThat(whileBusy).isNull()

        // The next tick must be able to write, otherwise one busy moment would stop the corpus for
        // good.
        assertThat(AutodriveDatasetLock.tryWithDataset { "appended" }).isEqualTo("appended")
    }
}
