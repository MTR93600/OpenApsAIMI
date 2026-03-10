package app.aaps.plugins.aps.openAPSAIMI.carbs

object CarbsAdvisor {
    /**
     * Estime la quantité de glucides à consommer pour éviter une hypo à court terme.
     * Logique identique à ton helper existant.
     */
    fun estimateRequiredCarbs(
        bg: Double,
        targetBG: Double,
        slope: Double,
        iob: Double,
        csf: Double,
        isf: Double,
        cob: Double
    ): Int {
        val timeAhead = 20.0
        val projectedDrop = slope * timeAhead
        val insulinEffect = iob * isf
        val totalPredictedDrop = projectedDrop + insulinEffect
        val futureBG = bg - totalPredictedDrop
        if (futureBG < targetBG) {
            val bgDiff = targetBG - futureBG
            val gramsNeeded = (bgDiff / csf) - (cob * 0.2)
            return kotlin.math.max(0.0, gramsNeeded).toInt()
        }
        return 0
    }
}
