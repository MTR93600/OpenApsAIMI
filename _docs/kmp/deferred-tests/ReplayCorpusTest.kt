package app.aaps.plugins.aps.openAPSAIMI.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Self-check of the replay harness.
 *
 * These figures are the ones the ADR set was written from. If one of them moves, either a fixture
 * was regenerated or the harness stopped reading the export the same way — both need to be
 * explained before any behaviour-changing ADR is trusted.
 *
 * See `docs/adr/0001-replay-harness.md`.
 */
class ReplayCorpusTest {

    @Test
    fun bundledFixturesLoad() {
        ReplayCorpus.bundled.forEach { name ->
            val ticks = ReplayCorpus.load(name)
            assertTrue(ticks.size > 100, "$name should hold a full day of ticks, got ${ticks.size}")
            assertTrue(
                ticks.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs },
                "$name must be sorted by timestamp",
            )
        }
    }

    @Test
    fun dayInRange_isTheNonRegressionReference() {
        val summary = ReplaySummary.of(ReplayCorpus.load(ReplayCorpus.DAY_IN_RANGE))

        assertEquals(284, summary.ticks)
        assertEquals(27.46, summary.totalSmbU, 0.01)
        assertEquals(95.4, summary.timeInRangePercent, 0.1)
        assertEquals(0.0, summary.timeBelow70Percent, 0.001)
        // The property that makes ADR 0006 low risk: this day never triggers the guard, so capping
        // Autodrive on rebound ticks cannot change it.
        assertEquals(0.0, summary.autodriveSmbAtReboundGuardU, 0.001)
    }

    @Test
    fun dayReboundCycles_carriesTheDefectAdr0006Targets() {
        val summary = ReplaySummary.of(ReplayCorpus.load(ReplayCorpus.DAY_REBOUND_CYCLES))

        assertEquals(285, summary.ticks)
        assertEquals(56.76, summary.totalSmbU, 0.01)
        assertEquals(10.96, summary.autodriveSmbAtReboundGuardU, 0.01)
        assertEquals(4.6, summary.timeBelow70Percent, 0.1)
        // Autodrive owns essentially the whole SMB volume: this is a single dominant channel, not
        // an arbitration problem between several.
        assertTrue(
            summary.smbByOwner.getValue("AutodriveV3") / summary.totalSmbU > 0.95,
            "expected Autodrive to own more than 95 % of SMB, got ${summary.smbByOwner}",
        )
    }

    @Test
    fun dayHyper_showsTheLoopDosingNotBlocked() {
        val summary = ReplaySummary.of(ReplayCorpus.load(ReplayCorpus.DAY_HYPER))

        assertEquals(409, summary.ticks)
        assertTrue(summary.timeAbove180Percent > 15.0, "expected a hyper-heavy day, got $summary")
        // The loop is not held back during hyper: it doses hard and glucose stays high anyway.
        assertTrue(summary.totalSmbU > 30.0, "expected a high insulin volume, got ${summary.totalSmbU}")
    }

    @Test
    fun localCorpusIsOptionalAndNeverSilentlyEmpty() {
        val local = ReplayCorpus.loadLocal()
        if (local.isEmpty()) return // not configured: calibration tests skip, they do not pass
        local.forEach { (name, ticks) ->
            assertTrue(ticks.isNotEmpty(), "$name is present but holds no tick")
        }
    }
}
