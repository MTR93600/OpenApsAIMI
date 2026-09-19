package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.concurrent.Volatile

/**
 * Cross-tick memory for meal absorption phases (120 min window).
 */
object MealAbsorptionMemory {

    const val MEMORY_WINDOW_MS = 120L * 60L * 1000L

    @Volatile
    var lastPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE

    @Volatile
    var lastActiveAtMs: Long = 0L

    /**
     * Time the current absorption episode started, not the time it was last seen.
     *
     * [lastActiveAtMs] is refreshed on every active tick, so it always says "a few minutes ago" and
     * can never tell how old the episode is. This field is written once, when the phase turns
     * active while it was not, and then stays put until [reset]. Use [onsetAgeMin] to ask how far
     * into the episode the loop is.
     */
    @Volatile
    var onsetAtMs: Long = 0L

    @Volatile
    var waveCount: Int = 0

    @Volatile
    var lastDeltaMgdlPer5: Double? = null

    @Volatile
    var lastGapMgdl: Double? = null

    @Volatile
    var lastBestTerminalMgdl: Double? = null

    fun isActive(nowMs: Long): Boolean =
        lastPhase.isActive && lastActiveAtMs > 0L && (nowMs - lastActiveAtMs) < MEMORY_WINDOW_MS

    /** Minutes since the episode started, or `null` when there is no episode. */
    fun onsetAgeMin(nowMs: Long): Double? = onsetAtMs.takeIf { it > 0L }?.let { (nowMs - it) / 60_000.0 }

    fun update(output: MealAbsorptionPhaseEngine.Output, nowMs: Long) {
        val prev = lastPhase
        if (output.phase.isActive) {
            if (prev.isActive &&
                (output.phase == MealAbsorptionPhase.SECOND_WAVE ||
                    (prev != MealAbsorptionPhase.SECOND_WAVE && output.phase == MealAbsorptionPhase.FIRST_WAVE))
            ) {
                if (output.phase == MealAbsorptionPhase.SECOND_WAVE && prev != MealAbsorptionPhase.SECOND_WAVE) {
                    waveCount += 1
                } else if (prev == MealAbsorptionPhase.NONE || prev == MealAbsorptionPhase.INTER_WAVE) {
                    if (waveCount == 0) waveCount = 1
                }
            } else if (!prev.isActive && output.phase == MealAbsorptionPhase.FIRST_WAVE) {
                waveCount = 1
            }
            // Onset is stamped once per episode: when the phase turns active while it was not, or
            // when the stamp is missing. Later active ticks must not move it.
            if (!prev.isActive || onsetAtMs == 0L) onsetAtMs = nowMs
            lastPhase = output.phase
            lastActiveAtMs = nowMs
        } else if (!isActive(nowMs)) {
            reset()
        }
        lastDeltaMgdlPer5 = output.deltaMgdlPer5
        lastGapMgdl = output.gapMgdl
        lastBestTerminalMgdl = output.bestTerminalMgdl
    }

    fun reset() {
        lastPhase = MealAbsorptionPhase.NONE
        lastActiveAtMs = 0L
        onsetAtMs = 0L
        waveCount = 0
        lastDeltaMgdlPer5 = null
        lastGapMgdl = null
        lastBestTerminalMgdl = null
    }
}
