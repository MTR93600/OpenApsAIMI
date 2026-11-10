package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

/** Display model for evidence grouped by the hour in which glucose changed. */
data class HourlyCoefficientRow(
    val hour: Int,
    val basalProfile: Double,
    val basalObserved: Double?,
    val basalEpisodes: Int,
    val isfProfile: Double,
    val isfObserved: Double?,
    val isfEpisodes: Int,
    val icProfile: Double,
    val icObserved: Double?,
    val icEpisodes: Int,
)

data class BasalObservation(val glucoseHour: Int, val suggestedBasalUph: Double)

object HourlyCoefficientAnalysis {
    fun median(values: List<Double>, minimum: Int = 3): Double? {
        if (values.size < minimum) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
