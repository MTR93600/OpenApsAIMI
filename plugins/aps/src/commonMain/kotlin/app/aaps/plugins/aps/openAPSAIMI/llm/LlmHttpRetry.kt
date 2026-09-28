package app.aaps.plugins.aps.openAPSAIMI.llm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWaitMs

/**
 * Centralised transient-error retry policy for LLM HTTP calls (Gemini / Claude / OpenAI / DeepSeek).
 *
 * One place owns the retry/backoff policy so behaviour stays auditable and under control:
 * - Transient (server overload / network blip) → retry the SAME request with bounded exponential backoff.
 *   Covers HTTP 500/502/503/504, 529 (Anthropic "overloaded"), Google "UNAVAILABLE"/"overloaded", read timeouts.
 * - Quota (HTTP 429 / RESOURCE_EXHAUSTED / "quota") → do NOT retry the same model; the caller switches model.
 * - Anything else (400/401/404 …) → surfaced immediately, no retry.
 *
 * Retries are bounded (default 3 attempts, ~0.7s → 1.4s → 2.8s) to avoid hammering an overloaded API.
 * Callers already perform blocking HTTP on background/IO threads, so the backoff sleep is safe here
 * (never invoked from the UI thread — blocking HTTP would otherwise crash with NetworkOnMainThreadException).
 *
 * ### Why the logger is a parameter and not a field
 *
 * This stayed an `object` and takes its [AAPSLogger] at the call site, rather than becoming a
 * Metro-injected class. Three reasons. [isTransientStatus], [isTransient] and [isQuota] are pure
 * tests on a status code or a message, and one of them is called from inside a request body where a
 * dependency-injected helper would be a needless hop. The object holds no state, so nothing here
 * would benefit from a scope. And the blast radius is identical either way: the four classes that
 * retry would each have gained a constructor parameter regardless, so injection would have bought
 * nothing for the same cost.
 *
 * The log line is not optional. It is the only record that a retry storm happened, and it names the
 * attempt, the wait and the error, so it stays a required argument rather than something a caller
 * can leave out.
 */
object LlmHttpRetry {

    private const val TAG = "AIMI_LLM_RETRY"

    // Matches both message shapes the executors emit: "… (503): …" (AiCoachingService) and "HTTP 503: …" (vision/auditor/physio).
    private val TRANSIENT_MESSAGE_MARKERS = listOf("503", "500", "502", "504", "529", "unavailable", "overloaded", "timed out", "timeout")
    private val QUOTA_MESSAGE_MARKERS = listOf("429", "quota", "resource_exhausted")
    private val TRANSIENT_STATUS_CODES = setOf(500, 502, 503, 504, 529)

    /** For providers that hold the raw HTTP status (Claude / OpenAI / DeepSeek) before wrapping it in an exception. */
    fun isTransientStatus(httpCode: Int): Boolean = httpCode in TRANSIENT_STATUS_CODES

    /** Transient = worth retrying the SAME request. Matches on the message emitted by the executors, e.g. "… (503): …". */
    fun isTransient(e: Throwable): Boolean {
        val m = e.message?.lowercase() ?: return false
        return TRANSIENT_MESSAGE_MARKERS.any { m.contains(it) }
    }

    /** Quota exhaustion → caller should switch to a higher-quota fallback model instead of retrying the same one. */
    fun isQuota(e: Throwable): Boolean {
        val m = e.message?.lowercase() ?: return false
        return QUOTA_MESSAGE_MARKERS.any { m.contains(it) }
    }

    /**
     * Runs [block], retrying ONLY on transient errors with bounded exponential backoff. Quota and other
     * errors propagate immediately so the caller can decide (model fallback / surface to user).
     *
     * @param aapsLogger where the one retry line goes. See the class note on why this is a parameter.
     * @param wait how the backoff is waited out. The default is the real platform sleep; a test
     *   passes its own so it can read back the exact wait that was asked for without spending it.
     *   A `wait` that answers `false` means the thread was interrupted, and the error being retried
     *   is then thrown as-is - which is what the Android code did before this moved to shared code.
     */
    fun <T> withTransientRetry(
        aapsLogger: AAPSLogger,
        maxAttempts: Int = 3,
        baseDelayMs: Long = 700L,
        wait: (Long) -> Boolean = ::aimiWaitMs,
        block: () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                attempt++
                if (attempt >= maxAttempts || !isTransient(e)) throw e
                val backoffMs = baseDelayMs shl (attempt - 1) // 700, 1400, 2800 …
                aapsLogger.warn(LTag.AIMI, "[$TAG] Transient LLM error (attempt $attempt/$maxAttempts) — retry in ${backoffMs}ms: ${e.message?.take(140)}")
                if (!wait(backoffMs)) throw e
            }
        }
    }
}
