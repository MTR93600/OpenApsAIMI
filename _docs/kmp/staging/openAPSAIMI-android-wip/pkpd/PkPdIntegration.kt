package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.compose.readAimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class MealAggressionContext(
    val mealModeActive: Boolean,
    val predictedBgMgdl: Double? = null,
    val targetBgMgdl: Double? = null
)

data class PkpdBolusSample(
    val ageMin: Double,
    val units: Double
)

class PkPdIntegration(private val preferences: Preferences) {

    companion object {
        /** Minimum learned DIA change (hours) before writing prefs again. */
        const val DIA_PERSIST_MIN_DELTA_H = 0.005

        /** Minimum learned peak change (minutes) before writing prefs again. */
        const val PEAK_PERSIST_MIN_DELTA_MIN = 0.05

        /** [PkpdLearningTrace.diaLearnBlockedBy]: this call does not learn, it only reads. */
        const val LEARN_BLOCKED_READ_ONLY_PATH = "read_only_path"

        /** [PkpdLearningTrace.diaLearnBlockedBy]: the causal state was not clean enough to learn from. */
        const val LEARN_BLOCKED_CAUSAL_UNCLEAN = "causal_unclean"

        /**
         * [PkpdLearningTrace.diaLearnBlockedBy]: start of the value used when
         * [CausalKineticsModulator] refuses structural learning. The modulator's own reason is
         * added after it, so the exported value says which causal branch closed the gate.
         */
        const val LEARN_BLOCKED_CAUSAL_MODULATOR_PREFIX = "causal_modulator:"
    }

    /**
     * Loop-facing configuration that must not include learned DIA/peak state.
     * Learned values live in prefs and in [estimator]; comparing them here used to
     * reset the estimator on every successful persist.
     */
    private data class StructuralConfig(
        val enabled: Boolean,
        val bounds: PkPdBounds,
        val isfBounds: IsfFusionBounds,
        val tailPolicy: TailAwareSmbPolicy,
        val anchorDiaHrs: Double,
        val anchorPeakMin: Double,
    )

    private var cachedStructuralConfig: StructuralConfig? = null
    private var estimator: AdaptivePkPdEstimator? = null
    private var lastBounds: PkPdBounds? = null
    private var lastLearningCfg: PkPdLearningConfig? = null
    private var fusion: IsfFusion? = null
    private var lastFusionBounds: IsfFusionBounds? = null
    private var damping: SmbDamping? = null
    private var lastTailPolicy: TailAwareSmbPolicy? = null
    private var lastPersisted: PkPdParams? = null
    private var recentBolusSamples: List<PkpdBolusSample> = emptyList()

    @Synchronized
    fun setRecentBolusSamples(samples: List<PkpdBolusSample>) {
        recentBolusSamples = samples
            .asSequence()
            .filter { it.units > 0.0 && it.ageMin.isFinite() && it.ageMin >= 0.0 }
            .toList()
    }

    @Synchronized
    fun reconstructedIobUnits(): Double {
        val est = estimator ?: return 0.0
        return recentBolusSamples.sumOf { sample ->
            sample.units * est.iobResidualAt(sample.ageMin)
        }.coerceAtLeast(0.0)
    }

    fun learningStatusSnapshot(): AdaptivePkPdStatusSnapshot? = estimator?.statusSnapshot()

