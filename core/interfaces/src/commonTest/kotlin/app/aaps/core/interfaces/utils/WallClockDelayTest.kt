package app.aaps.core.interfaces.utils

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallClockDelayTest {

    @Test
    fun `an end in the past returns at once`() = runTest {
        delayUntilWallClock(endTimeMs = 1_000L, now = { 5_000L })

        assertEquals(0L, currentTime)
    }

    @Test
    fun `waits until the clock reaches the end`() = runTest {
        var done = false
        launch {
            delayUntilWallClock(endTimeMs = 100_000L, now = { currentTime }, maxStepMs = 30_000L)
            done = true
        }

        advanceTimeBy(99_999L)
        runCurrent()
        assertFalse(done)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(done)
    }

    /** The case this exists for: the wall clock jumps forward while the app was suspended. */
    @Test
    fun `a jump of the wall clock is noticed within one step`() = runTest {
        var jump = 0L
        var done = false
        launch {
            delayUntilWallClock(endTimeMs = 3_600_000L, now = { currentTime + jump }, maxStepMs = 30_000L)
            done = true
        }

        advanceTimeBy(10_000L)
        runCurrent()
        // The app slept for an hour; the coroutine clock did not move during that hour.
        jump = 3_600_000L
        assertFalse(done)

        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun `the timer runs its action at the end and not before`() = runTest {
        var runs = 0
        val timer = WallClockTimer(backgroundScope) { currentTime }

        timer.schedule(60_000L) { runs++ }
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(0, runs)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `a cancelled action does not run`() = runTest {
        var runs = 0
        val timer = WallClockTimer(backgroundScope) { currentTime }

        timer.schedule(60_000L) { runs++ }
        timer.cancel()
        advanceTimeBy(120_000L)
        runCurrent()

        assertEquals(0, runs)
    }

    @Test
    fun `a new schedule replaces the pending one`() = runTest {
        val ran = mutableListOf<String>()
        val timer = WallClockTimer(backgroundScope) { currentTime }

        timer.schedule(60_000L) { ran += "first" }
        timer.schedule(120_000L) { ran += "second" }
        advanceTimeBy(200_000L)
        runCurrent()

        assertEquals(listOf("second"), ran)
    }
}
