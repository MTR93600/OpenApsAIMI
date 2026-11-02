package app.aaps.plugins.eversense.packets

import app.aaps.plugins.eversense.packets.Eversense365Communicator.Companion.BACKFILL_DEDUP_TOLERANCE_MS
import app.aaps.plugins.eversense.packets.Eversense365Communicator.Companion.isBackfillCandidate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Eversense365BackfillFilterTest {

    private val previous = 1_700_000_000_000L
    private val live = previous + 600_000L   // one reading missing in a 10 minute gap

    @Test fun `entry exactly at the previous reading is dropped`() =
        assertFalse(isBackfillCandidate(previous, previous, live))

    @Test fun `entry just inside the lower tolerance is dropped`() =
        assertFalse(isBackfillCandidate(previous + BACKFILL_DEDUP_TOLERANCE_MS, previous, live))

    @Test fun `entry just outside the lower tolerance is kept`() =
        assertTrue(isBackfillCandidate(previous + BACKFILL_DEDUP_TOLERANCE_MS + 1, previous, live))

    @Test fun `entry exactly at the live reading is dropped`() =
        assertFalse(isBackfillCandidate(live, previous, live))

    @Test fun `entry just inside the upper tolerance is dropped`() =
        assertFalse(isBackfillCandidate(live - BACKFILL_DEDUP_TOLERANCE_MS, previous, live))

    @Test fun `entry just outside the upper tolerance is kept`() =
        assertTrue(isBackfillCandidate(live - BACKFILL_DEDUP_TOLERANCE_MS - 1, previous, live))

    @Test fun `the genuinely missed reading in the middle of the gap is kept`() =
        assertTrue(isBackfillCandidate(previous + 300_000L, previous, live))

    @Test fun `a normal 5 minute cadence leaves nothing to backfill`() {
        val liveNext = previous + 300_000L
        assertFalse(isBackfillCandidate(previous, previous, liveNext))
        assertFalse(isBackfillCandidate(liveNext, previous, liveNext))
    }
}
