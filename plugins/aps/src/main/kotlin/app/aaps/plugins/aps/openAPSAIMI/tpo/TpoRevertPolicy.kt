package app.aaps.plugins.aps.openAPSAIMI.tpo

import kotlin.math.abs

/**
 * Decides, one key at a time, whether a finished time-period override may put the user's value back.
 *
 * ## The question
 *
 * A session stores the value it found (`baseline`) and the value it wrote (`overlay`). At revert time
 * there is exactly one thing worth knowing: **is the value in force still the one the session
 * wrote?** If it is, nothing else has touched the key and the user's value must come back. If it is
 * not, somebody else owns the key now and the session must leave it alone.
 *
 * ## What this replaces
 *
 * The session used to carry a `userOwnedKeys` set, added to on every tick where the live value
 * differed from the overlay, and the revert skipped every key in it. That set was **sticky**: one
 * tick of divergence marked the key for the rest of the session, and the revert then skipped it for
 * good — so the session's own value stayed in the preferences forever and the user's value was
 * silently lost. A transient was enough: another writer, a value the loop scaled for a moment, or the
 * user changing a setting and changing it straight back.
 *
 * Deciding at revert time removes the stickiness. The failure it cannot avoid is much milder and much
 * rarer: if somebody deliberately sets a key to the very value the session wrote, the revert puts the
 * baseline back against their wish. No mechanism can tell that apart from the session's own write, and
 * losing a baseline for good is the worse of the two.
 */
object TpoRevertPolicy {

    /** How close two stored doubles must be to count as the same value. */
    const val DOUBLE_TOLERANCE: Double = 0.0001

    /**
     * True when the session may restore the baseline for this key.
     *
     * @param liveValue the value in force now, or null when it could not be read — a key that cannot
     *   be read is never written to.
     * @param overlayValue the value the session wrote, or null when the session never wrote this key.
     * @param observedDivergence whether a divergence was seen earlier in the session. Recorded for the
     *   exported diagnostics only; it deliberately does **not** block a restore, because that is the
     *   stickiness this policy exists to remove.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldRestore(
        liveValue: Any?,
        overlayValue: Any?,
        observedDivergence: Boolean = false,
    ): Boolean {
        if (liveValue == null || overlayValue == null) return false
        return sameValue(liveValue, overlayValue)
    }

    /** Value equality as the preference store sees it: doubles within [DOUBLE_TOLERANCE], the rest exact. */
    fun sameValue(a: Any, b: Any): Boolean =
        when {
            a is Double && b is Double   -> abs(a - b) < DOUBLE_TOLERANCE
            a is Boolean && b is Boolean -> a == b
            else                         -> a.toString() == b.toString()
        }
}
