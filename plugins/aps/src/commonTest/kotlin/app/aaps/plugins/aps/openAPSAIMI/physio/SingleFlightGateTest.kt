package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SingleFlightGate] guards the one thing that must not happen twice: feeding the physiological
 * baseline. Its history is keyed by the time of the run, so two overlapping runs add two points
 * for one reading, and the mean and spread they produce reach an insulin multiplier.
 */
class SingleFlightGateTest {

    @Test
    fun `the first caller enters`() {
        assertTrue(SingleFlightGate().tryEnter())
    }

    @Test
    fun `a second caller is turned away while the first holds it`() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter(), "two callers must never be inside at once")
    }

    @Test
    fun `the gate is free again after the holder leaves`() {
        val gate = SingleFlightGate()
        gate.tryEnter()

        gate.exit()

        assertTrue(gate.tryEnter(), "a skipped run must not lock the gate for good")
    }

    /**
     * The caller releases the gate in a `finally`, so a run that throws still frees it. This pins
     * that the gate itself does not care how the holder left.
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

        assertTrue(gate.tryEnter())
    }

    /**
     * The real test, and the reason the gate is a compare-and-set rather than a read followed by a
     * write: many callers arrive at once and **exactly one** gets in.
     *
     * This runs them on a real multi-threaded dispatcher, not on the test scheduler, because a
     * single-threaded dispatcher would let a check-then-set version pass too - and that is the bug
     * this guards against.
     */
    @Test
    fun `exactly one of many callers racing for the gate enters`() = runTest {
        repeat(50) {
            val gate = SingleFlightGate()

            val entered = withContext(Dispatchers.Default) {
                (1..64).map { async { gate.tryEnter() } }.awaitAll()
            }

            assertEquals(1, entered.count { it }, "exactly one caller may enter")
        }
    }
}
