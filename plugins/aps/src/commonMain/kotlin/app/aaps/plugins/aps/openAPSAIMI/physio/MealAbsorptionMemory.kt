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
        waveCount = 0
        lastDeltaMgdlPer5 = null
        lastGapMgdl = null
        lastBestTerminalMgdl = null
    }
}
