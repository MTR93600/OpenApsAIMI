package app.aaps.plugins.aps.openAPSAIMI.tpo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * A time-period override writes preference values and must put them back when the session ends.
 *
 * The question this policy answers is the only one that matters at revert time: **is the value in
 * force still the one the session wrote?** If it is, nobody else has touched it and the user's own
 * value must come back. If it is not, somebody else owns the key now and the session must leave it
 * alone.
 */
class TpoRevertPolicyTest {

    @Test
    fun aValueStillEqualToWhatTheSessionWroteIsRestored() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = 1.25)).isTrue()
    }

    @Test
    fun aValueSomebodyElseHasMovedIsLeftAlone() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.60, overlayValue = 1.25)).isFalse()
    }

    @Test
    fun twoDoublesWithinTheToleranceCountAsTheSameValue() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.250_02, overlayValue = 1.25)).isTrue()
    }

    @Test
    fun twoDoublesOutsideTheToleranceDoNot() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.26, overlayValue = 1.25)).isFalse()
    }

    @Test
    fun aBooleanStillEqualToWhatTheSessionWroteIsRestored() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = true, overlayValue = true)).isTrue()
    }

    @Test
    fun aBooleanSomebodyElseHasFlippedIsLeftAlone() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = false, overlayValue = true)).isFalse()
    }

    @Test
    fun aValueThatCouldNotBeReadIsLeftAlone() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = null, overlayValue = 1.25)).isFalse()
    }

    @Test
    fun aKeyTheSessionNeverWroteIsLeftAlone() {
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = null)).isFalse()
    }

    /**
     * The bug this policy replaces. `userOwnedKeys` was sticky and additive: one tick where the read
     * differed from the overlay marked the key for the rest of the session, and `revertSession` then
     * skipped it for good — so the session's own value stayed in the preferences forever and the
     * user's value was silently lost. A key whose value comes back to what the session wrote must be
     * restored, whatever was observed earlier in the session.
     */
    @Test
    fun aKeyThatDivergedEarlierButIsBackToTheSessionValueIsStillRestored() {
        // What the old sticky set would have carried: "this key diverged once".
        val divergedEarlier = setOf("key_openapsaimi_high_bg_max_smb")
        assertThat(
            TpoRevertPolicy.shouldRestore(
                liveValue = 1.25,
                overlayValue = 1.25,
                observedDivergence = divergedEarlier.contains("key_openapsaimi_high_bg_max_smb"),
            )
        ).isTrue()
    }

    @Test
    fun anEarlierDivergenceDoesNotByItselfBlockARestore() {
        assertThat(
            TpoRevertPolicy.shouldRestore(liveValue = 2.20, overlayValue = 1.25, observedDivergence = true)
        ).isFalse()
        assertThat(
            TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = 1.25, observedDivergence = true)
        ).isTrue()
    }
}
