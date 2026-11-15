package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import kotlin.concurrent.Volatile

/**
 * Keeps the causal posterior of the last tick so the next tick can resolve its kinetics with it.
 *
 * The plugin resolves the DIA and peak before `determine_basal` runs, and the posterior of a tick is
 * only computed inside `determine_basal`. So the kinetics of a tick use the posterior of the tick
 * before. A posterior older than [maxAgeMs] (loop stopped, phone off) is not used: the kinetics then
 * have no context, as on the first tick.
 */
class CausalKineticsContextMemory(private val maxAgeMs: Long = MAX_AGE_MS) {

    private class Entry(val tickTimeMs: Long, val posterior: CausalStatePosterior?)

    @Volatile
    private var last: Entry? = null

    /**
     * Called at the end of a tick, with the posterior of that tick. Null (no posterior, or a failed
     * tick) clears the context for the next tick.
     */
    fun remember(tickTimeMs: Long, posterior: CausalStatePosterior?) {
        last = Entry(tickTimeMs, posterior)
    }

    /**
     * Runs [tick], then keeps the posterior that [posteriorAfterTick] reads. When [tick] throws, no
     * posterior is kept, so the next tick has no context (the behaviour before the context existed)
     * instead of the posterior of two ticks ago.
     */
    fun <T> runTick(tickTimeMs: Long, posteriorAfterTick: () -> CausalStatePosterior?, tick: () -> T): T {
        val result = try {
            tick()
        } catch (error: Throwable) {
            remember(tickTimeMs, null)
            throw error
        }
        remember(tickTimeMs, posteriorAfterTick())
        return result
    }

    /** The posterior to use for the tick at [nowMs], or null when there is none or it is too old. */
    fun forTickAt(nowMs: Long): CausalStatePosterior? {
        val entry = last ?: return null
        val ageMs = nowMs - entry.tickTimeMs
        if (ageMs < 0L || ageMs > maxAgeMs) return null
        return entry.posterior
    }

    companion object {

        /** Three loop ticks. */
        const val MAX_AGE_MS: Long = 15L * 60_000L
    }
}
