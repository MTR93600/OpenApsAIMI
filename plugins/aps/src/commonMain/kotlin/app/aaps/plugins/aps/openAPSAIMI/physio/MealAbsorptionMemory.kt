package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.concurrent.Volatile

/**
 * Cross-tick memory for meal absorption phases: one episode per meal, at most [MAX_EPISODE_MS] long.
 *
 * ## Why the episode is bounded (2026-10-21, report F10)
 *
 * The window used to be refreshed by **any** active tick. But INTER_WAVE, SECOND_WAVE and
 * PEAK_CORRECTION can only be classified while this memory is active, and the hysteresis turns up to
 * 10 NONE ticks into the previous phase: the memory was its own evidence. Measured on 2026-09-21 →
 * 10-21: active on 98.6 % of ticks (548 of 562 night ticks), episodes up to 113 h, median age 45 h.
 *
 * Now:
 * - only [Evidence] refreshes the window: a FIRST_WAVE or LATE_FAT predicate of the raw
 *   classification (never a held copy), a declared meal, or entered carbs;
 * - the episode closes [MEMORY_WINDOW_MS] after the last evidence, or [MAX_EPISODE_MS] after its onset;
 * - a FIRST_WAVE more than [NEW_ONSET_GAP_MS] after the previous one is a new meal: new onset, one wave;
 * - a declared meal or carbs only open an episode on their rising edge, so carbs that stay on board
 *   for hours cannot reopen it right after the max age closed it.
 */
object MealAbsorptionMemory {

    const val MEMORY_WINDOW_MS = 120L * 60L * 1000L

    /** Longest episode: the end of the late-fat window (120–420 min). */
    const val MAX_EPISODE_MS = 420L * 60L * 1000L

    /** A FIRST_WAVE this long after the previous one starts a new episode. */
    const val NEW_ONSET_GAP_MS = 60L * 60L * 1000L

    /** What may keep an episode alive. Phases that need the memory to exist are not evidence. */
    data class Evidence(
        val firstWave: Boolean = false,
        val lateFat: Boolean = false,
        val declaredMeal: Boolean = false,
        val cob: Boolean = false,
    ) {
        val any: Boolean get() = firstWave || lateFat || declaredMeal || cob

        companion object {
            val NONE = Evidence()

            /** Evidence read from a phase alone (callers without the raw classification). */
            fun of(output: MealAbsorptionPhaseEngine.Output) = Evidence(
                firstWave = output.phase == MealAbsorptionPhase.FIRST_WAVE,
                lateFat = output.phase == MealAbsorptionPhase.LATE_FAT,
            )
        }
    }

    enum class ClosedBy { NO_EVIDENCE, MAX_AGE }

    @Volatile
    var lastPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE

    @Volatile
    var lastActiveAtMs: Long = 0L

    /**
     * Time the current absorption episode started, not the time it was last seen.
     *
     * [lastActiveAtMs] is refreshed on every active tick, so it always says "a few minutes ago" and
     * can never tell how old the episode is. This field is written when an episode opens or restarts,
     * and then stays put until it closes. Use [onsetAgeMin] to ask how far into the episode the loop is.
     */
    @Volatile
    var onsetAtMs: Long = 0L

    /** Last tick that carried [Evidence]. The window runs from here. */
    @Volatile
    var lastEvidenceAtMs: Long = 0L

    @Volatile
    var lastFirstWaveAtMs: Long = 0L

    /** Why the last episode closed, `null` before the first close. Export only. */
    @Volatile
    var lastClosedBy: ClosedBy? = null

    @Volatile
    var lastClosedAtMs: Long = 0L

    @Volatile
    var waveCount: Int = 0

    @Volatile
    var lastDeltaMgdlPer5: Double? = null

    @Volatile
    var lastGapMgdl: Double? = null

    @Volatile
    var lastBestTerminalMgdl: Double? = null

    @Volatile
    private var prevDeclaredMeal: Boolean = false

    @Volatile
    private var prevCob: Boolean = false

