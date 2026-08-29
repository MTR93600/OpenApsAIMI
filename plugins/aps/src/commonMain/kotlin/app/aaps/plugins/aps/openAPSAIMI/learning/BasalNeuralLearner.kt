package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * BasalNeuralLearner identifies user-specific glycemic response patterns
 * to optimize basal adjustments.
 *
 * It supports two modes:
 * 1. T3C Brittle Mode (Aggressive corrective PI basal)
 * 2. Universal Adaptive Basal (Subtle scaling of standard TBRs for all users)
 */
@SingleIn(AppScope::class)
class BasalNeuralLearner @Inject constructor(
    private val preferences: Preferences,
    private val storage: AimiStorage,
    private val log: AAPSLogger
) {
    enum class GovernanceAction {
        WARMUP,
        KEEP,
        HOLD_CONSERVATIVE,
        ALLOW_GENTLE_INCREASE
    }

    data class GovernanceSnapshot(
        val action: GovernanceAction,
        val confidence: Double,
        val sampleCount: Int,
        /** Fraction of samples with BG below hypo threshold (unweighted count / n). */
        val hypoRate: Double,
        /** Hypo fraction used for HOLD enter/exit (noise- and context-weighted). */
        val hypoRateGovernance: Double = hypoRate,
        /** Same as [hypoRateGovernance] after short-horizon prediction relief (A3). */
        val hypoGovernanceAdjusted: Double = hypoRateGovernance,
        /** 0 = no predictive relief; 1 = strong relief from recent min-pred vs hypo band. */
        val anticipationRelief: Double = 0.0,
        val severeHypoCount: Int,
        val highRate: Double,
        val meanAbsTargetError: Double,
        val reason: String,
        val timestamp: Long,
        /** True when [action] is [GovernanceAction.HOLD_CONSERVATIVE] only because of exit hysteresis (raw metrics already improved). */
        val hypoHoldLatched: Boolean = false,
        /** Effective basal scaling floor applied this evaluation when in HOLD; null otherwise. */
        val activeBasalFloor: Double? = null,
        /** Effective T3C aggressiveness floor when in HOLD; null otherwise. */
        val activeAggressivenessFloor: Double? = null,
        /** Mean per-sample governance weight (sensor noise + IOB/drop context); 1.0 = legacy unweighted behaviour. */
        val meanGovernanceWeight: Double = 1.0,
    )

    private data class LearningSample(
        val timestamp: Long,
        val bgBefore: Double,
        val bgAfter: Double,
        val targetBg: Double,
        /** BG change (mg/dL per typical loop interval) used for context scoring. */
        val deltaMgDl: Double,
        val iobUnits: Double,
        /** CGM noise (0 = clean; higher = less trust in this sample for governance). */
        val sensorNoise: Double,
        /** Min BG across short prediction curves at this tick (mg/dL); null if unavailable. */
        val shortMinPredBg: Double? = null,
        /**
         * BG actually measured about [OUTCOME_HORIZON_MS] after this tick, filled in later.
         *
         * [bgAfter] is `rT.eventualBG`, a PKPD prediction floored at 39 mg/dL on roughly a quarter of
         * ticks — the same artefact the CSV parser was fixed for. Counting those as hypoglycaemia
         * biased governance towards HOLD_CONSERVATIVE, which suppresses basal learning. Governance now
         * scores realised outcomes only; a sample with no realised value is not counted at all.
         */
        var realisedBgAfter: Double? = null,
    )

    private var internalAggressivenessFactor = 1.0 // Heuristic fallback for T3C
    private var internalBasalScalingFactor = 1.0    // Heuristic fallback for Universal
    
    private var neuralT3cNet: AimiNeuralNetwork? = null
    private var neuralBasalNet: AimiNeuralNetwork? = null

    private val governanceWindow = ArrayDeque<LearningSample>(GOVERNANCE_WINDOW_MAX + 1)
    private var lastGovernanceSnapshot = GovernanceSnapshot(
        action = GovernanceAction.WARMUP,
        confidence = 0.0,
        sampleCount = 0,
        hypoRate = 0.0,
        severeHypoCount = 0,
        highRate = 0.0,
        meanAbsTargetError = 0.0,
        reason = "Warmup",
        timestamp = aimiWallClockMs(),
        hypoHoldLatched = false,
        activeBasalFloor = null,
        activeAggressivenessFloor = null,
        meanGovernanceWeight = 1.0,
    )
    private var lastGovernanceLogAt = 0L

    /**
     * Guards `lastGovernanceSnapshot` and the two model fields against a background training thread
     * publishing new weights while the loop reads them.
     *
     * Replaces `@Synchronized`, which is JVM only. On Android [AapsLock] delegates straight to
     * `kotlin.synchronized`, so the generated code and the runtime behaviour there are unchanged. It
     * is a dedicated lock and is never reassigned, which is the rule the class KDoc of [AapsLock]
     * states.
     */
    private val stateLock = AapsLock()

    /**
     * Neutral physiological context (backfill / when no live state is available): 4 latent + 3 mode + 3 causal = 10,
     * reusing the SMB feature schema so the two models share one physio vocabulary.
     *
     * DO NOT MOVE THIS PROPERTY BELOW THE `init` BLOCK. Kotlin runs property initializers and `init`
     * blocks in declaration order. The `init` block loads the models and probes them, the probe builds
     * its input with [modelInput], and [modelInput] reads this vector. Declared after `init`, this
     * property is still `null` while the probe runs, so the probe would crash (or silently probe a
     * wrong-size input) at construction time. The order is load-bearing, not cosmetic.
     */
    private val neutralPhysioFeatures: FloatArray =
        SmbRefinementFeatureSchema.latentFeatureValues(null) +
            SmbRefinementFeatureSchema.modeFeatureValues(null) +
            SmbRefinementFeatureSchema.causalFeatureValues(null)

    // Keep `neutralPhysioFeatures` declared ABOVE this block: the health probe inside loadModels()
    // reads it. See the KDoc of that property.
    init {
        loadModels()
    }

    private fun loadModels() {
        val t3cWeights = storage.file("t3c_brain_weights.json")
        val basalWeights = storage.file("basal_adaptive_weights.json")

        neuralT3cNet = BasalMlModelStore.loadValid(storage, t3cWeights, BasalMlSchema.INPUT_SIZE)
        neuralBasalNet = BasalMlModelStore.loadValid(storage, basalWeights, BasalMlSchema.INPUT_SIZE)
        rejectUnhealthyNets()
    }

    /**
     * Drops a model when it does not react to blood glucose, or answers outside the range that head may
     * ever publish. Runs for BOTH heads, on every path that assigns them (construction and
     * [reloadModels]).
     *
     * A constant model is not a theory, it is a measured failure mode. A shipped
     * `basal_adaptive_weights.json` was probed directly and returned **0.19918 for every input**
     * (spread over BG 40..400 = 3.4e-08). The runtime clamp turned that constant into exactly 0.70 on
     * 100 % of ticks, on two devices 40 days apart, which reads like a confident decision instead of a
     * dead model. Without this probe nothing tells the two apart. When the probe fails the heuristic
     * takes over, because a heuristic that moves is worth more than a number that never does.
     *
     * The T3C head shares the same network class, the same file format and the same trainer, so it can
     * die in exactly the same way. It used to be loaded with no probe at all.
     */
    private fun rejectUnhealthyNets() {
        neuralBasalNet = neuralBasalNet?.takeIf {
            answersToBg(it, "basal", BASAL_PROBE_OUTPUT_MIN, BASAL_PROBE_OUTPUT_MAX)
        }
        neuralT3cNet = neuralT3cNet?.takeIf {
            answersToBg(it, "T3C", T3C_PROBE_OUTPUT_MIN, T3C_PROBE_OUTPUT_MAX)
        }
    }

    /**
     * Sweeps [ClinicalBgAnchors.PROBE_BG_MGDL] through [net] and answers whether the model is alive.
     *
     * Alive means two things at once: every answer is finite and inside [outputMin] .. [outputMax]
     * (the publish range of that head), and the answers move by at least [PROBE_MIN_SPREAD] across the
     * anchors. A model that passes the range check with one constant value is the dead model described
     * in [rejectUnhealthyNets]; only the spread check catches it.
     *
     * [label] only names the head in the log lines ("basal" / "T3C").
     */
    private fun answersToBg(
        net: AimiNeuralNetwork,
        label: String,
        outputMin: Double,
        outputMax: Double,
    ): Boolean {
        val outputs = try {
            ClinicalBgAnchors.PROBE_BG_MGDL.map { bg ->
                net.predict(
                    modelInput(
                        bg = bg,
                        basal = BASAL_PROBE_BASAL_UPH,
                        accel = 0.0,
                        duraMin = BASAL_PROBE_DURA_MIN,
                        duraAvg = BASAL_PROBE_DURA_AVG,
                        iob = BASAL_PROBE_IOB_U,
                        physioFeatures = neutralPhysioFeatures,
                    )
                )[0]
            }
        } catch (e: Exception) {
            log.error(LTag.AIMI, "BasalNeuralLearner: $label model probe failed, using the heuristic", e)
            return false
        }

        val bad = outputs.any { !it.isFinite() || it < outputMin || it > outputMax }
        val spread = (outputs.maxOrNull() ?: 0.0) - (outputs.minOrNull() ?: 0.0)
        if (!bad && spread >= PROBE_MIN_SPREAD) return true

        log.warn(
            LTag.AIMI,
            "BasalNeuralLearner: $label model REJECTED (out=${outputs.joinToString(",") { fmt5(it) }} " +
                "spread=${fmt6(spread)} range=${aimiFmt2(outputMin)}..${aimiFmt2(outputMax)}) — it does not answer " +
                "to BG, falling back to the heuristic",
        )
        return false
    }

    /** Reload weight files from disk after background training publishes new models. */
    fun reloadModels() = stateLock.withLock {
        loadModels()
        log.debug(
            LTag.AIMI,
            "BasalNeuralLearner: models reloaded (t3c=${neuralT3cNet != null}, basal=${neuralBasalNet != null})",
        )
    }

    /**
     * Model input = 6 glucose-dynamics base features + 10 physiological-context features (mirror of the SMB schema)
     * → `BasalMlTrainingCoordinator.INPUT_SIZE`. Falls back to neutral physio if the caller's vector is the wrong
     * size, so a wiring mistake degrades to context-blind rather than crashing on an input-size mismatch.
     */
    private fun modelInput(
        bg: Double, basal: Double, accel: Double, duraMin: Double, duraAvg: Double, iob: Double,
        physioFeatures: FloatArray,
    ): FloatArray {
        val base = floatArrayOf(bg.toFloat(), basal.toFloat(), accel.toFloat(), duraMin.toFloat(), duraAvg.toFloat(), iob.toFloat())
        val physio = if (physioFeatures.size == neutralPhysioFeatures.size) physioFeatures else neutralPhysioFeatures
        return base + physio
    }

    /** Where the T3C aggressiveness multiplier came from this tick. */
    enum class T3cFactorSource { NEURAL, HEURISTIC }

    /**
     * Full T3C Brittle Mode answer, including the multiplier BEFORE the clamp.
     *
     * [rawMultiplier] is the field that matters for forensics, for the same reason as
     * [UniversalBasalDecision.rawValue]: it separates "the model learned 0.50" from "the model answers
     * 0.31 and the clamp reported 0.50". Without it the two print the same number.
     */
    data class T3cAdaptiveDecision(
        /** Value actually applied: [baseAggressiveness] * [multiplier]. */
        val factor: Double,
        /** User preference part, before any learning. */
        val baseAggressiveness: Double,
        /** Multiplier before the clamp: neural output, or heuristic factor. */
        val rawMultiplier: Double,
        /** Multiplier after [floor] / [ceiling]. */
        val multiplier: Double,
        val source: T3cFactorSource,
        val floor: Double,
        val ceiling: Double,
    ) {
        /** True when the clamp, not the model, decided the multiplier. */
        val clamped: Boolean get() = multiplier != rawMultiplier
    }

    /**
     * Returns the aggressiveness factor for T3C Brittle Mode.
     */
    fun getT3cAdaptiveFactor(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
        physioFeatures: FloatArray = neutralPhysioFeatures,
    ): Double = getT3cAdaptiveDecision(bg, basal, accel, duraMin, duraAvg, iob, physioFeatures).factor

    /**
     * Same result as [getT3cAdaptiveFactor], plus the raw multiplier and the source.
     *
     * The learned multiplier is clamped to [RUNTIME_T3C_FACTOR_MIN] .. [RUNTIME_T3C_FACTOR_MAX]. Before
     * this clamp existed the T3C head could scale the user's aggressiveness by anything its publish
     * range allowed (0.3x .. 3.0x), with no floor, no ceiling and no liveness check.
     */
    fun getT3cAdaptiveDecision(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
        physioFeatures: FloatArray = neutralPhysioFeatures,
    ): T3cAdaptiveDecision {
        val baseAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness)
        val neuralFactor = neuralT3cNet
            ?.predict(modelInput(bg, basal, accel, duraMin, duraAvg, iob, physioFeatures))
            ?.get(0)
            ?.takeIf { it.isFinite() }
        val raw = neuralFactor ?: internalAggressivenessFactor
        val multiplier = raw.coerceIn(RUNTIME_T3C_FACTOR_MIN, RUNTIME_T3C_FACTOR_MAX)
        val decision = T3cAdaptiveDecision(
            factor = baseAggressiveness * multiplier,
            baseAggressiveness = baseAggressiveness,
            rawMultiplier = raw,
            multiplier = multiplier,
            source = if (neuralFactor != null) T3cFactorSource.NEURAL else T3cFactorSource.HEURISTIC,
            floor = RUNTIME_T3C_FACTOR_MIN,
            ceiling = RUNTIME_T3C_FACTOR_MAX,
        )
        logT3cDecision(decision)
        return decision
    }

    /**
     * Traces the T3C multiplier, raw value first.
     *
     * A clamped tick is logged at info level, because "the clamp decided this" is the event a field
     * investigation needs to see; an unclamped tick is only debug, to keep the loop quiet.
     */
    private fun logT3cDecision(decision: T3cAdaptiveDecision) {
        val line =
            "BasalNeuralLearner: T3C raw=${fmt5(decision.rawMultiplier)} applied=${fmt5(decision.multiplier)} " +
                "base=${aimiFmt2(decision.baseAggressiveness)} factor=${fmt5(decision.factor)} " +
                "src=${decision.source.name} clamp=${aimiFmt2(decision.floor)}..${aimiFmt2(decision.ceiling)}"
        if (decision.clamped) log.info(LTag.AIMI, line) else log.debug(LTag.AIMI, line)
    }

    /** Where the Universal Adaptive Basal multiplier came from this tick. */
    enum class BasalMultiplierSource { NEURAL, HEURISTIC, DISABLED }

    /**
     * Full Universal Adaptive Basal answer, including the value BEFORE the clamp.
     *
     * [rawValue] is the field that matters for forensics: it separates "the model learned 0.70" from
     * "the model answers 0.199 and the clamp reported 0.70". Without it the two look identical in the
     * logs, which is exactly how a dead model survived 40 days in the field.
     */
    data class UniversalBasalDecision(
        /** Value actually applied, after [floor] / [ceiling]. */
        val multiplier: Double,
        /** Value before the clamp: neural output, or heuristic factor, or 1.0 when disabled. */
        val rawValue: Double,
        val source: BasalMultiplierSource,
        val floor: Double,
        val ceiling: Double,
    ) {
        /** True when the clamp, not the model, decided the value. */
        val clamped: Boolean get() = multiplier != rawValue
    }

    /**
     * Returns the scaling factor for Universal Adaptive Basal.
     */
    fun getUniversalBasalMultiplier(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
        physioFeatures: FloatArray = neutralPhysioFeatures,
    ): Double = getUniversalBasalDecision(bg, basal, accel, duraMin, duraAvg, iob, physioFeatures).multiplier

    /**
     * Same result as [getUniversalBasalMultiplier], plus the raw value and the source, for the
     * `adaptive_basal` block of AIMI_Decisions.jsonl.
     */
    fun getUniversalBasalDecision(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
        physioFeatures: FloatArray = neutralPhysioFeatures,
    ): UniversalBasalDecision {
        val ceiling = max(1.0, preferences.get(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling))
        if (!preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)) {
            return UniversalBasalDecision(
                multiplier = 1.0,
                rawValue = 1.0,
                source = BasalMultiplierSource.DISABLED,
                floor = RUNTIME_BASAL_FLOOR,
                ceiling = ceiling,
            )
        }

        val neuralFactor = neuralBasalNet
            ?.predict(modelInput(bg, basal, accel, duraMin, duraAvg, iob, physioFeatures))
            ?.get(0)
            ?.takeIf { it.isFinite() }
        val raw = neuralFactor ?: internalBasalScalingFactor
        return UniversalBasalDecision(
            multiplier = raw.coerceIn(RUNTIME_BASAL_FLOOR, ceiling),
            rawValue = raw,
            source = if (neuralFactor != null) BasalMultiplierSource.NEURAL else BasalMultiplierSource.HEURISTIC,
            floor = RUNTIME_BASAL_FLOOR,
            ceiling = ceiling,
        )
    }

    /**
     * Updates the internal state and LOGS data for Neural Training.
     */
    fun updateLearning(
        bgBefore: Double,
        bgAfter: Double,
        basalDelivered: Double,
        targetBg: Double,
        accel: Double,
        duraISFminutes: Double,
        duraISFaverage: Double,
        iob: Double,
        loopDeltaMgDl5m: Double? = null,
        sensorNoise: Double = 0.0,
        shortMinPredBg: Double? = null,
        physioFeatures: FloatArray = neutralPhysioFeatures,
        /**
         * Insulin delivered outside basal at this tick (SMB + manual bolus), in units.
         * `NaN` means "not reported"; the trainer then falls back to an IOB-jump check.
         */
        bolusInsulinU: Double = Double.NaN,
        /** Carbs on board at this tick, in grams. `NaN` means "not reported". */
        cobGrams: Double = Double.NaN,
    ) {
        val isT3cActive = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        val isAdaptiveBasalActive = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
        val delta = loopDeltaMgDl5m?.takeIf { it.isFinite() } ?: (bgAfter - bgBefore)
        
        // 1. T3C Heuristic (Aggressive)
        if (isT3cActive) {
            if (bgBefore > 180.0 && delta >= 0) {
                internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 3.0)
            }
            if (delta < -15.0) {
                internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
            }
        }

        // 2. Universal Adaptive Basal Heuristic (Subtle)
        if (isAdaptiveBasalActive) {
            // If BG is high (>140) and not dropping fast enough
            if (bgBefore > 140.0 && delta > -2.0) {
                internalBasalScalingFactor = min(internalBasalScalingFactor + 0.01, 3.0)
            }
            // If BG is low (<90) or dropping too fast
            if (bgBefore < 90.0 || delta < -8.0) {
                internalBasalScalingFactor = max(internalBasalScalingFactor - 0.02, 0.8)
            }
        }

        // 3. Governance window (in-memory, dosing-relevant): must not be skipped by a logging I/O failure.
        try {
            updateGovernanceWindow(
                bgBefore = bgBefore,
                bgAfter = bgAfter,
                targetBg = targetBg,
                deltaMgDl = delta,
                iobUnits = iob,
                sensorNoise = sensorNoise,
                shortMinPredBg = shortMinPredBg,
            )
            evaluateGovernance()
        } catch (e: Exception) {
            log.error("LEARNER_GOV", "Governance update failed: ${e.message}")
        }

        // 4. Data logging (best-effort, feeds background training): a file error here never affects governance.
        try {
            logRecord(
                bg = bgBefore,
                eventualBg = bgAfter,
                basal = basalDelivered,
                target = targetBg,
                accel = accel,
                duraMin = duraISFminutes,
                duraAvg = duraISFaverage,
                iob = iob,
                physioFeatures = physioFeatures,
                bolusInsulinU = bolusInsulinU,
                cobGrams = cobGrams,
            )
        } catch (e: Exception) {
            log.error("LEARNER_LOG", "Failed to log records: ${e.message}")
        }
    }

    fun getGovernanceSnapshot(): GovernanceSnapshot = stateLock.withLock { lastGovernanceSnapshot }

    /**
     * CSV header: legacy columns, then the physio-context columns (mirror of the SMB schema), then the
     * causal columns of [BasalCsvSchema]. New columns are always appended at the end so an old row
     * stays readable: the parser matches by name, and a name it cannot find is "unknown", not zero.
     */
    private val basalCsvHeader: String =
        "timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale," +
            (SmbRefinementFeatureSchema.latentFeatureNames +
                SmbRefinementFeatureSchema.modeFeatureNames +
                SmbRefinementFeatureSchema.causalFeatureNames +
                BasalCsvSchema.causalColumns).joinToString(",")

    /**
     * Ensure the CSV header is the current one. Fresh file → write it. Existing file with an older
     * header → replace the header line in place. Old data rows keep their own width; the parser reads
     * by column name, so a column they do not have is read as absent (schema versioning + backfill).
     */
    private fun ensureCsvSchema(file: AimiPath) {
        if (!storage.exists(file)) {
            storage.writeText(file, basalCsvHeader + "\n")
            return
        }
        val firstLine = storage.readFirstLine(file) ?: ""
        if (firstLine != basalCsvHeader) {
            val lines = storage.readLines(file).toMutableList()
            if (lines.isEmpty()) {
                storage.writeText(file, basalCsvHeader + "\n")
            } else {
                lines[0] = basalCsvHeader
                storage.writeText(file, lines.joinToString("\n") + "\n")
            }
        }
    }

    private fun logRecord(
        bg: Double,
        eventualBg: Double,
        basal: Double,
        target: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
        physioFeatures: FloatArray,
        bolusInsulinU: Double,
        cobGrams: Double,
    ) {
        val file = storage.file("basal_adaptive_records.csv")
        ensureCsvSchema(file)

        val physio = if (physioFeatures.size == neutralPhysioFeatures.size) physioFeatures else neutralPhysioFeatures
        val row = "${aimiWallClockMs()},$bg,$eventualBg,$basal,$target,$accel,$duraMin,$duraAvg,$iob," +
            "$internalAggressivenessFactor,$internalBasalScalingFactor,${physio.joinToString(",")}," +
            "$bolusInsulinU,$cobGrams\n"
        storage.appendText(file, row)
    }

    private fun updateGovernanceWindow(
        bgBefore: Double,
        bgAfter: Double,
        targetBg: Double,
        deltaMgDl: Double,
        iobUnits: Double,
        sensorNoise: Double,
        shortMinPredBg: Double?,
    ) {
        if (!bgBefore.isFinite() || !bgAfter.isFinite() || !targetBg.isFinite()) return
        val noise = if (sensorNoise.isFinite()) sensorNoise else 0.0
        val iob = if (iobUnits.isFinite()) iobUnits else 0.0
        val d = if (deltaMgDl.isFinite()) deltaMgDl else (bgAfter - bgBefore)
        val predMin = shortMinPredBg?.takeIf { it.isFinite() }
        fillRealisedOutcomes(nowMs = aimiWallClockMs(), observedBg = bgBefore)
        governanceWindow.addFirst(
            LearningSample(
                timestamp = aimiWallClockMs(),
                bgBefore = bgBefore,
                bgAfter = bgAfter,
                targetBg = targetBg,
                deltaMgDl = d,
                iobUnits = iob,
                sensorNoise = noise,
                shortMinPredBg = predMin,
            )
        )
        while (governanceWindow.size > GOVERNANCE_WINDOW_MAX) governanceWindow.removeLast()
    }

    /**
     * Short-horizon prediction relief: among recent samples that carry a min-pred value,
     * fraction where predicted trough sits above the hypo band (anticipates recovery).
     */
    private fun governanceAnticipationRelief(
        samples: List<LearningSample>,
        hypoBgThreshold: Double,
        lookback: Int,
        marginMgdl: Double,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val n = minOf(lookback.coerceAtLeast(1), samples.size)
        val recent = samples.take(n)
        val withPred = recent.filter { it.shortMinPredBg != null }
        if (withPred.isEmpty()) return 0.0
        val margin = hypoBgThreshold + marginMgdl
        val hits = withPred.count { (it.shortMinPredBg ?: 0.0) >= margin }
        return (hits.toDouble() / withPred.size.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Pulls multiplicative decay closer to 1.0 when [blend] > 0 (less aggressive rollback). */
    private fun blendDecayTowardNeutral(decay: Double, blend: Double): Double {
        val d = decay.coerceIn(0.90, 0.999)
        val b = blend.coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - d) * (1.0 - b)
    }

    /** Per-sample weight for governance (on-device): down-rank noisy CGM and very fast drops with little IOB. */
    private fun governanceSampleWeight(s: LearningSample): Double {
        val noiseTrust = when {
            !s.sensorNoise.isFinite() || s.sensorNoise <= 0.0 -> 1.0
            s.sensorNoise >= 3.0 -> GOVERNANCE_WEIGHT_NOISE_TIER3
            s.sensorNoise >= 2.0 -> GOVERNANCE_WEIGHT_NOISE_TIER2
            s.sensorNoise >= 1.0 -> GOVERNANCE_WEIGHT_NOISE_TIER1
            else -> (1.0 - s.sensorNoise * GOVERNANCE_WEIGHT_NOISE_LINEAR).coerceIn(GOVERNANCE_WEIGHT_MIN, 1.0)
        }
        val rapidFall = s.deltaMgDl < GOVERNANCE_RAPID_FALL_DELTA_MGDL
        val lowIob = s.iobUnits < GOVERNANCE_LOW_IOB_THRESHOLD_U
        val iobFactor = if (rapidFall && lowIob) GOVERNANCE_WEIGHT_RAPID_FALL_LOW_IOB else 1.0
        return (noiseTrust * iobFactor).coerceIn(GOVERNANCE_WEIGHT_MIN, 1.0)
    }

    private data class EffectiveGovernanceParams(
        val hypoBgThreshold: Double,
        val severeBgThreshold: Double,
        val hypoRateEnter: Double,
        val hypoRateExit: Double,
        val holdBasalFloorRate: Double,
        val holdBasalDecayRate: Double,
        val holdAggFloorRate: Double,
        val holdAggDecayRate: Double,
        val holdBasalFloorSevere: Double,
        val holdBasalDecaySevere: Double,
        val holdAggFloorSevere: Double,
        val holdAggDecaySevere: Double,
        val anticipationLookback: Int,
        val anticipationMarginMgdl: Double,
        val anticipationHypoDamp: Double,
        val anticipationDecayBlendMax: Double,
    )

    /**
     * Reads user preferences and applies safe cross-constraints (exit rate below enter rate, severe BG below hypo BG, severe tier at least as tight as rate tier).
     */
    private fun effectiveGovernanceParams(): EffectiveGovernanceParams {
        val hypoBg = preferences.get(DoubleKey.OApsAIMIGovernanceHypoBgMgdl)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoBgMgdl.min, DoubleKey.OApsAIMIGovernanceHypoBgMgdl.max)
        val severeBgRaw = preferences.get(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl)
            .coerceIn(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl.min, DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl.max)
        val severeBg = min(severeBgRaw, hypoBg - 1.0).coerceAtLeast(54.0)

        var hypoRateEnter = preferences.get(DoubleKey.OApsAIMIGovernanceHypoRateEnter)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoRateEnter.min, DoubleKey.OApsAIMIGovernanceHypoRateEnter.max)
        var hypoRateExit = preferences.get(DoubleKey.OApsAIMIGovernanceHypoRateExit)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoRateExit.min, DoubleKey.OApsAIMIGovernanceHypoRateExit.max)
        if (hypoRateExit >= hypoRateEnter) {
            hypoRateExit = (hypoRateEnter - 0.01).coerceAtLeast(DoubleKey.OApsAIMIGovernanceHypoRateExit.min)
        }

        fun clampedDecay(key: DoubleKey): Double =
            preferences.get(key).coerceIn(key.min, key.max).coerceAtMost(0.999).coerceAtLeast(0.90)

        fun clampedFloor(key: DoubleKey): Double =
            preferences.get(key).coerceIn(key.min, key.max)

        var holdBasalFloorRate = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate)
        var holdBasalDecayRate = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate)
        var holdAggFloorRate = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldAggFloorRate)
        var holdAggDecayRate = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldAggDecayRate)
        var holdBasalFloorSevere = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere)
        var holdBasalDecaySevere = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere)
        var holdAggFloorSevere = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere)
        var holdAggDecaySevere = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere)

        if (holdBasalFloorSevere < holdBasalFloorRate) holdBasalFloorSevere = holdBasalFloorRate
        holdBasalDecaySevere = min(holdBasalDecaySevere, holdBasalDecayRate)
        if (holdAggFloorSevere < holdAggFloorRate) holdAggFloorSevere = holdAggFloorRate
        holdAggDecaySevere = min(holdAggDecaySevere, holdAggDecayRate)

        val anticipationLookback = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples.min,
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples.max,
            )
            .roundToInt()
            .coerceIn(1, 288)
        val anticipationMarginMgdl = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl.min,
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl.max,
            )
        val anticipationHypoDamp = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp.min,
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp.max,
            )
        val anticipationDecayBlendMax = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax.min,
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax.max,
            )

        return EffectiveGovernanceParams(
            hypoBgThreshold = hypoBg,
            severeBgThreshold = severeBg,
            hypoRateEnter = hypoRateEnter,
            hypoRateExit = hypoRateExit,
            holdBasalFloorRate = holdBasalFloorRate,
            holdBasalDecayRate = holdBasalDecayRate,
            holdAggFloorRate = holdAggFloorRate,
            holdAggDecayRate = holdAggDecayRate,
            holdBasalFloorSevere = holdBasalFloorSevere,
            holdBasalDecaySevere = holdBasalDecaySevere,
            holdAggFloorSevere = holdAggFloorSevere,
            holdAggDecaySevere = holdAggDecaySevere,
            anticipationLookback = anticipationLookback,
            anticipationMarginMgdl = anticipationMarginMgdl,
            anticipationHypoDamp = anticipationHypoDamp,
            anticipationDecayBlendMax = anticipationDecayBlendMax,
        )
    }

    /**
     * Fills [LearningSample.realisedBgAfter] on samples that have reached the outcome horizon.
     *
     * `observedBg` is the BG measured now, which is the realised outcome for a tick recorded roughly
     * [OUTCOME_HORIZON_MS] ago. Samples outside the acceptance window are left unrealised rather than
     * labelled with a stale reading.
     */
    private fun fillRealisedOutcomes(nowMs: Long, observedBg: Double) {
        if (!observedBg.isFinite() || observedBg <= 0.0) return
        governanceWindow.forEach { sample ->
            if (sample.realisedBgAfter != null) return@forEach
            val age = nowMs - sample.timestamp
            if (age in OUTCOME_HORIZON_MIN_MS..OUTCOME_HORIZON_MAX_MS) {
                sample.realisedBgAfter = observedBg
            }
        }
    }

    internal fun evaluateGovernance() {
        val now = aimiWallClockMs()
        if (governanceWindow.isEmpty()) return

        val p = effectiveGovernanceParams()
        // Realised outcomes only: a floored prediction must never be scored as a hypo.
        val samples = governanceWindow.filter { it.realisedBgAfter != null }
        if (samples.isEmpty()) return
        val count = samples.size
        val outcome = { s: LearningSample -> s.realisedBgAfter ?: s.bgAfter }
        val hypoCount = samples.count { outcome(it) < p.hypoBgThreshold }
        val highCount = samples.count { outcome(it) > 180.0 }
        val meanAbsTargetError = samples.map { abs(outcome(it) - it.targetBg) }.average()

        val hypoRateUnweighted = hypoCount.toDouble() / count.toDouble()
        var weightSum = 0.0
        var hypoWeightedSum = 0.0
        samples.forEach { s ->
            val w = governanceSampleWeight(s)
            weightSum += w
            if ((s.realisedBgAfter ?: s.bgAfter) < p.hypoBgThreshold) hypoWeightedSum += w
        }
        val hypoRateGovernance = if (weightSum > 0.0) hypoWeightedSum / weightSum else hypoRateUnweighted
        val meanGovernanceWeight = if (count > 0) weightSum / count.toDouble() else 1.0
        val severeHypoCount = samples.count {
            it.bgAfter < p.severeBgThreshold && governanceSampleWeight(it) >= GOVERNANCE_SEVERE_MIN_SAMPLE_WEIGHT
        }
        val highRate = highCount.toDouble() / count.toDouble()
        val confidence = (count.toDouble() / GOVERNANCE_WINDOW_MAX.toDouble()).coerceIn(0.0, 1.0)

        val anticipationRelief = governanceAnticipationRelief(
            samples,
            p.hypoBgThreshold,
            p.anticipationLookback,
            p.anticipationMarginMgdl,
        )
        val hypoGovernanceAdjusted =
            (hypoRateGovernance * (1.0 - anticipationRelief * p.anticipationHypoDamp))
                .coerceIn(0.0, 1.0)

        val previousAction = lastGovernanceSnapshot.action
        val rawHypoPressure = severeHypoCount >= 1 || hypoGovernanceAdjusted >= p.hypoRateEnter
        val hypoPressureClearForExit = severeHypoCount == 0 && hypoGovernanceAdjusted < p.hypoRateExit

        val action: GovernanceAction
        val reason: String
        val hypoHoldLatched: Boolean
        if (count < GOVERNANCE_MIN_SAMPLES) {
            action = GovernanceAction.WARMUP
            reason = "Not enough samples"
            hypoHoldLatched = false
        } else if (rawHypoPressure) {
            action = GovernanceAction.HOLD_CONSERVATIVE
            reason = "Hypo pressure detected"
            hypoHoldLatched = false
        } else if (previousAction == GovernanceAction.HOLD_CONSERVATIVE && !hypoPressureClearForExit) {
            // Exit hysteresis: avoid flicker when hypoRate oscillates around the enter threshold.
            action = GovernanceAction.HOLD_CONSERVATIVE
            reason = "Hypo pressure latched (hysteresis)"
            hypoHoldLatched = true
        } else if (highRate >= 0.45 && hypoRateUnweighted <= 0.05 && meanAbsTargetError >= 35.0) {
            action = GovernanceAction.ALLOW_GENTLE_INCREASE
            reason = "Persistent hyper pattern"
            hypoHoldLatched = false
        } else {
            action = GovernanceAction.KEEP
            reason = "Balanced risk profile"
            hypoHoldLatched = false
        }

        val severeTier = severeHypoCount >= 1
        val basalFloor = if (action == GovernanceAction.HOLD_CONSERVATIVE) {
            if (severeTier) p.holdBasalFloorSevere else p.holdBasalFloorRate
        } else {
            null
        }
        val aggFloor = if (action == GovernanceAction.HOLD_CONSERVATIVE) {
            if (severeTier) p.holdAggFloorSevere else p.holdAggFloorRate
        } else {
            null
        }

        lastGovernanceSnapshot = GovernanceSnapshot(
            action = action,
            confidence = confidence,
            sampleCount = count,
            hypoRate = hypoRateUnweighted,
            hypoRateGovernance = hypoRateGovernance,
            hypoGovernanceAdjusted = hypoGovernanceAdjusted,
            anticipationRelief = anticipationRelief,
            severeHypoCount = severeHypoCount,
            highRate = highRate,
            meanAbsTargetError = meanAbsTargetError,
            reason = reason,
            timestamp = now,
            hypoHoldLatched = hypoHoldLatched,
            activeBasalFloor = basalFloor,
            activeAggressivenessFloor = aggFloor,
            meanGovernanceWeight = meanGovernanceWeight,
        )

        if (action != GovernanceAction.WARMUP) {
            applyGovernanceGuardrails(action, severeTier, p, anticipationRelief)
        }
        maybeLogGovernance(lastGovernanceSnapshot)
    }

    private fun applyGovernanceGuardrails(
        action: GovernanceAction,
        severeHypoTier: Boolean,
        p: EffectiveGovernanceParams,
        anticipationRelief: Double,
    ) {
        when (action) {
            GovernanceAction.HOLD_CONSERVATIVE -> {
                // Soft rollback toward neutral to avoid over-learning during hypo-prone windows.
                val basalFloor = if (severeHypoTier) p.holdBasalFloorSevere else p.holdBasalFloorRate
                val basalDecayRaw = if (severeHypoTier) p.holdBasalDecaySevere else p.holdBasalDecayRate
                val aggFloor = if (severeHypoTier) p.holdAggFloorSevere else p.holdAggFloorRate
                val aggDecayRaw = if (severeHypoTier) p.holdAggDecaySevere else p.holdAggDecayRate
                val decayBlend =
                    anticipationRelief.coerceIn(0.0, 1.0) * p.anticipationDecayBlendMax.coerceIn(0.0, 1.0)
                val basalDecay = blendDecayTowardNeutral(basalDecayRaw, decayBlend)
                val aggDecay = blendDecayTowardNeutral(aggDecayRaw, decayBlend)
                internalBasalScalingFactor = max(basalFloor, internalBasalScalingFactor * basalDecay)
                internalAggressivenessFactor = max(aggFloor, internalAggressivenessFactor * aggDecay)
            }
            GovernanceAction.ALLOW_GENTLE_INCREASE -> {
                // Keep adaptation gentle even in strong hyper windows.
                internalBasalScalingFactor = min(internalBasalScalingFactor, 1.35)
                internalAggressivenessFactor = min(internalAggressivenessFactor, 2.20)
            }
            GovernanceAction.KEEP, GovernanceAction.WARMUP -> {
                // No guardrail override.
            }
        }
    }

    private fun maybeLogGovernance(snapshot: GovernanceSnapshot) {
        val now = aimiWallClockMs()
        val hasActionChanged = snapshot.action != lastLoggedAction
        if (!hasActionChanged && (now - lastGovernanceLogAt) < GOVERNANCE_LOG_MIN_MS) return

        lastGovernanceLogAt = now
        lastLoggedAction = snapshot.action

        log.info(
            LTag.AIMI,
            "action=${snapshot.action.name} conf=${aimiFmt2(snapshot.confidence)} n=${snapshot.sampleCount} " +
                "hypo=${aimiFmt2(snapshot.hypoRate)} hypoG=${aimiFmt2(snapshot.hypoRateGovernance)} " +
                "hypoAdj=${aimiFmt2(snapshot.hypoGovernanceAdjusted)} ant=${aimiFmt2(snapshot.anticipationRelief)} " +
                "wMean=${aimiFmt2(snapshot.meanGovernanceWeight)} severe=${snapshot.severeHypoCount} " +
                "high=${aimiFmt2(snapshot.highRate)} mae=${aimiFmt1(snapshot.meanAbsTargetError)} " +
                "latch=${snapshot.hypoHoldLatched} " +
                "floorB=${snapshot.activeBasalFloor?.let { aimiFmt2(it) } ?: "-"} " +
                "floorA=${snapshot.activeAggressivenessFloor?.let { aimiFmt2(it) } ?: "-"} " +
                "reason=${snapshot.reason}",
        )
    }

    private var lastLoggedAction: GovernanceAction = GovernanceAction.WARMUP

    /**
     * Replaces the governance window (for unit tests).
     * [bgAfters] is chronological (oldest → newest); internal deque matches production (newest at front).
     */
    internal fun replaceGovernanceSamplesForTesting(
        bgAfters: List<Double>,
        targetBg: Double = 100.0,
        sensorNoises: List<Double>? = null,
        iobUnits: List<Double>? = null,
        deltasMgDl: List<Double>? = null,
        shortMinPredBgs: List<Double>? = null,
    ) {
        governanceWindow.clear()
        val slice = bgAfters.takeLast(GOVERNANCE_WINDOW_MAX)
        val offset = bgAfters.size - slice.size
        // addFirst oldest→newest so front = newest (matches updateGovernanceWindow / take(n) = recent)
        for (i in slice.indices) {
            val bg = slice[i]
            val idx = offset + i
            val noise = sensorNoises?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val iob = iobUnits?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val d = deltasMgDl?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val pred = shortMinPredBgs?.getOrNull(idx)?.takeIf { it.isFinite() }
            governanceWindow.addFirst(
                LearningSample(
                    timestamp = idx.toLong(),
                    bgBefore = bg,
                    bgAfter = bg,
                    targetBg = targetBg,
                    deltaMgDl = d,
                    iobUnits = iob,
                    sensorNoise = noise,
                    shortMinPredBg = pred,
                    // The seeded values *are* outcomes: this helper takes the BG that was actually
                    // reached. Marking them realised keeps the test intent and the production filter
                    // consistent.
                    realisedBgAfter = bg,
                )
            )
        }
    }

    /**
     * Resets governance + heuristic factors (for unit tests).
     */
    internal fun resetGovernanceStateForTesting() {
        governanceWindow.clear()
        lastGovernanceSnapshot = GovernanceSnapshot(
            action = GovernanceAction.WARMUP,
            confidence = 0.0,
            sampleCount = 0,
            hypoRate = 0.0,
            severeHypoCount = 0,
            highRate = 0.0,
            meanAbsTargetError = 0.0,
            reason = "Warmup",
            timestamp = aimiWallClockMs(),
            hypoHoldLatched = false,
            activeBasalFloor = null,
            activeAggressivenessFloor = null,
            meanGovernanceWeight = 1.0,
        )
        internalBasalScalingFactor = 1.0
        internalAggressivenessFactor = 1.0
        lastLoggedAction = GovernanceAction.WARMUP
        lastGovernanceLogAt = 0L
    }

    /**
     * The blood-glucose anchors, in mg/dL, that EVERY probe of these two heads must sweep.
     *
     * This is a contract between two places, not a local detail:
     * - the runtime loader probes a model on these anchors before it is allowed to dose
     *   (`answersToBg`, called from `rejectUnhealthyNets`);
     * - the publish gate in the trainer must probe a candidate on the SAME anchors.
     *
     * If the two sweep different inputs, the system can publish a model that its own loader then
     * refuses on the next reload. That is worse than either probe alone: training reports success, the
     * runtime silently falls back to the heuristic, and nothing in the logs connects the two.
     *
     * The publish gate today samples the p10/p50/p90 of the TRAINING bg column instead, and that is the
     * mismatch. Measured on real patient field data (24 h, n=260) the training p10..p90 window is
     * 79.2..157.6 mg/dL = 78.4 mg/dL wide, only 0.44x of the clinical span 70..250 mg/dL, so the gate
     * understates a model's real bg response by about 2.3x on that patient. On 10 patient-shaped seeds
     * that cost 5 of 10 models that genuinely learned (rejected), and let 1 of 10 pure-noise models
     * through.
     *
     * Why these three values: a low anchor inside the hypo band, a mid anchor near target, and a high
     * anchor in the correction band. They are fixed on purpose. A per-patient window shrinks with a
     * well-controlled patient, which makes the gate easiest exactly where a wrong model is hardest to
     * notice.
     */
    object ClinicalBgAnchors {

        /** Low / mid / high blood glucose, mg/dL. Do not narrow this without changing both probes. */
        val PROBE_BG_MGDL: List<Double> = listOf(70.0, 140.0, 250.0)
    }

    internal companion object {
        /**
         * Lowest multiplier the Universal Adaptive Basal path may apply.
         *
         * It matches the heuristic's own decrement floor (0.80 in [updateLearning]) and it is on
         * purpose NOT the same number as the label clamp floor (0.85) nor as `BasalLearner.CLAMP_MIN`
         * (0.70). Three different meanings used to share the literal 0.70, so a saturated label, a
         * clamped dead model and a legitimate strong cut all printed the same value.
         */
        const val RUNTIME_BASAL_FLOOR = 0.80

        /**
         * Lowest and highest multiplier the T3C Brittle Mode path may apply.
         *
         * Derived, not invented. The T3C head publishes with an output range of 0.3 .. 3.0, so a
         * published head could scale the user's own aggressiveness by 0.3x .. 3.0x with nothing in
         * between it and the pump. The clamp is tighter than that range on purpose, and every bound
         * below already exists somewhere else in this system:
         * - **0.50** is the floor the T3C heuristic itself never goes under (see the `max(... , 0.5)` in
         *   [updateLearning]) and it is the trainer's own label floor (`T3C_LABEL_MIN`);
         * - **2.00** is the trainer's label ceiling (`T3C_LABEL_MAX`) and the ceiling governance already
         *   forces in [applyGovernanceGuardrails] on a persistent hyper pattern (2.20, same order).
         *
         * So the clamp is exactly the window every training label was clamped into. A model can only
         * have LEARNED a value inside that window; an answer outside it is extrapolation, not learning.
         * Anything the model asks for beyond it is refused here and shown as `raw` in the log line.
         *
         * These bounds do NOT replace the caller's own limits: `DetermineBasalAIMI2` still clamps the
         * final aggressiveness (0.3 .. 2.0, or 3.0 in CFRD) after adding its own boosts, so the CFRD
         * path can still reach its ceiling. This clamp only limits the LEARNED part.
         */
        const val RUNTIME_T3C_FACTOR_MIN = 0.5
        const val RUNTIME_T3C_FACTOR_MAX = 2.0

        const val BASAL_PROBE_BASAL_UPH = 1.0
        const val BASAL_PROBE_DURA_MIN = 30.0
        const val BASAL_PROBE_DURA_AVG = 45.0
        const val BASAL_PROBE_IOB_U = 0.5

        /**
         * A model that moves less than this across [ClinicalBgAnchors.PROBE_BG_MGDL] is treated as
         * constant. Shared by both heads: the unit is the same (a fraction of a multiplier). It is an
         * absolute floor on "this model is not a constant", so it is relatively more permissive for the
         * T3C head, whose label window (0.5 .. 2.0) is three times wider than the basal one
         * (0.85 .. 1.35). That is the safe direction: it never rejects a model that really moves.
         */
        const val PROBE_MIN_SPREAD = 0.05

        /**
         * Accepted probe output of each head = the range that head is allowed to PUBLISH in
         * (`BasalMlTrainingCoordinator.trainBasalHead` / `trainT3cHead`).
         *
         * The probe must not be tighter than the publish range, or the loader would refuse a model the
         * trainer just published and the two sides would fight forever. Values that are in the publish
         * range but outside the runtime clamp are accepted here and clamped later, which is why the log
         * line prints the raw value: a model living at 0.31 and a model that learned 0.50 both apply
         * 0.50, and only `raw` tells them apart.
         */
        const val BASAL_PROBE_OUTPUT_MIN = 0.5
        const val BASAL_PROBE_OUTPUT_MAX = 2.0
        const val T3C_PROBE_OUTPUT_MIN = 0.3
        const val T3C_PROBE_OUTPUT_MAX = 3.0

        const val GOVERNANCE_WINDOW_MAX = 288 // ~24h @ 5-min cadence
        const val GOVERNANCE_MIN_SAMPLES = 36 // ~3h warmup

        /** Delay after which a tick's outcome is considered observable. */
        const val OUTCOME_HORIZON_MS = 30L * 60_000
        const val OUTCOME_HORIZON_MIN_MS = 20L * 60_000
        const val OUTCOME_HORIZON_MAX_MS = 45L * 60_000
        const val GOVERNANCE_LOG_MIN_MS = 5 * 60 * 1000L

        const val GOVERNANCE_WEIGHT_NOISE_TIER3 = 0.35
        const val GOVERNANCE_WEIGHT_NOISE_TIER2 = 0.55
        const val GOVERNANCE_WEIGHT_NOISE_TIER1 = 0.75
        const val GOVERNANCE_WEIGHT_NOISE_LINEAR = 0.15
        const val GOVERNANCE_WEIGHT_MIN = 0.25
        const val GOVERNANCE_RAPID_FALL_DELTA_MGDL = -12.0
        const val GOVERNANCE_LOW_IOB_THRESHOLD_U = 0.35
        const val GOVERNANCE_WEIGHT_RAPID_FALL_LOW_IOB = 0.75
        const val GOVERNANCE_SEVERE_MIN_SAMPLE_WEIGHT = 0.45
    }

    private fun fmt5(value: Double): String =
        NumberFormat.withDecimals(5).format(value, NumberFormatPlatform.SEPARATOR_DOT)

    private fun fmt6(value: Double): String =
        NumberFormat.DECIMAL_6.format(value, NumberFormatPlatform.SEPARATOR_DOT)
}
