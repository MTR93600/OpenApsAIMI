package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

/**
 * [SingleFlightGate] guards the one thing that must not happen twice at once: feeding the
 * physiological baseline. Its per-run history is keyed by the time of the run, so two overlapping
 * runs add two points for one reading, and the mean and spread they give reach an insulin decision.
 */
class SingleFlightGateTest {

    @Test
    fun `the first caller enters`() {
        assertThat(SingleFlightGate().tryEnter()).isTrue()
    }

    @Test
    fun `a second caller is turned away while the first holds it`() {
        val gate = SingleFlightGate()

        assertThat(gate.tryEnter()).isTrue()
        assertWithMessage("two callers must never be inside at once").that(gate.tryEnter()).isFalse()
    }

    @Test
    fun `the gate is free again after the holder leaves`() {
        val gate = SingleFlightGate()
        gate.tryEnter()

        gate.exit()

        assertWithMessage("a skipped run must not lock the gate for good").that(gate.tryEnter()).isTrue()
    }

    /**
     * The caller gives the gate back in a `finally`, so a run that throws still frees it. This pins
     * that the gate does not care how the holder left.
     */
    @Test
    fun `a holder that fails still frees the gate`() {
        val gate = SingleFlightGate()
        gate.tryEnter()

        runCatching {
            try {
                error("the read failed")
            } finally {
                gate.exit()
            }
        }

        assertThat(gate.tryEnter()).isTrue()
    }

    /**
     * The real test, and the reason the gate is a compare-and-set and not a read followed by a
     * write: many callers arrive at once and **exactly one** gets in.
     *
     * It runs them on real threads that all start together, because a single thread would let a
     * check-then-set version pass too - and that is the bug this guards against.
     */
    @Test
    fun `exactly one of many callers racing for the gate enters`() {
        val pool = Executors.newFixedThreadPool(16)
        try {
            repeat(50) {
                val gate = SingleFlightGate()
                val start = CountDownLatch(1)
                val tasks = (1..64).map {
                    Callable {
                        start.await()
                        gate.tryEnter()
                    }
                }
                val futures = tasks.map { pool.submit(it) }
                start.countDown()
                val entered = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertWithMessage("exactly one caller may enter").that(entered.count { it }).isEqualTo(1)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