    @Synchronized
    fun computeRuntime(
        epochMillis: Long,
        bg: Double,
        deltaMgDlPer5: Double,
        iobU: Double,
        carbsActiveG: Double,
        windowMin: Int,
        exerciseFlag: Boolean,
        profileIsf: Double,
        tdd24h: Double,
        mealContext: MealAggressionContext? = null,
        consoleLog: MutableList<String>? = null,
        combinedDelta: Double? = null,
        uamConfidence: Double = 0.0,
        patientWeightKg: Double? = null,
        physioLatentState: PhysioLatentState? = null,
        estimatedRaMgdlPerMin: Double? = null,
        causalStatePosterior: CausalStatePosterior? = null,
        allowLearning: Boolean = true,
        patientEventMemory: PatientEventMemory? = null,
    ): PkPdRuntime? {
        val structural = readStructuralConfig()
        val previousStructural = cachedStructuralConfig
        if (previousStructural != null && previousStructural != structural) {
            applyStructuralConfigChange(previousStructural, structural)
        }
        cachedStructuralConfig = structural

        if (!structural.enabled) {
            consoleLog?.add("PKPD Debug: Config ENABLED is FALSE. Check OApsAIMIPkpdEnabled preference.")
            clearAllCaches()
            return null
        }

        if (lastPersisted == null) {
            lastPersisted = readLearnedSeed(structural.bounds)
        }

        val learningCfg = buildLearningConfig(structural)
        val estimator = ensureEstimator(structural.bounds, learningCfg)
        val fusion = ensureFusion(structural.isfBounds)
        val damping = ensureDamping(structural.tailPolicy)
        val tddIsf = computeTddIsf(tdd24h, profileIsf)
        IsfTddProvider.set(tddIsf)
        val epochMin = TimeUnit.MILLISECONDS.toMinutes(epochMillis)
        val learningWindowMin = windowMin.coerceIn(
            AdaptivePkPdEstimator.LEARNING_WINDOW_MIN_MIN,
            AdaptivePkPdEstimator.LEARNING_WINDOW_MAX_MIN,
        )
        if (learningWindowMin != windowMin) {
            consoleLog?.add(
                "PKPD_LEARN: windowMin=$windowMin clamped to $learningWindowMin for estimator.update " +
                    "(bounds ${AdaptivePkPdEstimator.LEARNING_WINDOW_MIN_MIN}-${AdaptivePkPdEstimator.LEARNING_WINDOW_MAX_MIN})",
            )
        }
        val learningContextClean = causalStatePosterior?.learningContextClean() ?: true
        val causalModulation = CausalKineticsModulator.modulate(causalStatePosterior)
        val causalLearningAllowed = causalModulation.learningAllowed
        // Same three booleans as the guard below, read where the guard is really taken.
        // The snapshot used to rebuild this decision later in the tick from a newer causal state,
        // so it could not be trusted. This value is exported as PkpdLearningTrace.diaLearnBlockedBy.
        val learnBlockedBy: String? = when {
            !allowLearning         -> LEARN_BLOCKED_READ_ONLY_PATH
            !learningContextClean  -> LEARN_BLOCKED_CAUSAL_UNCLEAN
            !causalLearningAllowed -> LEARN_BLOCKED_CAUSAL_MODULATOR_PREFIX + causalModulation.reason
            else                   -> null
        }
        logPkpdLearningSkipReason(
            bg = bg,
            iobU = iobU,
            carbsActiveG = carbsActiveG,
            deltaMgDlPer5 = deltaMgDlPer5,
            exerciseFlag = exerciseFlag,
            causalStatePosterior = causalStatePosterior,
            learningContextClean = learningContextClean && causalLearningAllowed,
            consoleLog = consoleLog,
        )
        if (allowLearning && learningContextClean && causalLearningAllowed) {
            estimator.update(
                epochMin = epochMin,
                bg = bg,
                deltaMgDlPer5 = deltaMgDlPer5,
                iobU = iobU,
                carbsActiveG = carbsActiveG,
                windowMin = learningWindowMin,
                exerciseFlag = exerciseFlag
            )
        } else if (allowLearning && learningContextClean && !causalLearningAllowed) {
            consoleLog?.add("PKPD_LEARN skip: causal_modulator_learningAllowed=false")
        }
        val params = estimator.params()
        persistStateIfNeeded(params, structural.bounds)
        val tailFraction = estimator.iobResidualAt(windowMin.toDouble()).coerceIn(0.0, 1.0)
        val baselineActivityState = estimator.activityStateAt(windowMin.toDouble())
        val activityState = aggregateActivityState(
            estimator = estimator,
            baseline = baselineActivityState,
            iobU = iobU
        )
        val freshness = (1.0 - activityState.postWindowFraction).coerceIn(0.0, 1.0)
        val activityBlend = (0.6 * activityState.relativeActivity + 0.4 * freshness).coerceIn(0.0, 1.0)
        val anticipatoryBoost = activityState.anticipationWeight * 0.1
        val mealBoost = mealContext?.let { ctx ->
            if (!ctx.mealModeActive) return@let 0.0
            val predicted = ctx.predictedBgMgdl
            val target = ctx.targetBgMgdl
            val normalizedRise = if (predicted != null && target != null) {
                ((predicted - target).coerceAtLeast(0.0) / 70.0).coerceIn(0.0, 1.0)
            } else 0.0
            0.05 + 0.15 * normalizedRise
        } ?: 0.0
        val behaviorProfile = readAimiBehaviorRuntimeProfile(preferences)
        val weightKineticFactor = buildWeightKineticFactor(patientWeightKg)
        val rawPhysioAbsorptionFactor = buildPhysioAbsorptionFactor(
            physioLatentState = physioLatentState,
            causalStatePosterior = causalStatePosterior,
            mealContext = mealContext,
            estimatedRaMgdlPerMin = estimatedRaMgdlPerMin,
            patientEventMemory = patientEventMemory,
        )
        val physioAbsorptionFactor = blendTowardNeutral(
            value = rawPhysioAbsorptionFactor,
            blendFraction = behaviorProfile.pkpdPhysioBlendFraction(),
        )
        val familyMealFactor = behaviorProfile.pkpdMealAbsorptionFactor(mealContext?.mealModeActive == true)
        val minScale = if (mealContext?.mealModeActive == true) 0.9 else 0.8
        val maxScale = if (mealContext?.mealModeActive == true) 1.5 else 1.4
        val pkpdScale = (
            1.0 +
                0.12 * tailFraction +
                0.22 * activityBlend +
                anticipatoryBoost +
                mealBoost
            )
            .times(weightKineticFactor)
            .times(physioAbsorptionFactor)
            .times(familyMealFactor)
            .coerceIn(minScale * 0.92, maxScale * 1.06)

        val effectiveDelta = combinedDelta ?: deltaMgDlPer5
        val isRising = effectiveDelta > 0.5

        var aggressionMultiplier = if (effectiveDelta > 1.5) {
            val rawFactor = Math.exp(-0.04 * (effectiveDelta - 1.5))
            rawFactor.coerceIn(0.60, 1.0)
        } else 1.0

        if (uamConfidence > 0.5) {
            val uamBoost = 1.0 - (uamConfidence - 0.5) * 0.4
            aggressionMultiplier *= uamBoost.coerceIn(0.8, 1.0)
            consoleLog?.add("🧠 UAM detected (conf=${"%.2f".format(uamConfidence)}) -> Extra ISF Boost")
        }
        val rawPhysioSiFactor = buildPhysioSiFactor(
            physioLatentState = physioLatentState,
            causalStatePosterior = causalStatePosterior,
            patientEventMemory = patientEventMemory,
        )
        val physioSiFactor = blendTowardNeutral(
            value = rawPhysioSiFactor,
            blendFraction = behaviorProfile.pkpdPhysioBlendFraction(),
        )
        aggressionMultiplier = (
            aggressionMultiplier *
                physioSiFactor *
                behaviorProfile.pkpdCorrectionAggressionFactor() *
                behaviorProfile.pkpdStabilityAggressionFactor(isRising)
            )
            .coerceIn(0.55, 1.08)
        physioLatentState?.takeIf { it.isActive() }?.let { latent ->
            consoleLog?.add(
                "PKPD_PHYSIO: wK=${"%.2f".format(weightKineticFactor)} " +
                    "abs=${"%.2f".format(physioAbsorptionFactor)} si=${"%.2f".format(physioSiFactor)} " +
                    "meal=${"%.2f".format(latent.mealProb)} endo=${"%.2f".format(latent.endogenousGlucoseDrive)} " +
                    "resist=${"%.2f".format(latent.transientResistanceProb)} postHypo=${"%.2f".format(latent.postHypoReboundProb)} " +
                    "cause=${causalStatePosterior?.dominant?.name ?: "UNKNOWN"} " +
                    "learnQ=${"%.2f".format(causalStatePosterior?.learningQuality ?: 0.0)}"
            )
        }
        consoleLog?.add(
            "PKPD_FAMILY: prot=${behaviorProfile.protectionLevel} meal=${behaviorProfile.mealCaptureLevel} " +
                "stab=${behaviorProfile.stabilityLevel} phys=${behaviorProfile.physioLevel} auto=${behaviorProfile.autonomyLevel} " +
                "mealF=${"%.2f".format(familyMealFactor)} physBlend=${"%.2f".format(behaviorProfile.pkpdPhysioBlendFraction())}",
        )

        val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale, isRising, aggressionMultiplier)
        return PkPdRuntime(
            params = params,
            tailFraction = tailFraction,
            fusedIsf = fusedIsf,
            profileIsf = profileIsf,
            tddIsf = tddIsf,
            pkpdScale = pkpdScale,
            weightKineticFactor = weightKineticFactor,
            physioAbsorptionFactor = physioAbsorptionFactor,
            physioSiFactor = physioSiFactor,
            damping = damping,
            activity = activityState,
            learningTrace = buildLearningTrace(estimator, structural.bounds, learnBlockedBy),
        )
    }

    /**
     * Small read-only view of the DIA learning loop, exported in the intelligence snapshot.
     * It makes a frozen DIA and a too short insulin tail visible without reading the source.
     */
    private fun buildLearningTrace(
        estimator: AdaptivePkPdEstimator,
        bounds: PkPdBounds,
        learnBlockedBy: String?,
    ): PkpdLearningTrace {
        val status = estimator.statusSnapshot()
        return PkpdLearningTrace(
            diaAtFloor = abs(status.params.diaHrs - bounds.diaMinH) <= 0.01,
            diaRegPullH = status.lastDiaRegPullH,
            diaLearnStepH = status.lastDiaLearnStepH,
            iobResidual120Min = estimator.iobResidualAt(120.0),
            diaAcceptedUpdates = status.acceptedUpdateCount,
            diaLearnBlockedBy = learnBlockedBy,
        )
    }

    private fun applyStructuralConfigChange(old: StructuralConfig, new: StructuralConfig) {
        estimator?.let { persistStateIfNeeded(it.params(), new.bounds) }

        val learningInputsChanged = old.bounds != new.bounds ||
            old.anchorDiaHrs != new.anchorDiaHrs ||
            old.anchorPeakMin != new.anchorPeakMin
        if (learningInputsChanged) {
            estimator = null
            lastBounds = null
            lastLearningCfg = null
        }
        if (old.isfBounds != new.isfBounds) {
            fusion = null
            lastFusionBounds = null
        }
        if (old.tailPolicy != new.tailPolicy) {
            damping = null
            lastTailPolicy = null
        }
    }

    private fun clearAllCaches() {
        estimator = null
        fusion = null
        damping = null
        lastBounds = null
        lastFusionBounds = null
        lastTailPolicy = null
        lastLearningCfg = null
        cachedStructuralConfig = null
        lastPersisted = null
    }

    private fun aggregateActivityState(
        estimator: AdaptivePkPdEstimator,
        baseline: InsulinActivityState,
        iobU: Double
    ): InsulinActivityState {
        val samples = recentBolusSamples
        if (samples.isEmpty()) return baseline

        val weightsByStage = linkedMapOf<InsulinActivityStage, Double>()
        var totalWeight = 0.0
        var weightedRelative = 0.0
        var weightedPosition = 0.0
        var weightedPostWindow = 0.0
        var weightedAnticipation = 0.0
        var weightedMinutesToOnset = 0.0

        samples.forEach { sample ->
            val bolusState = estimator.activityStateAt(sample.ageMin)
            val residualIob = estimator.iobResidualAt(sample.ageMin)
            val baseWeight = (sample.units * residualIob).coerceAtLeast(0.0)
            if (baseWeight <= 1e-6) return@forEach

            val stageWeight = (baseWeight * bolusState.relativeActivity.coerceAtLeast(0.05)).coerceAtLeast(1e-6)
            totalWeight += stageWeight
            weightsByStage[bolusState.stage] = (weightsByStage[bolusState.stage] ?: 0.0) + stageWeight
            weightedRelative += bolusState.relativeActivity * stageWeight
            weightedPosition += bolusState.normalizedPosition * stageWeight
            weightedPostWindow += bolusState.postWindowFraction * stageWeight
            weightedAnticipation += bolusState.anticipationWeight * stageWeight
            weightedMinutesToOnset += bolusState.minutesUntilOnset * stageWeight
        }

        if (totalWeight <= 1e-6) return baseline

        val dominantStage = weightsByStage.maxByOrNull { it.value }?.key ?: baseline.stage
        val coherentStage = if (dominantStage == InsulinActivityStage.PRE_ONSET && iobU >= 3.0) {
            InsulinActivityStage.RISING
        } else {
            dominantStage
        }

        return baseline.copy(
            relativeActivity = (weightedRelative / totalWeight).coerceIn(0.0, 1.0),
            normalizedPosition = (weightedPosition / totalWeight).coerceIn(0.0, 1.0),
            postWindowFraction = (weightedPostWindow / totalWeight).coerceIn(0.0, 1.0),
            anticipationWeight = (weightedAnticipation / totalWeight).coerceIn(0.0, 1.0),
            minutesUntilOnset = (weightedMinutesToOnset / totalWeight).coerceAtLeast(0.0),
            stage = coherentStage
        )
    }

    private fun readStructuralConfig(): StructuralConfig {
        val enabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
        val bounds = PkPdBounds(
            diaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
            diaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
            peakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            peakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            maxDiaChangePerDayH = PkpdLearningBounds.coerceMaxDiaChangePerDayH(
                preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
            ),
            maxPeakChangePerDayMin = PkpdLearningBounds.coerceMaxPeakChangePerDayMin(
                preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin),
            ),
        ).normalized()
        val isfBounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick),
        ).normalized()
        val effectiveTailDamping = PkpdSmbTailDamping.effectiveStoredValue(
            preferences.get(DoubleKey.OApsAIMISmbTailDamping),
        )
        val tailPolicy = TailAwareSmbPolicy(
            tailIobHigh = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
            smbDampingAtTail = effectiveTailDamping,
            postExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
            lateFattyMealDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)
        )
        return StructuralConfig(
            enabled = enabled,
            bounds = bounds,
            isfBounds = isfBounds,
            tailPolicy = tailPolicy,
            // The anchor is the soft target the learner is pulled back to. An anchor outside the
            // bounds would pull the learned value onto a bound and keep it there forever, so it is
            // kept inside the (already normalized) bounds whatever the preset or slider wrote.
            anchorDiaHrs = preferences.get(DoubleKey.OApsAIMIPkpdAnchorDiaH)
                .coerceIn(bounds.diaMinH, bounds.diaMaxH),
            anchorPeakMin = preferences.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin)
                .coerceIn(bounds.peakMinMin, bounds.peakMinMax),
        )
    }

    private fun readLearnedSeed(bounds: PkPdBounds): PkPdParams =
        clampParams(
            sanitizePkPdParams(
                PkPdParams(
                    diaHrs = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH),
                    peakMin = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin),
                ),
            ),
            bounds,
        )

    private fun buildLearningConfig(structural: StructuralConfig): PkPdLearningConfig =
        PkPdLearningConfig(
            bounds = structural.bounds,
            anchorDiaHrs = structural.anchorDiaHrs,
            anchorPeakMin = structural.anchorPeakMin,
        )

    private fun ensureEstimator(bounds: PkPdBounds, learningCfg: PkPdLearningConfig): AdaptivePkPdEstimator {
        if (estimator == null || lastBounds != bounds || lastLearningCfg != learningCfg) {
            val start = estimator?.params()?.let { clampParams(it, bounds) }
                ?: lastPersisted
                ?: readLearnedSeed(bounds)
            estimator = AdaptivePkPdEstimator(ExponentialKernel(), learningCfg, start)
            lastBounds = bounds
            lastLearningCfg = learningCfg
        }
        return estimator!!
    }

    private fun ensureFusion(bounds: IsfFusionBounds): IsfFusion {
        if (fusion == null || lastFusionBounds != bounds) {
            fusion = IsfFusion(bounds)
            lastFusionBounds = bounds
        }
        return fusion!!
    }

    private fun ensureDamping(policy: TailAwareSmbPolicy): SmbDamping {
        if (damping == null || lastTailPolicy != policy) {
            damping = SmbDamping(policy)
            lastTailPolicy = policy
        }
        return damping!!
    }

    private fun clampParams(params: PkPdParams, bounds: PkPdBounds): PkPdParams {
        val safe = sanitizePkPdParams(params)
        val b = bounds.normalized()
        val dia = safe.diaHrs.coerceIn(b.diaMinH, b.diaMaxH)
        val peak = safe.peakMin.coerceIn(b.peakMinMin, b.peakMinMax)
        return PkPdParams(dia, peak)
    }

    private fun persistStateIfNeeded(params: PkPdParams, bounds: PkPdBounds) {
        val clamped = clampParams(params, bounds)
        val last = lastPersisted
        val shouldPersist = last == null ||
            abs(last.diaHrs - clamped.diaHrs) > DIA_PERSIST_MIN_DELTA_H ||
            abs(last.peakMin - clamped.peakMin) > PEAK_PERSIST_MIN_DELTA_MIN
        if (shouldPersist) {
            preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, clamped.diaHrs)
            preferences.put(DoubleKey.OApsAIMIPkpdStatePeakMin, clamped.peakMin)
            lastPersisted = clamped
        }
    }

    private fun logPkpdLearningSkipReason(
        bg: Double,
        iobU: Double,
        carbsActiveG: Double,
        deltaMgDlPer5: Double,
        exerciseFlag: Boolean,
        causalStatePosterior: CausalStatePosterior?,
        learningContextClean: Boolean,
        consoleLog: MutableList<String>?,
    ) {
        val reason = when {
            bg < AdaptivePkPdEstimator.HYPO_LEARN_SKIP_BG_MGDL -> "bg<hypo"
            bg < AdaptivePkPdEstimator.HYPO_CAUTION_BG_MGDL && deltaMgDlPer5 < -1.0 -> "bg_near_hypo_falling"
            deltaMgDlPer5 <= AdaptivePkPdEstimator.FAST_FALL_DELTA_MGDL5 -> "fast_fall"
            iobU < 0.3 -> "iob<0.3"
            carbsActiveG > 15.0 -> "cob>15"
            exerciseFlag -> "exercise"
            deltaMgDlPer5 > 5.0 -> "delta>5"
            !learningContextClean -> "causal_${causalStatePosterior?.dominant?.name?.lowercase() ?: "unclean"}"
            else -> null
        }
        if (reason != null) {
            consoleLog?.add("PKPD_LEARN skip: $reason (update() returns early in estimator)")
        }
    }

    private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
        if (tdd24h <= 0.1) return fallback
        val anchored = 1800.0 / tdd24h

        val maxDeviation = fallback * 0.5
        val clamped = anchored.coerceIn(
            fallback - maxDeviation,
            fallback + maxDeviation
        )

        return clamped.coerceIn(5.0, 400.0)
    }

    private fun blendTowardNeutral(value: Double, blendFraction: Double): Double {
        val safeBlend = blendFraction.coerceIn(0.0, 1.0)
        return 1.0 + ((value - 1.0) * safeBlend)
    }

    private fun buildWeightKineticFactor(patientWeightKg: Double?): Double {
        val safeWeight = patientWeightKg?.takeIf { it.isFinite() && it >= 40.0 } ?: return 1.0
        return (75.0 / safeWeight).coerceIn(0.84, 1.08)
    }

    private fun buildPhysioAbsorptionFactor(
        physioLatentState: PhysioLatentState?,
        causalStatePosterior: CausalStatePosterior?,
        mealContext: MealAggressionContext?,
        estimatedRaMgdlPerMin: Double?,
        patientEventMemory: PatientEventMemory?,
    ): Double {
        if (physioLatentState == null && causalStatePosterior == null) return 1.0
        val mealSupportProb = max(
            physioLatentState?.mealProb ?: 0.0,
            causalStatePosterior?.mealConfidence ?: 0.0,
        )
        val mealSupport = if (mealContext?.mealModeActive == true) {
            mealSupportProb * 0.12
        } else {
            0.0
        }
        val endogenousBrake = max(
            physioLatentState?.endogenousGlucoseDrive ?: 0.0,
            causalStatePosterior?.dawnEndogenousProb ?: 0.0,
        ) * 0.10
        val reboundBrake = max(
            physioLatentState?.postHypoReboundProb ?: 0.0,
            causalStatePosterior?.postHypoRecoveryProb ?: 0.0,
        ) * 0.09
        val protectiveBrake = (causalStatePosterior?.protectiveConfidence ?: 0.0) * 0.06
        val fragilityBrake = (patientEventMemory?.correctionFragilityScore ?: 0.0) * 0.08
        val exhaustionBrake = (patientEventMemory?.postHyperExhaustionScore ?: 0.0) * 0.05
        val raSupport = estimatedRaMgdlPerMin
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it / 12.0).coerceIn(0.0, 0.08) }
            ?: 0.0
        val dominantMealBoost = when (causalStatePosterior?.dominant) {
            CausalStateId.FAST_MEAL -> 0.03
            CausalStateId.PROLONGED_MEAL -> 0.02
            else -> 0.0
        }
        return (
            1.0 + mealSupport + raSupport + dominantMealBoost -
                endogenousBrake - reboundBrake - protectiveBrake - fragilityBrake - exhaustionBrake
            )
            .coerceIn(0.82, 1.18)
    }

    private fun buildPhysioSiFactor(
        physioLatentState: PhysioLatentState?,
        causalStatePosterior: CausalStatePosterior?,
        patientEventMemory: PatientEventMemory?,
    ): Double {
        if (physioLatentState == null && causalStatePosterior == null) return 1.0
        val latent = physioLatentState ?: PhysioLatentState()
        var rawFactor = latent.circadianSiFactor *
            (1.0 - latent.transientResistanceProb * 0.14) *
            (1.0 + latent.postHypoReboundProb * 0.08)
        rawFactor *= when (causalStatePosterior?.dominant) {
            CausalStateId.DAWN_ENDOGENOUS -> 0.97
            CausalStateId.STRESS_RESISTANCE -> 0.94
            CausalStateId.INFLAMMATORY_DRIFT -> 0.95
            CausalStateId.POST_HYPO_RECOVERY -> 1.02
            else -> 1.0
        }
        rawFactor *= 1.0 - (causalStatePosterior?.stressResistanceProb ?: 0.0) * 0.05
        rawFactor *= 1.0 - (causalStatePosterior?.inflammatoryDriftProb ?: 0.0) * 0.04
        rawFactor *= 1.0 + (patientEventMemory?.correctionFragilityScore ?: 0.0) * 0.06
        rawFactor *= 1.0 + (patientEventMemory?.postHyperExhaustionScore ?: 0.0) * 0.04
        val confidence = max(latent.sensorConfidence, causalStatePosterior?.learningQuality ?: 0.0).coerceIn(0.0, 1.0)
        return (1.0 + ((rawFactor - 1.0) * confidence)).coerceIn(0.78, 1.08)
    }
}

