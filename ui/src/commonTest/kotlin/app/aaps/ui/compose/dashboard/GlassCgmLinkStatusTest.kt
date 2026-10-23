package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.source.CgmWarmupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `origin/dev_OAPSAIMI` `GlassCgmLinkStatusTest` (`e5e7033311`). [GlassCgmLinkStatus] is
 * pure commonMain, so the tests run on the JVM and on the iOS simulator.
 */
class GlassCgmLinkStatusTest {

    private val now = 10_000_000_000L
    private val min = 60_000L

    private fun warmup(phase: CgmWarmupStatus.Phase, active: Boolean = true) =
        CgmWarmupStatus(active = active, phase = phase, remainingMs = null, endsAtEpochMs = null, message = null)

    /** ONE+ standby between two radio windows: the source says RECONNECTING, but the value is fresh. */
    @Test
    fun normalOnePlusStandbyShowsNothing() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING), isGlucoseActual = true, glucoseTimestampMs = now - 3 * min, nowMs = now)
        assertNull(banner)
    }

    @Test
    fun realLossShowsThePhaseAndTheAgeOfTheLastValue() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING), isGlucoseActual = false, glucoseTimestampMs = now - 14 * min, nowMs = now)
        assertNotNull(banner)
        assertEquals(CgmWarmupStatus.Phase.RECONNECTING, banner.phase)
        assertEquals(14L, banner.minutesSinceLastValue)
    }

    /** The overview refreshes on events; the card must still notice a value getting old between them. */
    @Test
    fun valueOlderThanNineMinutesIsStaleEvenIfTheOverviewStillSaysActual() {
        assertTrue(GlassCgmLinkStatus.isStale(isGlucoseActual = true, glucoseTimestampMs = now - 10 * min, nowMs = now))
        assertFalse(GlassCgmLinkStatus.isStale(isGlucoseActual = true, glucoseTimestampMs = now - 8 * min, nowMs = now))
        assertTrue(GlassCgmLinkStatus.isStale(isGlucoseActual = false, glucoseTimestampMs = null, nowMs = now))
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.CONNECTING), isGlucoseActual = true, glucoseTimestampMs = now - 10 * min, nowMs = now)
        assertNotNull(banner)
    }

    @Test
    fun noSourceStatusOrInactiveStatusShowsNoBanner() {
        assertNull(GlassCgmLinkStatus.banner(null, isGlucoseActual = false, glucoseTimestampMs = now - 20 * min, nowMs = now))
        assertNull(GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING, active = false), false, now - 20 * min, now))
    }

    @Test
    fun unknownLastValueGivesNoAge() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.PAIRING), isGlucoseActual = false, glucoseTimestampMs = null, nowMs = now)
        assertNotNull(banner)
        assertNull(banner.minutesSinceLastValue)
    }
}
