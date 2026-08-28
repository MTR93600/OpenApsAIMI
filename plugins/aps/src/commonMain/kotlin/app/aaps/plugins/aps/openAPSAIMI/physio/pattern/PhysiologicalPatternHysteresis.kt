package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

/**
 * Stabilizes dominant endocrine / recovery patterns against single-tick flips.
 */
object PhysiologicalPatternHysteresis {

    private const val HOLD_MS = 20 * 60 * 1000L
    private const val MIN_CONFIDENCE = 0.40

    private val stickyPatterns = setOf(
        PhysiologicalPatternId.DAWN_CORTISOL,
        PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
        PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
        PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
        PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
        PhysiologicalPatternId.RECOVERY_NEEDED,
        PhysiologicalPatternId.SLEEP_DEBT,
        PhysiologicalPatternId.HRV_DEPRESSED,
        PhysiologicalPatternId.POST_HYPO_REBOUND,
    )

    private var lastDominant: PhysiologicalPatternId? = null
    private var lastDominantAtMs: Long = 0L
    private var lastHeldReading: PhysiologicalPatternReading? = null

    fun stabilize(readings: List<PhysiologicalPatternReading>, nowMs: Long): List<PhysiologicalPatternReading> {
        if (readings.isEmpty()) {
            if (lastDominant != null && nowMs - lastDominantAtMs <= HOLD_MS && lastHeldReading != null) {
                return listOf(lastHeldReading!!)
            }
            clearIfExpired(nowMs)
            return emptyList()
        }

        val sorted = readings.sortedByDescending { it.confidence }
        val top = sorted.first()

        if (top.id in stickyPatterns && top.confidence >= MIN_CONFIDENCE) {
            lastDominant = top.id
            lastDominantAtMs = nowMs
            lastHeldReading = top
        }

        val held = lastHeldReading
        if (held != null &&
            held.id in stickyPatterns &&
            nowMs - lastDominantAtMs <= HOLD_MS &&
            sorted.none { it.id == held.id && it.confidence >= held.confidence }
        ) {
            return (listOf(held) + sorted.filter { it.id != held.id }).distinctBy { it.id }
        }

        clearIfExpired(nowMs)
        return sorted
    }

    fun reset() {
        lastDominant = null
        lastDominantAtMs = 0L
        lastHeldReading = null
    }

    private fun clearIfExpired(nowMs: Long) {
        if (lastDominant != null && nowMs - lastDominantAtMs > HOLD_MS) {
            reset()
        }
    }
}
