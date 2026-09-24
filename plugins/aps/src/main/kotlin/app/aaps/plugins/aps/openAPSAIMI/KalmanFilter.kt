package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

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

        /**
         * Smallest total daily dose this calculator will treat as a measurement, in units.
         *
         * Below it the value is missing data, not a small dose: nobody on insulin therapy has a real
         * daily total under this, and a near-zero one collapses the effective dose that scales the
         * whole sensitivity estimate.
         */
        const val MIN_USABLE_TDD_U: Double = 1.0

        /**
         * True when a total daily dose can be used as a measurement.
         *
         * `averageTDD(...)?.data?.totalAmount` returns 0.0 when there is no history yet — and a
         * relaxed test double returns 0.0 too. Caching that made the effective dose zero and the
         * sensitivity it produced was out by more than a factor of ten, which is how this check was
         * found: a full-suite run returned 223.29 mg/dL/U where 15.0 was expected, once, and never
         * reproduced.
         */
        fun isUsableTdd(value: Double?): Boolean =
            value != null && value.isFinite() && value >= MIN_USABLE_TDD_U
        private const val MIN_ISF = 5.0
        private const val MAX_ISF = 300.0
        private const val BASE_CONSTANT = 75.0
        private const val SCALING_FACTOR = 1800.0

        /**
         * Physiological floor, as a fraction of the smaller of the profile ISF and the TDD-implied
         * ISF.
         *
         * `computeRawISF` multiplies by a `bgFactor` of 0.2 above BG 180, so at a TDD of 55 U/day
         * the raw value drops under [MIN_ISF] from about BG 200 on and the result saturates on the
         * absolute clamp of 5.0 mg/dL/U. The outcome corpus in `ISF/DynamicSensitivityPolicy`
         * measures this patient between 18.7 and 24.3 mg/dL/U, so 5.0 is four times below the
         * lowest sensitivity ever observed. It is an artefact of the clamp, not a sensitivity.
         *
         * 0.5 is set well under the corpus on purpose. The floor is relative to the patient, never
         * an absolute number: it follows the profile ISF and the TDD, so it moves with them.
         *
         * It can only raise the fast estimate. A higher ISF makes the loop assume insulin acts more
         * strongly, so on every arithmetic dose path it doses less.
         *
         * That is not a guarantee on the neural path. The working sensitivity also reaches
         * `insulinEffect = iob * variableSensitivity / insulinDivisor`, which feeds the trend
         * indicator FEATURE of the SMB refinement network, and a model's answer is not monotone in
         * one of its inputs. What bounds that path is the model's own correction clamp of
         * `min(0.05 U, 25 % of the dose)`, not this floor. See `ISF/WorkingIsf`.
         *
         * In steady state it decides nothing. `OpenAPSAIMIPlugin` takes `max(kalmanFastIsf, isfAdj)`
         * and the blend inside `IsfAdjustmentEngine` cannot fall below 0.58 times the profile ISF
         * before its rate limiter: its weight on the bounded AF term is at most 0.6, and that term
         * is itself bounded below at 0.3 times the profile ISF, so 0.6 * 0.3 + 0.4 = 0.58. At 0.5
         * the floor stays under that, so the `max` keeps discarding this value.
         *
         * That does not hold during a transition. `IsfAdjustmentEngine` rate-limits against its own
         * previous anchor, not against the current profile ISF, and the budget is 1.67 % per
         * 5-minute cycle. When the profile ISF steps up at a block boundary - this patient goes from
         * 30 to 70 mg/dL/U at midnight, so it happens every day - the anchor stays near the old low
         * value and needs about 1.5 hours to climb back above 0.58 times the new profile ISF. In
         * that window `isfAdj` is below the floor, and the floor is what decides the value. The
         * effect there is a smaller dose.
         */
        private const val PHYSIO_FLOOR_FACTOR = 0.5
    }

    // Augmente la variance de processus pour plus de réactivité
    private val kalmanFilter = KalmanFilter(
        stateEstimate = 15.0,
        estimationError = 5.0,
        processVariance = 10.0,
        measurementVariance = 1.0
    )
    @Volatile private var cachedTdd7Days: Double? = null
    @Volatile private var cachedTdd2Days: Double? = null
    @Volatile private var cachedTdd1Day: Double? = null
    private val tddRefreshInFlight = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun computeEffectiveTDD(): Double {
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        refreshTddAsync()
        val tdd7D = cachedTdd7Days ?: tdd7P
        val tdd2Days = cachedTdd2Days ?: tdd7P
        val tddDaily = cachedTdd1Day ?: tdd7P
        return (0.2 * tdd7D) + (0.4 * tdd2Days) + (0.4 * tddDaily)
    }

    private fun refreshTddAsync() {
        if (!tddRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                // Only cache what is a measurement. A zero or absent total is missing data, and
                // caching it collapsed the effective dose that scales the whole estimate — see
                // [isUsableTdd]. A refused value leaves the previous cache, or the preference
                // fallback in [computeEffectiveTDD], in place.
                tddCalculator.averageTDD(
                    tddCalculator.calculate(7, allowMissingDays = false)
                )?.data?.totalAmount?.takeIf { isUsableTdd(it) }?.let { cachedTdd7Days = it }
                tddCalculator.averageTDD(
                    tddCalculator.calculate(2, allowMissingDays = false)
                )?.data?.totalAmount?.takeIf { isUsableTdd(it) }?.let { cachedTdd2Days = it }
                tddCalculator.averageTDD(
                    tddCalculator.calculate(1, allowMissingDays = false)
                )?.data?.totalAmount?.takeIf { isUsableTdd(it) }?.let { cachedTdd1Day = it }
            } finally {
                tddRefreshInFlight.set(false)
            }
        }
    }

    /**
     * Lowest sensitivity this calculator is allowed to report.
     *
     * Falls back to the absolute [MIN_ISF] when the TDD is unusable, so the behaviour is unchanged
     * in that case. Without the guard, `SCALING_FACTOR / 0.0` would be infinite and every value
     * would be pushed to [MAX_ISF].
     */
    private fun physiologicalFloor(effectiveTdd: Double, profileIsfMgdl: Double?): Double {
        if (!effectiveTdd.isFinite() || effectiveTdd < 1.0) return MIN_ISF
        val tddIsf = SCALING_FACTOR / effectiveTdd
        val profile = profileIsfMgdl?.takeIf { it.isFinite() && it > 0.0 }
        val base = if (profile != null) min(profile, tddIsf) else tddIsf
        return (base * PHYSIO_FLOOR_FACTOR).coerceIn(MIN_ISF, MAX_ISF)
    }

    private fun computeRawISF(glucose: Double, effectiveTDD: Double, floorIsf: Double): Double {
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
        return rawISF.coerceIn(floorIsf, MAX_ISF)
    }

    /**
     * @param profileIsfMgdl the static profile ISF for the current time of day, when the caller has
     *   it. It only lowers the floor, never raises it.
     */
    fun calculateISF(
        glucose: Double,
        currentDelta: Double?,
        predictedDelta: Double?,
        profileIsfMgdl: Double? = null
    ): Double {
        // Computed once: it drives both the raw value and the floor, and it must be the same number
        // in both.
        val effectiveTDD = computeEffectiveTDD()
        val floorIsf = physiologicalFloor(effectiveTDD, profileIsfMgdl)
        val rawISF = computeRawISF(glucose, effectiveTDD, floorIsf)
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

        // The floor is applied twice on purpose.
        // On the measurement, so the filter's internal state never learns a non-physiological value
        // during a high plateau and then carries it through the fall back down.
        // On the output, because stateEstimate starts at 15.0, which can be below the floor: the
        // first call of the process would otherwise return a value under the floor even though the
        // measurement was already bounded.
        val filteredISF = kalmanFilter.update(rawISF).coerceIn(floorIsf, MAX_ISF)
        logger.debug(LTag.APS, "ISF filtré par Kalman : $filteredISF (variance de mesure = ${kalmanFilter.measurementVariance})")
        return filteredISF
    }

}
