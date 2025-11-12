package app.aaps.plugins.aps.openAPSAIMI.wcycle

class WCycleLearner {
    private val emaBasal = mutableMapOf<CyclePhase, Double>()
    private val emaSmb   = mutableMapOf<CyclePhase, Double>()
    private val alpha = 0.1
    fun update(phase: CyclePhase, needBasalScale: Double?, needSmbScale: Double?) {
        needBasalScale?.let { emaBasal[phase] = (emaBasal[phase] ?: 1.0) + alpha * (it - (emaBasal[phase] ?: 1.0)) }
        needSmbScale  ?.let { emaSmb  [phase] = (emaSmb  [phase] ?: 1.0) + alpha * (it - (emaSmb  [phase] ?: 1.0)) }
    }
    fun learnedMultipliers(phase: CyclePhase, clampMin: Double, clampMax: Double): Pair<Double, Double> {
        val b = (emaBasal[phase] ?: 1.0).coerceIn(clampMin, clampMax)
        val s = (emaSmb  [phase] ?: 1.0).coerceIn(clampMin, clampMax)
        return b to s
    }
}
