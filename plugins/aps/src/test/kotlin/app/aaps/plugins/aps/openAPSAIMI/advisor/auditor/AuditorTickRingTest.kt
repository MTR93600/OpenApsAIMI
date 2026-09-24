package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The in-memory ring that feeds the 30-minute context. It is the only history the profile checker
 * gets: nothing here reads a file or a database, so what goes in by [AuditorTickRing.record] is
 * exactly what a later [AuditorTickRing.snapshot] hands back.
 */
class AuditorTickRingTest {

    private fun fact(ts: Long, bg: Double = 100.0) = AuditorTickFact(
        timestampMs = ts,
        bgMgdl = bg,
        deltaMgdl5m = 0.0,
        shortAvgDeltaMgdl5m = 0.0,
        iobU = 0.0,
        cobG = 0.0,
        profileIsfStaticMgdl = 60.0,
        dynamicIsfRawMgdl = 60.0,
        commandIsfRawMgdl = 60.0,
        commandIsfPreFloorMgdl = 60.0,
        commandFloorMultiplier = 1.0,
        workingIsfRawMgdl = 60.0,
        profileTargetMgdl = 100.0,
        tempTargetActive = false,
        workingTargetRawMgdl = 100.0,
        minPredBgMgdl = null,
        hypoThresholdMgdl = null,
        runningBasalUph = 0.6,
        profileBasalUph = 0.6,
        postHypoActive = false,
        exerciseLockout = false,
        isfFactorApplied = 1.0,
        targetFactorApplied = 1.0,
    )

    @Test
    fun `an empty ring gives an empty snapshot and no latest`() {
        val ring = AuditorTickRing()
        assertTrue(ring.snapshot(1_000_000L).isEmpty())
        assertNull(ring.latest())
    }

    @Test
    fun `what is recorded is what the snapshot hands back, oldest first`() {
        val ring = AuditorTickRing()
        ring.record(fact(1_000_000L))
        ring.record(fact(1_060_000L))
        ring.record(fact(1_120_000L))
        val snapshot = ring.snapshot(1_120_000L)
        assertEquals(listOf(1_000_000L, 1_060_000L, 1_120_000L), snapshot.map { it.timestampMs })
        assertEquals(1_120_000L, ring.latest()!!.timestampMs)
    }

    @Test
    fun `a tick older than the window is dropped from the snapshot`() {
        val ring = AuditorTickRing(maxAgeMs = 45 * 60_000L, maxSize = 64)
        ring.record(fact(0L))
        ring.record(fact(50 * 60_000L))
        // The first tick is more than 45 minutes before the second, so recording the second evicts it.
        val snapshot = ring.snapshot(50 * 60_000L)
        assertEquals(listOf(50 * 60_000L), snapshot.map { it.timestampMs })
    }

    @Test
    fun `the ring never grows past its size cap`() {
        val ring = AuditorTickRing(maxAgeMs = Long.MAX_VALUE / 2, maxSize = 5)
        for (i in 0 until 10) ring.record(fact(i * 60_000L))
        val snapshot = ring.snapshot(9 * 60_000L)
        assertEquals(5, snapshot.size)
        assertEquals(5 * 60_000L, snapshot.first().timestampMs)
        assertEquals(9 * 60_000L, snapshot.last().timestampMs)
    }
}
