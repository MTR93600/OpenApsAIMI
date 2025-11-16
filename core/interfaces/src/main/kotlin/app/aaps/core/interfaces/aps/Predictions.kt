package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Serializable
data class Predictions(
    var IOB: List<Int>? = null,
    var ZT: List<Int>? = null,
    var COB: List<Int>? = null,
    var aCOB: List<Int>? = null, // AMA only
    var UAM: List<Int>? = null,
    var scenarioConfidence: Map<String, Double>? = null,
    var percentileBands: List<PredictionPercentileBand>? = null,
    var annotations: List<PredictionAnnotation>? = null
)

@Serializable
data class PredictionPercentileBand(
    val offsetMinutes: Int,
    val p05: Int,
    val p50: Int,
    val p95: Int
)

@Serializable
enum class PredictionAnnotationType {
    MEAL_MODE,
    MEAL_IOB_RELAX,
    NIGHT_GROWTH,
    LATE_FAT
}

@Serializable
data class PredictionAnnotation(
    val type: PredictionAnnotationType,
    val label: String,
    val offsetMinutes: Int
)