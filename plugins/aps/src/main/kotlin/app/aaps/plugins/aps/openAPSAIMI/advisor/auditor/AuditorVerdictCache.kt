package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe cache for AI Auditor verdicts
 * Enables synchronous access to async auditor results
 */
object AuditorVerdictCache {
    
    private const val DEFAULT_KEY = "LATEST"
    private val cache = ConcurrentHashMap<String, CachedVerdict>()
    
    data class CachedVerdict(
        val verdict: AuditorVerdict,
        val modulation: DecisionModulator.ModulatedDecision,
        val timestamp: Long
    )
    
    @JvmStatic
    fun update(verdict: AuditorVerdict, modulation: DecisionModulator.ModulatedDecision) {
        update(DEFAULT_KEY, verdict, modulation)
    }

    @JvmStatic
    fun update(key: String, verdict: AuditorVerdict, modulation: DecisionModulator.ModulatedDecision) {
        cache[key] = CachedVerdict(verdict, modulation, System.currentTimeMillis())
    }
    
    @JvmStatic
    @JvmOverloads
    fun get(maxAgeMs: Long = 300_000): CachedVerdict? {
        return get(DEFAULT_KEY, maxAgeMs)
    }

    @JvmStatic
    fun get(key: String, maxAgeMs: Long): CachedVerdict? {
        val cached = cache[key] ?: return null
        val age = System.currentTimeMillis() - cached.timestamp
        if (age > maxAgeMs) {
            cache.remove(key) // Proactive TTL cleanup
            return null
        }
        return cached
    }
    
    @JvmStatic
    fun getAgeMs(): Long? {
        return getAgeMs(DEFAULT_KEY)
    }

    @JvmStatic
    fun getAgeMs(key: String): Long? {
        val cached = cache[key] ?: return null
        return System.currentTimeMillis() - cached.timestamp
    }
    
    @JvmStatic
    fun clear() {
        cache.clear()
    }
}
