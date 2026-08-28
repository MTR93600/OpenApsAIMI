package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class MealAggressionContext(
    val mealModeActive: Boolean,
    val predictedBgMgdl: Double? = null,
    val targetBgMgdl: Double? = null
)

data class PkpdBolusSample(
    val ageMin: Double,
    val units: Double
)
