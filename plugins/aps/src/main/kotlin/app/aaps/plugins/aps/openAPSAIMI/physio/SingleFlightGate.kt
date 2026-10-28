package app.aaps.plugins.aps.openAPSAIMI.physio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets one caller through at a time, and turns the others away instead of making them wait.
 *
 * Kept as its own class so the rule can be tested alone. The thing to prove is one sentence:
 * **exactly one caller enters.** That matters because the physio run feeds
 * [AIMIPhysioBaselineModelMTR], whose per-run history is keyed by the time of the run. Two
 * overlapping runs add two points for one HRV reading, and that skews the mean and the spread
 * that [HrvDepression] reads.
 *
 * Turning callers away is right here: every caller is a periodic refresh, so a skipped one comes
 * back soon, while a queued one would wait behind a slow read and then add a burst of samples.
 *
 * [tryEnter] is one atomic compare-and-set, not a read followed by a write. A check-then-set lets
 * two callers both see "free" and both go in.
 */
internal class SingleFlightGate {

    private val busy = AtomicBoolean(false)

    /** `true` when the caller may go on. It then owns the gate until it calls [exit]. */
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    /** Gives the gate back. Safe to call when not held: the gate simply stays free. */
    fun exit() {
        busy.set(false)
    }
}
