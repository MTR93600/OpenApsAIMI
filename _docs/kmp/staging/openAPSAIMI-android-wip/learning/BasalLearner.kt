package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-Scale Basal Learner
 * 
 * Replaces the old single-scale (1x/day) learner with a 3-tier approach:
 * - Short-term (30 min): Reacts to acute changes (stress, illness onset)
 * - Medium-term (6 hours): Captures intra-day patterns
 * - Long-term (24 hours): Structural adjustments based on TDD
 * 
 * Each tier uses exponentially weighted observations to prioritize recent data.
 */
@SingleIn(AppScope::class)
class BasalLearner @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    data class StatusSnapshot(
        val shortTermMultiplier: Double,
        val mediumTermMultiplier: Double,
        val longTermMultiplier: Double,
        val combinedMultiplier: Double,
        val shortBufferCount: Int,
        val mediumBufferCount: Int,
        val fastingSampleCount: Int,
        val shortUpdateCount: Long,
        val mediumUpdateCount: Long,
        val longUpdateCount: Long,
        val lastShortUpdate: Long,
        val lastMediumUpdate: Long,
        val lastLongUpdate: Long,
        val updatedAt: Long?,
    )

    private val fileName = "aimi_basal_learner.json"
    private val file by lazy { storageHelper.getAimiFile(fileName) }

    // === Multi-Scale Multipliers ===
    var shortTermMultiplier = 1.0   // Updated every 30 min
        private set
    var mediumTermMultiplier = 1.0  // Updated every 6 hours
        private set
    var longTermMultiplier = 1.0    // Updated every 24 hours
        private set

    // === Timestamps for update scheduling ===
    private var lastShortUpdate = 0L
    private var lastMediumUpdate = 0L
    private var lastLongUpdate = 0L

    // === Accumulators for each scale ===
    private val shortTermBuffer = mutableListOf<TimestampedBg>()
    private val mediumTermBuffer = mutableListOf<TimestampedBg>()

    // === Legacy accumulators (for long-term, fasting-based) ===
    private var fastingSamples = 0
    private var fastingSlopeSum = 0.0
    private var fastingBgSum = 0.0
    private val shortUpdateCount = AtomicLong(0L)
    private val mediumUpdateCount = AtomicLong(0L)
    private val longUpdateCount = AtomicLong(0L)
    private val statusRef = AtomicReference<StatusSnapshot>()

    // === Lot 4 — exclusion post-hypo ===
    /** Horodatage de la dernière hypo signalée par [onHypoDetected]. */
    private var lastHypoDetectedAt = 0L
    /** Nombre d'échantillons écartés de l'apprentissage moyen/long depuis le démarrage (télémétrie). */
    private var postHypoExcludedSamples = 0L

    // === Configuration ===
    companion object {
        private const val SHORT_INTERVAL_MS = 30 * 60 * 1000L       // 30 minutes
        private const val MEDIUM_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6 hours
        private const val LONG_INTERVAL_MS = 24 * 60 * 60 * 1000L   // 24 hours

        private const val SHORT_WINDOW_MS = 2 * 60 * 60 * 1000L     // 2 hours of data
        private const val MEDIUM_WINDOW_MS = 24 * 60 * 60 * 1000L   // 24 hours of data

        private const val TAU_SHORT_MS = 30 * 60 * 1000.0           // 30 min half-life
        private const val TAU_MEDIUM_MS = 3 * 60 * 60 * 1000.0      // 3 hour half-life

        private const val ALPHA_SHORT = 0.25   // Fast EMA
        private const val ALPHA_MEDIUM = 0.15  // Moderate EMA
        private const val ALPHA_LONG = 0.10    // Slow EMA

        /**
         * Floor of the heuristic (H) basal multiplier. It is 0.70, and it is NOT the 0.80 of the learned
         * (N) channel (`BasalNeuralLearner.RUNTIME_BASAL_FLOOR`). The two floors mean different things and
         * are kept apart on purpose.
         *
         * Read this before trusting any "never below 0.80" claim: the blend keeps the smaller of the two
         * channels as soon as either one is defensive, so this 0.70 can still reach the pump. A multiplier
         * of exactly 0.70 on 100% of ticks was the field symptom on two patient devices 40 days apart, and
         * it stayed ambiguous because several mechanisms print that same number. `n_raw` and `n_source` in
         * the `adaptive_basal` block of AIMI_Decisions.jsonl are what separates them now.
         *
         * Whether H should floor at 0.80 like N is a therapy decision that needs field evidence. Do not
         * change this value as part of a documentation or clean-up change.
         */
        private const val CLAMP_MIN = 0.70
        private const val CLAMP_MAX = 2.0

        /**
         * Lot 4 — durée pendant laquelle les échantillons qui suivent une hypo sont écartés de
         * l'apprentissage moyen/long terme. Couvre le rebond (resucrage + contre-régulation) sans geler
         * durablement l'apprentissage.
         */
        private const val POST_HYPO_EXCLUSION_MS = 3 * 60 * 60 * 1000L // 3 h

        /**
         * Lot 4 — pas de retour au neutre appliqué à chaque mise à jour. Sans lui, `onHypoDetected`
         * (×0,90 sur le terme court) était **irréversible** sur glycémie calme : `updateShortTerm` pose
         * `adjustment = 1.0` quand `|weightedError| <= 0.5`, donc `ema(prev, prev, α) = prev`, un no-op
         * algébrique. Le motif existe déjà dans `UnifiedReactivityLearner` (convergence neutre F1).
         */
        private const val NEUTRAL_CONVERGENCE_STEP = 0.02
    }

    data class TimestampedBg(val timestamp: Long, val bg: Double, val delta: Double)

    init {
        load()
        publishStatus()
    }

    /**
     * Returns the combined multiplier from all three scales.
     * Weights: Short 40%, Medium 35%, Long 25%
     */
    fun getMultiplier(): Double {
        val combined = shortTermMultiplier * 0.40 +
                       mediumTermMultiplier * 0.35 +
                       longTermMultiplier * 0.25
        return combined.coerceIn(CLAMP_MIN, CLAMP_MAX)
    }

    /**
     * Main processing function. Called every 5 minutes from DetermineBasalAIMI2.
     */
    fun process(
        currentBg: Double,
        currentDelta: Double,
        tdd7Days: Double,
        tdd30Days: Double,
        isFastingTime: Boolean
    ) {
        val now = System.currentTimeMillis()
        val observation = TimestampedBg(now, currentBg, currentDelta)

        // 🧪 Lot 4 — fenêtre d'exclusion post-hypo.
        //
        // Un rebond qui suit une hypo (resucrage ou contre-régulation) est haut et montant : il prend la
        // branche `avgBg > 150 && weightedError >= -0.5 -> ×1,12` de [updateMediumTerm], et la fenêtre
        // moyenne (TAU_MEDIUM_MS = 3 h) le surpondère justement parce qu'il est récent. L'hypo elle-même
        // n'atteint que le terme court, à ×0,90 — soit ≈ -4 % sur la combinaison. Sans exclusion, chaque
        // hypo apprenait donc au moteur à doser **plus**.
        //
        // Les échantillons de la fenêtre sont écartés des buffers moyen/long ; le terme court continue de
        // les voir, car c'est lui qui doit réagir vite à l'hypo.
        val inPostHypoWindow = now - lastHypoDetectedAt < POST_HYPO_EXCLUSION_MS
        shortTermBuffer.add(observation)
        if (inPostHypoWindow) {
            postHypoExcludedSamples++
        } else {
            mediumTermBuffer.add(observation)
        }

        // Prune old data
        pruneBuffer(shortTermBuffer, SHORT_WINDOW_MS, now)
        pruneBuffer(mediumTermBuffer, MEDIUM_WINDOW_MS, now)

        // === SHORT-TERM UPDATE (every 30 min) ===
        if (now - lastShortUpdate >= SHORT_INTERVAL_MS && shortTermBuffer.size >= 3) {
            updateShortTerm(now)
            lastShortUpdate = now
            shortUpdateCount.incrementAndGet()
        }

        // === MEDIUM-TERM UPDATE (every 6 hours) ===
        if (now - lastMediumUpdate >= MEDIUM_INTERVAL_MS && mediumTermBuffer.size >= 12) {
            updateMediumTerm(now)
            lastMediumUpdate = now
            mediumUpdateCount.incrementAndGet()
        }

        // === LONG-TERM UPDATE (every 24 hours, fasting-based) ===
        if (isFastingTime && currentBg > 70.0 && !inPostHypoWindow) {
            fastingSamples++
            fastingSlopeSum += currentDelta
            fastingBgSum += currentBg
        }

        if (now - lastLongUpdate >= LONG_INTERVAL_MS) {
            updateLongTerm(tdd7Days, tdd30Days)
            lastLongUpdate = now
            longUpdateCount.incrementAndGet()
            // Reset fasting accumulators
            fastingSamples = 0
            fastingSlopeSum = 0.0
            fastingBgSum = 0.0
        }

        save()
        publishStatus(now)
    }

    /**
     * Event-driven update: Force recalculation after hypo events.
     * Called externally when hypo is detected.
     */
    fun onHypoDetected() {
        log.info(LTag.APS, "BasalLearner: Hypo detected, reducing short-term multiplier")
        // Lot 4 — ouvre la fenêtre d'exclusion : le rebond qui suit ne doit pas entraîner à la hausse.
        lastHypoDetectedAt = System.currentTimeMillis()
        shortTermMultiplier = (shortTermMultiplier * 0.90).coerceIn(CLAMP_MIN, CLAMP_MAX)
        save()
        publishStatus(System.currentTimeMillis())
    }

    /**
     * Event-driven update: Force recalculation after persistent hyper.
     * Called externally when hyper > 180 for > 60 min.
     */
    fun onPersistentHyper() {
        // 🧪 Lot 4 — garde de cohérence : un rebond consécutif à une hypo est haut et montant, il peut
        // franchir le seuil d'hyper persistante alors qu'il est précisément l'artefact que la fenêtre
        // d'exclusion écarte de l'apprentissage. Le laisser passer ici rouvrirait par la porte
        // événementielle ce que [process] vient de fermer côté buffers.
        val now = System.currentTimeMillis()
        if (now - lastHypoDetectedAt < POST_HYPO_EXCLUSION_MS) {
            log.info(LTag.APS, "BasalLearner: Persistent hyper ignored (post-hypo rebound window)")
            return
        }
        log.info(LTag.APS, "BasalLearner: Persistent hyper detected, increasing short-term multiplier")
        shortTermMultiplier = (shortTermMultiplier * 1.10).coerceIn(CLAMP_MIN, CLAMP_MAX)
        save()
        publishStatus(now)
    }

    /**
     * Lot 4 — rapproche un multiplicateur de 1.0 d'un pas borné à chaque mise à jour.
     *
     * Sans ce terme, une réduction posée par [onHypoDetected] ne se défaisait jamais sur glycémie calme :
     * [updateShortTerm] pose `adjustment = 1.0` quand `|weightedError| <= 0.5`, ce qui rend
     * `ema(prev, prev, α) = prev` — un no-op algébrique. Le multiplicateur restait donc bloqué sous 1.0
     * jusqu'à ce qu'une tendance montante apparaisse.
     *
     * Le pas est volontairement inférieur à l'amplitude des branches d'apprentissage (×0,90 à ×1,12), pour
     * ramener vers le neutre sans jamais masquer un signal réel.
     */
    private fun converge(value: Double): Double = when {
        value > 1.0 -> max(1.0, value - NEUTRAL_CONVERGENCE_STEP)
        value < 1.0 -> min(1.0, value + NEUTRAL_CONVERGENCE_STEP)
        else        -> value
    }

    // === Private Update Functions ===

    private fun updateShortTerm(now: Long) {
        if (shortTermBuffer.isEmpty()) return

        // Compute weighted average delta (temporal decay)
        val weightedError = computeWeightedError(shortTermBuffer, now, TAU_SHORT_MS)

        // Adjust multiplier based on error
        // Positive error (rising) → need more basal → increase multiplier
        // Negative error (dropping) → need less basal → decrease multiplier
        val adjustment = when {
            weightedError > 1.0 -> 1.05   // Rising → +5%
            weightedError > 0.5 -> 1.02   // Slightly rising → +2%
            weightedError < -1.0 -> 0.95  // Dropping → -5%
            weightedError < -0.5 -> 0.98  // Slightly dropping → -2%
            else -> 1.0                   // Stable
        }

        val newValue = shortTermMultiplier * adjustment
        shortTermMultiplier = ema(shortTermMultiplier, newValue, ALPHA_SHORT)
            .let { converge(it) }
            .coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.debug(LTag.APS, "BasalLearner: Short-term update. WeightedError=${"%.2f".format(weightedError)}, " +
            "Adjustment=$adjustment, NewMultiplier=${"%.3f".format(shortTermMultiplier)}")
    }

    private fun updateMediumTerm(now: Long) {
        if (mediumTermBuffer.isEmpty()) return

        val weightedError = computeWeightedError(mediumTermBuffer, now, TAU_MEDIUM_MS)
        val avgBg = computeWeightedAvgBg(mediumTermBuffer, now, TAU_MEDIUM_MS)

        // More nuanced adjustment based on both trend and level
        val adjustment = when {
            // High & Stable/Rising → Need significantly more
            avgBg > 150 && weightedError >= -0.5 -> 1.12
            // High & Dropping → Slight increase (still elevated)
            avgBg > 150 && weightedError < -0.5 -> 1.05
            // Low & Stable/Dropping → Need less
            avgBg < 85 && weightedError <= 0.5 -> 0.90
            // Low & Rising → Slight decrease
            avgBg < 85 && weightedError > 0.5 -> 0.95
            // Normal range, adjust based on trend
            weightedError > 1.0 -> 1.06
            weightedError > 0.5 -> 1.03
            weightedError < -1.0 -> 0.94
            weightedError < -0.5 -> 0.97
            else -> 1.0
        }

        val newValue = mediumTermMultiplier * adjustment
        mediumTermMultiplier = ema(mediumTermMultiplier, newValue, ALPHA_MEDIUM)
            .let { converge(it) }
            .coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.debug(LTag.APS, "BasalLearner: Medium-term update. AvgBG=${"%.0f".format(avgBg)}, " +
            "WeightedError=${"%.2f".format(weightedError)}, NewMultiplier=${"%.3f".format(mediumTermMultiplier)}")
    }

    private fun updateLongTerm(tdd7Days: Double, tdd30Days: Double) {
        val fastingScore = if (fastingSamples > 10) fastingSlopeSum / fastingSamples else 0.0
        val avgFastingBg = if (fastingSamples > 10) fastingBgSum / fastingSamples else 100.0

        var adjustment = 1.0

        // TDD Trend Analysis
        if (tdd30Days > 0) {
            val tddRatio = tdd7Days / tdd30Days
            adjustment *= when {
                tddRatio > 1.15 -> 1.10  // Significant increase in insulin needs
                tddRatio > 1.05 -> 1.05
                tddRatio < 0.85 -> 0.90  // Significant decrease
                tddRatio < 0.95 -> 0.95
                else -> 1.0
            }
        }

        // Fasting Analysis
        adjustment *= when {
            avgFastingBg > 140 && fastingScore > -0.5 -> 1.10  // High & stable/rising
            avgFastingBg > 120 && fastingScore > 0.5 -> 1.08   // Elevated & rising
            avgFastingBg < 80 && fastingScore < 0.5 -> 0.90    // Low & stable/dropping
            fastingScore > 1.0 -> 1.06                         // Rising
            fastingScore < -1.0 -> 0.94                        // Dropping
            else -> 1.0
        }

        val newValue = longTermMultiplier * adjustment
        longTermMultiplier = ema(longTermMultiplier, newValue, ALPHA_LONG)
            .let { converge(it) }
            .coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.info(LTag.APS, "BasalLearner: Long-term update. TDD7/30=${"%.2f".format(tdd7Days/max(1.0, tdd30Days))}, " +
            "AvgFastingBG=${"%.0f".format(avgFastingBg)}, FastingSlope=${"%.2f".format(fastingScore)}, " +
            "NewMultiplier=${"%.3f".format(longTermMultiplier)}")
    }

    // === Helper Functions ===

    private fun computeWeightedError(buffer: List<TimestampedBg>, now: Long, tau: Double): Double {
        if (buffer.isEmpty()) return 0.0

        var weightedSum = 0.0
        var weightSum = 0.0

        for (obs in buffer) {
            val ageMs = (now - obs.timestamp).toDouble()
            val weight = exp(-ageMs / tau)
            weightedSum += obs.delta * weight
            weightSum += weight
        }

        return if (weightSum > 0) weightedSum / weightSum else 0.0
    }

    private fun computeWeightedAvgBg(buffer: List<TimestampedBg>, now: Long, tau: Double): Double {
        if (buffer.isEmpty()) return 100.0

        var weightedSum = 0.0
        var weightSum = 0.0

        for (obs in buffer) {
            val ageMs = (now - obs.timestamp).toDouble()
            val weight = exp(-ageMs / tau)
            weightedSum += obs.bg * weight
            weightSum += weight
        }

        return if (weightSum > 0) weightedSum / weightSum else 100.0
    }

    private fun pruneBuffer(buffer: MutableList<TimestampedBg>, windowMs: Long, now: Long) {
        buffer.removeAll { now - it.timestamp > windowMs }
    }

    private fun ema(prev: Double, obs: Double, alpha: Double): Double {
        return (1 - alpha) * prev + alpha * obs
    }

    // === Persistence ===

    private fun load() {
        storageHelper.loadFileSafe(file,
            onSuccess = { content ->
                val json = JSONObject(content)
                shortTermMultiplier = json.optDouble("shortTermMultiplier", 1.0)
                mediumTermMultiplier = json.optDouble("mediumTermMultiplier", 1.0)
                longTermMultiplier = json.optDouble("longTermMultiplier", 1.0)
                lastShortUpdate = json.optLong("lastShortUpdate", 0L)
                lastMediumUpdate = json.optLong("lastMediumUpdate", 0L)
                lastLongUpdate = json.optLong("lastLongUpdate", 0L)
                log.info(LTag.APS, "BasalLearner: ✅ Loaded multipliers S=${"%.3f".format(shortTermMultiplier)} " +
                    "M=${"%.3f".format(mediumTermMultiplier)} L=${"%.3f".format(longTermMultiplier)}")
            },
            onError = { e ->
                log.warn(LTag.APS, "BasalLearner: Load failed, using defaults (multiplier=1.0)")
            }
        )
    }

    private fun save() {
        val json = JSONObject()
        json.put("shortTermMultiplier", shortTermMultiplier)
        json.put("mediumTermMultiplier", mediumTermMultiplier)
        json.put("longTermMultiplier", longTermMultiplier)
        json.put("lastShortUpdate", lastShortUpdate)
        json.put("lastMediumUpdate", lastMediumUpdate)
        json.put("lastLongUpdate", lastLongUpdate)
        storageHelper.saveFileSafe(file, json.toString())
    }

    fun statusSnapshot(): StatusSnapshot = statusRef.get()

    private fun publishStatus(observedAt: Long? = null) {
        val lastEngineUpdate = maxOf(lastShortUpdate, lastMediumUpdate, lastLongUpdate)
        statusRef.set(
            StatusSnapshot(
                shortTermMultiplier = shortTermMultiplier,
                mediumTermMultiplier = mediumTermMultiplier,
                longTermMultiplier = longTermMultiplier,
                combinedMultiplier = getMultiplier(),
                shortBufferCount = shortTermBuffer.size,
                mediumBufferCount = mediumTermBuffer.size,
                fastingSampleCount = fastingSamples,
                shortUpdateCount = shortUpdateCount.get(),
                mediumUpdateCount = mediumUpdateCount.get(),
                longUpdateCount = longUpdateCount.get(),
                lastShortUpdate = lastShortUpdate,
                lastMediumUpdate = lastMediumUpdate,
                lastLongUpdate = lastLongUpdate,
                updatedAt = observedAt ?: lastEngineUpdate.takeIf { it > 0L },
            )
        )
    }

    // === Legacy API compatibility ===
    @Deprecated("Use getMultiplier() instead", ReplaceWith("getMultiplier()"))
    val basalMultiplier: Double
        get() = getMultiplier()
}
