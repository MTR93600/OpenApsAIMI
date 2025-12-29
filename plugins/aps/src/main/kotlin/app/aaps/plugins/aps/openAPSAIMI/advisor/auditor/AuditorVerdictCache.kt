package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe cache for AI Auditor verdicts
 * Enables synchronous access to async auditor results
 */
object AuditorVerdictCache {
    
    private val cache = AtomicReference<CachedVerdict?>(null)
    
    data class CachedVerdict(
        val verdict: AuditorVerdict,
        val modulation: DecisionModulator.ModulatedDecision,
        val timestamp: Long
    )
    
    fun update(verdict: AuditorVerdict, modulation: DecisionModulator.ModulatedDecision) {
        cache.set(CachedVerdict(verdict, modulation, System.currentTimeMillis()))
    }
    
    fun get(maxAgeMs: Long = 300_000): CachedVerdict? {
        val cached = cache.get() ?: return null
        val age = System.currentTimeMillis() - cached.timestamp
        if (age > maxAgeMs) return null
        return cached
    }
    
    fun getAgeMs(): Long? {
        val cached = cache.get() ?: return null
        return System.currentTimeMillis() - cached.timestamp
    }
    
    fun clear() {
        cache.set(null)
    }
}
