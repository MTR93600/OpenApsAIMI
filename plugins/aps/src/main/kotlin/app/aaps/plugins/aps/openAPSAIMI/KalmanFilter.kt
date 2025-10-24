package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Classe de filtre de Kalman simple permettant de lisser les mesures d'ISF.
 */
class KalmanFilter(
    var stateEstimate: Double,
    var estimationError: Double,
    var processVariance: Double,
    var measurementVariance: Double
) {
    /**
     * Mise à jour du filtre avec une nouvelle mesure.
     */
    fun update(measurement: Double): Double {
        // Étape de prédiction
        val prediction = stateEstimate
        val predictionError = estimationError + processVariance

        // Calcul du gain de Kalman
        val kalmanGain = predictionError / (predictionError + measurementVariance)

        // Mise à jour de l'estimation de l'état
        stateEstimate = prediction + kalmanGain * (measurement - prediction)
        estimationError = (1 - kalmanGain) * predictionError

        return stateEstimate
    }
}

/**
 * Calculateur de l’ISF utilisant une approche par filtre de Kalman.
 *
 * L’estimation de l’ISF se base sur une mesure brute obtenue avec une formule classique,
 * puis le filtre de Kalman lisse cette valeur pour obtenir une estimation plus stable et réactive.
 */
class KalmanISFCalculator(
    private val tddCalculator: TddCalculator,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {
    companion object {
        private const val MIN_ISF = 5.0
        private const val MAX_ISF = 300.0
        private const val BASE_CONSTANT = 75.0
        private const val SCALING_FACTOR = 1800.0
    }

    // Augmente la variance de processus pour plus de réactivité
    private val kalmanFilter = KalmanFilter(
        stateEstimate = 15.0,
        estimationError = 5.0,
        processVariance = 10.0,
        measurementVariance = 1.0
    )

    private fun computeEffectiveTDD(): Double {
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        return (0.2 * tdd7D) + (0.4 * tdd2Days) + (0.4 * tddDaily)
    }

    private fun computeRawISF(glucose: Double): Double {
        val effectiveTDD = computeEffectiveTDD()
        val safeTDD = if (effectiveTDD < 1.0) 1.0 else effectiveTDD

        // Apply a progressive reduction in ISF based on increasing glucose levels
        val bgFactor = when {
            glucose >= 180.0 -> 0.2  // Maximum reduction at high glucose levels
            glucose >= 160.0 -> 0.3
            glucose >= 140.0 -> 0.5
            glucose >= 130.0 -> 0.7
            glucose >= 115.0 -> 0.8
            glucose >= 100.0 -> 0.9
            else -> 1.0
        }

        val rawISF = (SCALING_FACTOR / (safeTDD * ln(glucose / BASE_CONSTANT + 1))) * bgFactor
        return rawISF.coerceIn(MIN_ISF, MAX_ISF)
    }

    fun calculateISF(glucose: Double, currentDelta: Double?, predictedDelta: Double?): Double {
        val rawISF = computeRawISF(glucose)
        logger.debug(LTag.APS, "Raw ISF calculé : $rawISF pour BG = $glucose")

        // Calculate the combined influence of current and predicted deltas
        var deltaInfluence = 0.0

        if (currentDelta != null) {
            deltaInfluence += abs(currentDelta)
        }

        if (predictedDelta != null) {
            deltaInfluence += abs(predictedDelta)
        }

        // Set new measurement variance based on combined delta influence
        var newMeasurementVariance = when {
            deltaInfluence > 8 -> 0.5  // High responsiveness for significant changes
            deltaInfluence > 4 -> 1.0   // Moderate responsiveness
            else -> 2.0                // Standard responsiveness
        }

        // Additional adjustment for high glucose to increase responsiveness
        if (glucose >= 110.0) {
            newMeasurementVariance = max(1.0, newMeasurementVariance * 0.8)
        }

        kalmanFilter.measurementVariance = newMeasurementVariance

        val filteredISF = kalmanFilter.update(rawISF).coerceIn(MIN_ISF, MAX_ISF)
        logger.debug(LTag.APS, "ISF filtré par Kalman : $filteredISF (variance de mesure = ${kalmanFilter.measurementVariance})")
        return filteredISF
    }

}