/**
 * Read-only numbers about the DIA learning loop, for the intelligence snapshot.
 *
 * @property diaAtFloor learned DIA sitting on the lower bound (within 0.01 h)
 * @property diaRegPullH last pull back toward the anchor, in hours
 * @property diaLearnStepH last raw learning step, in hours, before the anchor pull and any clamp
 * @property iobResidual120Min insulin left on board 2 h after a dose, 0..1
 * @property diaAcceptedUpdates how many learning steps the estimator has accepted so far. When this
 *   number is the same as on the tick before, [diaRegPullH] and [diaLearnStepH] were kept from an
 *   older tick and must not be read as this tick's step.
 * @property diaLearnBlockedBy why learning did not run on this tick, or null when it did run. Taken
 *   in [PkPdIntegration.computeRuntime] at the line where the gate is really applied.
 */
data class PkpdLearningTrace(
    val diaAtFloor: Boolean,
    val diaRegPullH: Double,
    val diaLearnStepH: Double,
    val iobResidual120Min: Double,
    val diaAcceptedUpdates: Long = 0L,
    val diaLearnBlockedBy: String? = null,
)

class PkPdRuntime(
    val params: PkPdParams,
    val tailFraction: Double,
    val fusedIsf: Double,
    val profileIsf: Double,
    val tddIsf: Double,
    val pkpdScale: Double,
    val weightKineticFactor: Double,
    val physioAbsorptionFactor: Double,
    val physioSiFactor: Double,
    private val damping: SmbDamping,
    val activity: InsulinActivityState,
    val learningTrace: PkpdLearningTrace? = null,
) {

    fun dampSmbWithAudit(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        elapsedSinceMealMin: Double = 0.0
    ): SmbDampingAudit =
        damping.dampWithAudit(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity, elapsedSinceMealMin)

    fun dampSmb(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        elapsedSinceMealMin: Double = 0.0
    ): Double =
        damping.damp(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity, elapsedSinceMealMin)
}
