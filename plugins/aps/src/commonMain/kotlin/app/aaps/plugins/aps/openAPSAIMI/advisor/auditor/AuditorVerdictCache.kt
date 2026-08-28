package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import kotlin.concurrent.Volatile

/**
 * Thread-safe cache for AI Auditor verdicts
 * Enables synchronous access to async auditor results
 */
object AuditorVerdictCache {

    private const val DEFAULT_KEY = "LATEST"
    private val lock = AapsLock()
    private val cache = mutableMapOf<String, CachedVerdict>()
    @Volatile
    private var currentBgTimestampMs: Long? = null

    data class CachedVerdict(
        val verdict: AuditorVerdict,
        val result: DecisionResult,
        val timestamp: Long,
        val bgTimestampMs: Long? = null,
    )

    fun update(verdict: AuditorVerdict, result: DecisionResult) {
        update(DEFAULT_KEY, verdict, result)
    }

    fun update(key: String, verdict: AuditorVerdict, result: DecisionResult) {
        lock.withLock {
            cache[key] = CachedVerdict(
                verdict = verdict,
                result = result,
                timestamp = aimiWallClockMs(),
                bgTimestampMs = currentBgTimestampMs,
            )
        }
    }

    fun update(verdict: AuditorVerdict, result: DecisionResult, bgTimestampMs: Long?) {
        update(DEFAULT_KEY, verdict, result, bgTimestampMs)
    }

    fun update(key: String, verdict: AuditorVerdict, result: DecisionResult, bgTimestampMs: Long?) {
        lock.withLock {
            if (bgTimestampMs != null && bgTimestampMs > 0L) {
                currentBgTimestampMs = bgTimestampMs
            }
            cache[key] = CachedVerdict(
                verdict = verdict,
                result = result,
                timestamp = aimiWallClockMs(),
                bgTimestampMs = bgTimestampMs,
            )
        }
    }

    fun get(maxAgeMs: Long = 300_000): CachedVerdict? {
        return get(DEFAULT_KEY, maxAgeMs)
    }

    fun get(key: String, maxAgeMs: Long): CachedVerdict? {
        return lock.withLock {
            val cached = cache[key] ?: return@withLock null
            val age = aimiWallClockMs() - cached.timestamp
            if (age > maxAgeMs) {
                cache.remove(key) // Proactive TTL cleanup
                return@withLock null
            }
            cached
        }
    }

    fun getDisplayable(maxAgeMs: Long = 300_000): CachedVerdict? {
        return resolveForDisplay(maxAgeMs)?.takeIf { it.alignedWithCurrentBg }?.cached
    }

    /**
     * Latest verdict within TTL, with flag when CGM timestamp advanced since audit.
     */
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

    fun noteCurrentBg(bgTimestampMs: Long?) {
        if (bgTimestampMs != null && bgTimestampMs > 0L) {
            currentBgTimestampMs = bgTimestampMs
        }
    }

    fun getAgeMs(): Long? {
        return getAgeMs(DEFAULT_KEY)
    }

    fun getAgeMs(key: String): Long? {
        return lock.withLock {
            val cached = cache[key] ?: return@withLock null
            aimiWallClockMs() - cached.timestamp
        }
    }

    fun clear() {
        lock.withLock {
            cache.clear()
            currentBgTimestampMs = null
        }
    }
}
