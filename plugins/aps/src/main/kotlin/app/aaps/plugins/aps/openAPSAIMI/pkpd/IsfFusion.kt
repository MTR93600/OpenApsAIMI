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

    fun fused(profileIsf: Double, tddIsf: Double, pkpdScale: Double): Double {
        val pkpdIsf = (tddIsf * pkpdScale).coerceAtLeast(1.0)
        val candidates = listOf(profileIsf, tddIsf, pkpdIsf).sorted()
        var median = candidates[1]
        median = median.coerceIn(tddIsf * bounds.minFactor, tddIsf * bounds.maxFactor)
        lastIsf?.let { prev ->
            val maxUp = prev * (1.0 + bounds.maxChangePer5Min)
            val maxDown = prev * (1.0 - bounds.maxChangePer5Min)
            median = median.coerceIn(maxDown, maxUp)
        }
        lastIsf = median
        return median
    }
}