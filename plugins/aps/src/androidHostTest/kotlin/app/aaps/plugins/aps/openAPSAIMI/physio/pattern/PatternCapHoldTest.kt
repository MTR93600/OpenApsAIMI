package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PatternCapHoldTest {

    @Test
    fun live_cap_passes_through_and_is_not_marked_holding() {
        val hold = PatternCapHold()
        assertThat(hold.resolve(rawCapU = 0.90, rising = true)).isEqualTo(0.90)
        assertThat(hold.holding).isFalse()
    }

    @Test
    fun cap_is_held_during_rise_when_pattern_flaps() {
        val hold = PatternCapHold(holdTicks = 3)
        hold.resolve(rawCapU = 0.90, rising = true)

        // Pattern flapped off mid-rise: cap survives 3 ticks.
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(0.90)
        assertThat(hold.holding).isTrue()
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(0.90)
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(0.90)

        // Hold exhausted.
        assertThat(hold.resolve(rawCapU = null, rising = true)).isNull()
        assertThat(hold.holding).isFalse()
    }

    @Test
    fun hold_clears_when_rise_ends() {
        val hold = PatternCapHold(holdTicks = 3)
        hold.resolve(rawCapU = 0.90, rising = true)

        assertThat(hold.resolve(rawCapU = null, rising = false)).isNull()
        // Even if a new rise starts immediately, the old cap is gone.
        assertThat(hold.resolve(rawCapU = null, rising = true)).isNull()
    }

    @Test
    fun live_cap_refreshes_the_hold_budget() {
        val hold = PatternCapHold(holdTicks = 2)
        hold.resolve(rawCapU = 0.90, rising = true)
        hold.resolve(rawCapU = null, rising = true)

        // Pattern comes back with a different cap: budget refreshed, new value held.
        hold.resolve(rawCapU = 1.20, rising = true)
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(1.20)
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(1.20)
        assertThat(hold.resolve(rawCapU = null, rising = true)).isNull()
    }

    @Test
    fun no_cap_ever_seen_yields_null() {
        val hold = PatternCapHold()
        assertThat(hold.resolve(rawCapU = null, rising = true)).isNull()
        assertThat(hold.holding).isFalse()
    }

    @Test
    fun soft_meal_proposal_is_not_rehardcapped_when_pattern_flaps() {
        val hold = PatternCapHold(holdTicks = 3)
        assertThat(
            hold.resolve(rawCapU = 1.20, rising = true, rawKind = PatternCapKind.SOFT),
        ).isNull()
        assertThat(hold.holding).isFalse()
        // Soft flap-off must not resurrect a binding 1.20 hard hold.
        assertThat(hold.resolve(rawCapU = null, rising = true)).isNull()
    }

    @Test
    fun hard_cap_still_held_during_rise_when_pattern_flaps() {
        val hold = PatternCapHold(holdTicks = 2)
        assertThat(
            hold.resolve(rawCapU = 0.50, rising = true, rawKind = PatternCapKind.HARD),
        ).isEqualTo(0.50)
        assertThat(hold.resolve(rawCapU = null, rising = true)).isEqualTo(0.50)
        assertThat(hold.holding).isTrue()
    }
}
