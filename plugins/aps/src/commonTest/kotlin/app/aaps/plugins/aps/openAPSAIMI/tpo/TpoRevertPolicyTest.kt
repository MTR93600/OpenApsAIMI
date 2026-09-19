package app.aaps.plugins.aps.openAPSAIMI.tpo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A time-period override writes preference values and must put them back when the session ends.
 *
 * The question this policy answers is the only one that matters at revert time: **is the value in
 * force still the one the session wrote?** If it is, nobody else has touched it and the user's own
 * value must come back. If it is not, somebody else owns the key now and the session must leave it
 * alone.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `f3de6740ee` (file unchanged on tip `a546722609`;
 * helper `sameValue` later compares `Number` in `acdc2b118e`). Study source set:
 * [TpoRevertPolicy] is commonMain and has no mocks, so the tests live in `commonTest`
 * (`kotlin.test`) — same layout as
 * [app.aaps.plugins.aps.openAPSAIMI.risk.MealConfirmedEarlyReleaseLatchTest]. Assertions are the
 * same locks as the Truth/JUnit reference corpus. Names are camelCase (no backtick commas).
 */
class TpoRevertPolicyTest {

    @Test
    fun aValueStillEqualToWhatTheSessionWroteIsRestored() {
        assertTrue(TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = 1.25))
    }

    @Test
    fun aValueSomebodyElseHasMovedIsLeftAlone() {
        assertFalse(TpoRevertPolicy.shouldRestore(liveValue = 1.60, overlayValue = 1.25))
    }

    @Test
    fun twoDoublesWithinTheToleranceCountAsTheSameValue() {
        assertTrue(TpoRevertPolicy.shouldRestore(liveValue = 1.250_02, overlayValue = 1.25))
    }

    @Test
    fun twoDoublesOutsideTheToleranceDoNot() {
        assertFalse(TpoRevertPolicy.shouldRestore(liveValue = 1.26, overlayValue = 1.25))
    }

    @Test
    fun aBooleanStillEqualToWhatTheSessionWroteIsRestored() {
        assertTrue(TpoRevertPolicy.shouldRestore(liveValue = true, overlayValue = true))
    }

    @Test
    fun aBooleanSomebodyElseHasFlippedIsLeftAlone() {
        assertFalse(TpoRevertPolicy.shouldRestore(liveValue = false, overlayValue = true))
    }

    @Test
    fun aValueThatCouldNotBeReadIsLeftAlone() {
        assertFalse(TpoRevertPolicy.shouldRestore(liveValue = null, overlayValue = 1.25))
    }

    @Test
    fun aKeyTheSessionNeverWroteIsLeftAlone() {
        assertFalse(TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = null))
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
        assertTrue(
            TpoRevertPolicy.shouldRestore(
                liveValue = 1.25,
                overlayValue = 1.25,
                observedDivergence = divergedEarlier.contains("key_openapsaimi_high_bg_max_smb"),
            )
        )
    }

    @Test
    fun anEarlierDivergenceDoesNotByItselfBlockARestore() {
        assertFalse(
            TpoRevertPolicy.shouldRestore(liveValue = 2.20, overlayValue = 1.25, observedDivergence = true)
        )
        assertTrue(
            TpoRevertPolicy.shouldRestore(liveValue = 1.25, overlayValue = 1.25, observedDivergence = true)
        )
    }
}
