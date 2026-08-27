package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import java.util.concurrent.ConcurrentHashMap
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult

/**
 * Thread-safe cache for AI Auditor verdicts
 * Enables synchronous access to async auditor results
 */
object AuditorVerdictCache {
    
    private const val DEFAULT_KEY = "LATEST"
    private val cache = ConcurrentHashMap<String, CachedVerdict>()
    @Volatile
    private var currentBgTimestampMs: Long? = null
    
    data class CachedVerdict(
        val verdict: AuditorVerdict,
        val result: DecisionResult,
        val timestamp: Long,
        val bgTimestampMs: Long? = null,
    )
    
    @JvmStatic
    fun update(verdict: AuditorVerdict, result: DecisionResult) {
        update(DEFAULT_KEY, verdict, result)
    }

    @JvmStatic
    fun update(key: String, verdict: AuditorVerdict, result: DecisionResult) {
        cache[key] = CachedVerdict(
            verdict = verdict,
            result = result,
            timestamp = System.currentTimeMillis(),
            bgTimestampMs = currentBgTimestampMs,
        )
    }

    @JvmStatic
    fun update(verdict: AuditorVerdict, result: DecisionResult, bgTimestampMs: Long?) {
        update(DEFAULT_KEY, verdict, result, bgTimestampMs)
    }

    @JvmStatic
    fun update(key: String, verdict: AuditorVerdict, result: DecisionResult, bgTimestampMs: Long?) {
        if (bgTimestampMs != null && bgTimestampMs > 0L) {
            currentBgTimestampMs = bgTimestampMs
        }
        cache[key] = CachedVerdict(
            verdict = verdict,
            result = result,
            timestamp = System.currentTimeMillis(),
            bgTimestampMs = bgTimestampMs,
        )
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
    @JvmOverloads
    fun getDisplayable(maxAgeMs: Long = 300_000): CachedVerdict? {
        return resolveForDisplay(maxAgeMs)?.takeIf { it.alignedWithCurrentBg }?.cached
    }

    /**
     * Latest verdict within TTL, with flag when CGM timestamp advanced since audit.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveForDisplay(maxAgeMs: Long = 300_000): ResolvedVerdict? {
        val cached = get(DEFAULT_KEY, maxAgeMs) ?: return null
        val lastBgTimestamp = currentBgTimestampMs
        val cachedBgTimestamp = cached.bgTimestampMs
        val aligned = !(
            lastBgTimestamp != null &&
                lastBgTimestamp > 0L &&
                cachedBgTimestamp != null &&
                cachedBgTimestamp > 0L &&
                cachedBgTimestamp != lastBgTimestamp
            )
        return ResolvedVerdict(cached = cached, alignedWithCurrentBg = aligned)
    }

    data class ResolvedVerdict(
        val cached: CachedVerdict,
        val alignedWithCurrentBg: Boolean,
    )

    @JvmStatic
    fun noteCurrentBg(bgTimestampMs: Long?) {
        if (bgTimestampMs != null && bgTimestampMs > 0L) {
            currentBgTimestampMs = bgTimestampMs
        }
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
        currentBgTimestampMs = null
    }
}
