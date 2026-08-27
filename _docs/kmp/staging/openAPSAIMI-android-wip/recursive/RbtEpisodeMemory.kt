package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.max

/**
 * Macro-scale episodic memory for post-hypo rebound and chaotic intervals (τ180 semantics).
 * Complements per-scale [RecursiveBeliefMemory] belief-echo rings with clinically named episodes.
 *
 * Post-hypo TTL is depth-scaled (aligned with [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2]
 * post-hypo windows): LIGHT nadir (≥60) → 30 min from episode start; DEEP nadir (&lt;60) → 45 min.
 * Aggressive rise exit ([app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit]) clears
 * the post-hypo episode immediately.
 */
object RbtEpisodeMemory {

    private const val POST_HYPO_START_THRESHOLD = 0.60
    /** Light hypo (nadir ≥ 60 mg/dL): 30 min from episode start. */
    private const val POST_HYPO_TTL_LIGHT_MS = 30L * 60L * 1000L
    /** Deep hypo (nadir &lt; 60 mg/dL): 45 min from episode start. */
    private const val POST_HYPO_TTL_DEEP_MS = 45L * 60L * 1000L
    private const val DEEP_HYPO_NADIR_MGDL = 60.0
    private const val CHAOS_START_THRESHOLD = 0.72
    private const val CHAOS_TTL_MS = 45L * 60L * 1000L
    private const val MEAL_EXTEND_POST_HYPO_MEAL_PROB = 0.55

    enum class EpisodeKind {
        POST_HYPO_REBOUND,
        CHAOTIC,
    }

    data class EpisodeState(
        val kind: EpisodeKind,
        val startedAtMs: Long,
        val lastSeenAtMs: Long,
        val peakScore: Double,
        val tickCount: Int,
        /** True when the nadir that opened the episode was &lt; 60 mg/dL. */
        val deepHypo: Boolean = false,
    ) {
        fun ageMinutes(nowMs: Long): Double =
            ((nowMs - startedAtMs).coerceAtLeast(0L) / 60_000.0)

        fun isExpired(nowMs: Long): Boolean {
            return when (kind) {
                // Wall-clock from start (not lastSeen) so a sticky postHypoProb cannot refresh forever.
                EpisodeKind.POST_HYPO_REBOUND -> {
                    val ttl = if (deepHypo) POST_HYPO_TTL_DEEP_MS else POST_HYPO_TTL_LIGHT_MS
                    nowMs - startedAtMs > ttl
                }
                EpisodeKind.CHAOTIC -> nowMs - lastSeenAtMs > CHAOS_TTL_MS
            }
        }
    }

    @Volatile
    private var active: EpisodeState? = null

    fun tick(
        nowMs: Long,
        postHypoReboundProb: Double,
        chaosScore: Double,
        mealProb: Double,
        recentNadirBgMgdl: Double? = null,
        aggressiveRiseExit: Boolean = false,
    ): EpisodeState? {
        if (aggressiveRiseExit && active?.kind == EpisodeKind.POST_HYPO_REBOUND) {
            active = null
            return null
        }

        val current = active?.takeUnless { it.isExpired(nowMs) }
        val postHypoSignal = postHypoReboundProb.coerceIn(0.0, 1.0)
        val chaosSignal = chaosScore.coerceIn(0.0, 1.0)
        val deepHypoSignal = recentNadirBgMgdl != null && recentNadirBgMgdl < DEEP_HYPO_NADIR_MGDL

        val candidateKind = when {
            chaosSignal >= CHAOS_START_THRESHOLD -> EpisodeKind.CHAOTIC
            postHypoSignal >= POST_HYPO_START_THRESHOLD -> EpisodeKind.POST_HYPO_REBOUND
            else -> null
        }

        active = when {
            candidateKind == null && current == null -> null
            candidateKind == null -> current
            current == null -> EpisodeState(
                kind = candidateKind,
                startedAtMs = nowMs,
                lastSeenAtMs = nowMs,
                peakScore = max(postHypoSignal, chaosSignal),
                tickCount = 1,
                deepHypo = if (candidateKind == EpisodeKind.POST_HYPO_REBOUND) deepHypoSignal else false,
            )
            current.kind == candidateKind || candidateKind == EpisodeKind.CHAOTIC -> {
                current.copy(
                    kind = if (candidateKind == EpisodeKind.CHAOTIC) EpisodeKind.CHAOTIC else current.kind,
                    lastSeenAtMs = nowMs,
                    peakScore = max(current.peakScore, max(postHypoSignal, chaosSignal)),
                    tickCount = current.tickCount + 1,
                    deepHypo = current.deepHypo ||
                        (candidateKind == EpisodeKind.POST_HYPO_REBOUND && deepHypoSignal),
                )
            }
            current.kind == EpisodeKind.POST_HYPO_REBOUND &&
                candidateKind == EpisodeKind.POST_HYPO_REBOUND &&
                mealProb >= MEAL_EXTEND_POST_HYPO_MEAL_PROB -> {
                // Meal context may refresh lastSeen, but expiry still uses startedAt + depth TTL.
                current.copy(
                    lastSeenAtMs = nowMs,
                    peakScore = max(current.peakScore, postHypoSignal),
                    tickCount = current.tickCount + 1,
                    deepHypo = current.deepHypo || deepHypoSignal,
                )
            }
            else -> EpisodeState(
                kind = candidateKind,
                startedAtMs = nowMs,
                lastSeenAtMs = nowMs,
                peakScore = max(postHypoSignal, chaosSignal),
                tickCount = 1,
                deepHypo = if (candidateKind == EpisodeKind.POST_HYPO_REBOUND) deepHypoSignal else false,
            )
        }
        active = active?.takeUnless { it.isExpired(nowMs) }
        return active
    }

    fun activeEpisode(nowMs: Long): EpisodeState? =
        active?.takeUnless { it.isExpired(nowMs) }

    fun clearForTests() {
        active = null
    }
}