    fun isActive(nowMs: Long): Boolean =
        lastPhase.isActive &&
            onsetAtMs > 0L && (nowMs - onsetAtMs) < MAX_EPISODE_MS &&
            lastEvidenceAtMs > 0L && (nowMs - lastEvidenceAtMs) < MEMORY_WINDOW_MS

    /** Minutes since the episode started, or `null` when there is no episode. */
    fun onsetAgeMin(nowMs: Long): Double? = onsetAtMs.takeIf { it > 0L }?.let { (nowMs - it) / 60_000.0 }

    fun update(output: MealAbsorptionPhaseEngine.Output, nowMs: Long, evidence: Evidence = Evidence.of(output)) {
        if (onsetAtMs > 0L) {
            val closeBy = when {
                nowMs - onsetAtMs >= MAX_EPISODE_MS -> ClosedBy.MAX_AGE
                nowMs - lastEvidenceAtMs >= MEMORY_WINDOW_MS -> ClosedBy.NO_EVIDENCE
                else -> null
            }
            if (closeBy != null) close(closeBy, nowMs)
        }
        val declaredEdge = evidence.declaredMeal && !prevDeclaredMeal
        val cobEdge = evidence.cob && !prevCob
        prevDeclaredMeal = evidence.declaredMeal
        prevCob = evidence.cob

        if (output.phase.isActive) {
            val prev = lastPhase
            if (onsetAtMs == 0L) {
                // No open episode: only fresh evidence opens one.
                if (!(evidence.firstWave || evidence.lateFat || declaredEdge || cobEdge)) {
                    carry(output)
                    return
                }
                onsetAtMs = nowMs
                lastEvidenceAtMs = nowMs
                lastFirstWaveAtMs = nowMs
                waveCount = 1
            } else {
                if (evidence.firstWave && nowMs - lastFirstWaveAtMs >= NEW_ONSET_GAP_MS) {
                    onsetAtMs = nowMs
                    waveCount = 1
                } else if (prev.isActive &&
                    (output.phase == MealAbsorptionPhase.SECOND_WAVE ||
                        (prev != MealAbsorptionPhase.SECOND_WAVE && output.phase == MealAbsorptionPhase.FIRST_WAVE))
                ) {
                    if (output.phase == MealAbsorptionPhase.SECOND_WAVE && prev != MealAbsorptionPhase.SECOND_WAVE) {
                        waveCount += 1
                    } else if (prev == MealAbsorptionPhase.NONE || prev == MealAbsorptionPhase.INTER_WAVE) {
                        if (waveCount == 0) waveCount = 1
                    }
                }
                if (evidence.any) lastEvidenceAtMs = nowMs
                if (evidence.firstWave) lastFirstWaveAtMs = nowMs
            }
            lastPhase = output.phase
            lastActiveAtMs = nowMs
        } else if (!isActive(nowMs) && onsetAtMs > 0L) {
            close(ClosedBy.NO_EVIDENCE, nowMs)
        }
        carry(output)
    }

    private fun carry(output: MealAbsorptionPhaseEngine.Output) {
        lastDeltaMgdlPer5 = output.deltaMgdlPer5
        lastGapMgdl = output.gapMgdl
        lastBestTerminalMgdl = output.bestTerminalMgdl
    }

    private fun close(by: ClosedBy, nowMs: Long) {
        lastPhase = MealAbsorptionPhase.NONE
        lastActiveAtMs = 0L
        onsetAtMs = 0L
        lastEvidenceAtMs = 0L
        lastFirstWaveAtMs = 0L
        waveCount = 0
        lastClosedBy = by
        lastClosedAtMs = nowMs
    }

    fun reset() {
        lastPhase = MealAbsorptionPhase.NONE
        lastActiveAtMs = 0L
        onsetAtMs = 0L
        lastEvidenceAtMs = 0L
        lastFirstWaveAtMs = 0L
        lastClosedBy = null
        lastClosedAtMs = 0L
        waveCount = 0
        lastDeltaMgdlPer5 = null
        lastGapMgdl = null
        lastBestTerminalMgdl = null
        prevDeclaredMeal = false
        prevCob = false
    }
}
