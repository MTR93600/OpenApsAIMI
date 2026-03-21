package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class IsfFusionBounds(
    val minFactor: Double = 0.75,
    val maxFactor: Double = 1.25,
    val maxChangePer5Min: Double = 0.03
)

class IsfFusion(
    private val bounds: IsfFusionBounds = IsfFusionBounds()
) {
    private var lastIsf: Double? = null

    fun fused(profileIsf: Double, tddIsf: Double, pkpdScale: Double, isRising: Boolean = false, aggressionMultiplier: Double = 1.0): Double {
        val pkpdIsf = (tddIsf * pkpdScale).coerceAtLeast(1.0)
        val candidates = listOf(profileIsf, tddIsf, pkpdIsf).sorted()
        var median = candidates[1]
        
        // 🛡️ SAFETY: If BG is rising, never let the ISF be weaker (larger) than profile ISF.
        // If profile=5 and TDD=7, during rise we must use 5.
        if (isRising && median > profileIsf) {
            median = profileIsf
        }

        // 🚀 VELOCITY BOOST: Apply reduction factor for aggression
        median *= aggressionMultiplier

        // 🛡️ DYNAMIC BOUNDS: Allow more aggression during rise, but stay safe
        val minSafeIsf = minOf(profileIsf, tddIsf * bounds.minFactor) * (if (isRising) 0.8 else 1.0)
        median = median.coerceIn(minSafeIsf, tddIsf * (bounds.maxFactor * 1.5)) 

        lastIsf?.let { prev ->
            val maxUp = prev * (1.0 + bounds.maxChangePer5Min)
            val maxDown = prev * (0.85 - bounds.maxChangePer5Min) // [MTR FIX] Slew rate: allow 15% drop per tick for massive meal response
            median = median.coerceIn(maxDown, maxUp)
        }
        lastIsf = median
        return median
    }
}