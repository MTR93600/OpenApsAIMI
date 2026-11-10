package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.source.CgmWarmupStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassCgmLinkStatusTest {

    private val now = 10_000_000_000L
    private val min = 60_000L

    private fun warmup(phase: CgmWarmupStatus.Phase, active: Boolean = true) =
        CgmWarmupStatus(active = active, phase = phase, remainingMs = null, endsAtEpochMs = null, message = null)

    /** ONE+ standby between two radio windows: the source says RECONNECTING, but the value is fresh. */
    @Test
    fun normalOnePlusStandbyShowsNothing() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING), isGlucoseActual = true, glucoseTimestampMs = now - 3 * min, nowMs = now)
        assertThat(banner).isNull()
    }

    @Test
    fun realLossShowsThePhaseAndTheAgeOfTheLastValue() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING), isGlucoseActual = false, glucoseTimestampMs = now - 14 * min, nowMs = now)!!
        assertThat(banner.phase).isEqualTo(CgmWarmupStatus.Phase.RECONNECTING)
        assertThat(banner.minutesSinceLastValue).isEqualTo(14L)
    }

    /** The view model refreshes on events; the card must still notice a value getting old between them. */
    @Test
    fun valueOlderThanNineMinutesIsStaleEvenIfTheViewModelStillSaysActual() {
        assertThat(GlassCgmLinkStatus.isStale(isGlucoseActual = true, glucoseTimestampMs = now - 10 * min, nowMs = now)).isTrue()
        assertThat(GlassCgmLinkStatus.isStale(isGlucoseActual = true, glucoseTimestampMs = now - 8 * min, nowMs = now)).isFalse()
        assertThat(GlassCgmLinkStatus.isStale(isGlucoseActual = false, glucoseTimestampMs = null, nowMs = now)).isTrue()
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.CONNECTING), isGlucoseActual = true, glucoseTimestampMs = now - 10 * min, nowMs = now)
        assertThat(banner).isNotNull()
    }

    @Test
    fun noSourceStatusOrInactiveStatusShowsNoBanner() {
        assertThat(GlassCgmLinkStatus.banner(null, isGlucoseActual = false, glucoseTimestampMs = now - 20 * min, nowMs = now)).isNull()
        assertThat(GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.RECONNECTING, active = false), false, now - 20 * min, now)).isNull()
    }

    @Test
    fun unknownLastValueGivesNoAge() {
        val banner = GlassCgmLinkStatus.banner(warmup(CgmWarmupStatus.Phase.PAIRING), isGlucoseActual = false, glucoseTimestampMs = null, nowMs = now)!!
        assertThat(banner.minutesSinceLastValue).isNull()
    }
}
