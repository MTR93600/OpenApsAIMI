package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

/**
 * The one proposal the loop is working from.
 *
 * Written by the auditor coroutine, read by the loop, so the slot is volatile and holds an immutable
 * value. It never expires by itself: age belongs to `AuditorProfileFactorGate`, which re-reads it on
 * every tick and knows the two different lifetimes.
 *
 * A newer proposal always replaces an older one, even a neutral newer one. A fresh look that sees no
 * reason to act is an answer, not a missing answer.
 */
object AuditorProfileFactorCache {

    @Volatile
    private var current: AuditorProfileProposal? = null

    /** Stores the newest proposal. */
    fun publish(proposal: AuditorProfileProposal) {
        current = proposal
    }

    /** The proposal the loop must judge this tick, or null when there is none. */
    fun latest(): AuditorProfileProposal? = current

    /** Forgets the proposal. For tests, and for a user who turns the auditor off. */
    fun clear() {
        current = null
    }
}
