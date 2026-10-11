package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lets one caller through at a time, and turns the rest away rather than queueing them.
 *
 * Pulled out of [PhysioPipeline] so the rule can be tested on its own. The pipeline needs nine
 * collaborators to build, so a test of the whole run would be mostly fakes - and the thing worth
 * proving here is one sentence: **exactly one caller enters.** That matters because the run feeds
 * `AIMIPhysioBaselineModelMTR`, whose history is keyed by the time of the run, so two overlapping
 * runs add two points for one reading and skew the mean and spread that `HrvDepression` reads.
 *
 * Turning callers away is the right answer rather than making them wait: every caller here is a
 * periodic refresh, so a skipped one comes round again shortly, while a queued one would pile up
 * behind a slow read and then feed a burst of samples at once.
 *
 * [tryEnter] is a single atomic compare-and-set, not a read followed by a write. That distinction
 * is the whole point: a check-then-set lets two callers both see "free" and both proceed, which is
 * a bug this repository has shipped before.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SingleFlightGate {

    private val busy = AtomicBoolean(false)

    /** `true` when the caller may proceed, and then it owns the gate until it calls [exit]. */
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    /** Hands the gate back. Safe to call when not held; it simply leaves the gate free. */
    fun exit() {
        busy.store(false)
    }
}
