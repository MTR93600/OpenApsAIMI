package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthResistanceMode
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cAnticipation
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cTrajectoryContext
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealRiseGeometry
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.risk.IobConsensus
import app.aaps.plugins.aps.openAPSAIMI.safety.CompressionReboundGuard
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndometriosisAdjuster
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Instant as KotlinInstant

/**
 * Members [buildRbtExtendedSignals] already holds when the reference enters the function.
 * Preference reads stay on the lines below, through [Preferences] and the Android ports.
 */
internal class RbtExtendedTickState(
    val iob: Float,
    val bg: Double,
    val delta: Float,
    val shortAvgDelta: Float,
    val longAvgDelta: Float,
    val eventualBG: Double,
    val targetBg: Float,
    val hourOfDay: Int,
    val adaptiveMult: Double,
    val bgacc: Double,
    val duraISFminutes: Double,
    val duraISFaverage: Double,
    val maxIob: Double,
    val variableSensitivity: Float,
    val maxSMB: Double,
    val maxSMBHB: Double,
    val mealTime: Boolean,
    val bfastTime: Boolean,
    val lunchTime: Boolean,
    val dinnerTime: Boolean,
    val highCarbTime: Boolean,
    val snackTime: Boolean,
    val sleepTime: Boolean,
    val exerciseInsulinLockoutActive: Boolean,
    val exerciseHyperBasalOverrideActive: Boolean,
    val exerciseBasalResumeBgMgdl: Double,
    val highBgOverrideUsed: Boolean,
    val mealAdvisorOneShotThisTick: Boolean,
    val lastScenarioBestCappedForPhysio: Boolean,
    val lastPhysioLatentState: PhysioLatentState?,
    val lastUamHypothesisState: UamHypothesisState?,
    val lastPatientState: PatientStateSnapshot?,
    val lastPatientModeDecision: PatientModeOrchestrator.Decision?,
    val lastMealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
    val lastPhysiologicalPhaseOutput: PhysiologicalPhaseClassifier.Output?,
    val lastPostHypoDeliveryAuthority: PostHypoDeliveryAuthority.Decision,
    val lastHarmoniaDecision: HarmoniaDecision?,
    val lastPhysiologicalTreeSnapshot: PhysiologicalTreeSnapshot?,
    val lastTubeAdvisorSmbCapScale: Double?,
    val tickInsulinActionState: InsulinActionState?,
    val correctionAggressionDecision: CorrectionAggressionGate.Decision?,
    val lastInflammationResult: InflammationAdjuster.InflammationResult?,
    val currentThyroidEffects: ThyroidEffects,
    val lastMealCertainty: MealCertainty?,
)

internal fun buildRbtExtendedSignals(
    rT: RT,
    profile: OapsProfileAimi,
    htr: HyperTrajectoryReleaseResult,
    v3SmbU: Double,
    autosens: AutosensResult,
    glucoseStatus: GlucoseStatusAIMI?,
    mpcFeedForwardRa: Double?,
    cbfShieldDeltaU: Double?,
    preferences: Preferences,
    dateUtil: DateUtil,
    consoleLog: MutableList<String>,
    state: RbtExtendedTickState,
    pkpd: PkPdRuntime?,
    pkpdIntegration: PkPdIntegration,
    basalNeuralLearner: BasalNeuralLearner,
    basalLearner: BasalLearner,
    nightGrowthResistanceMode: NightGrowthResistanceMode,
    trajectoryGuard: TrajectoryGuard,
    endoAdjuster: EndometriosisAdjuster,
    recentGlucose: AimiRecentGlucose,
    postHypoClassification: AimiPostHypoClassification,
    nightGrowthConfig: AimiNightGrowthConfig,
    physio: AimiPhysioTick,
    tickWrites: AimiRbtTickWrites,
): RbtExtendedSignals {
    val iobConsensus = IobConsensus.resolve(
        aapsIobUnits = state.iob.toDouble(),
        pkpdIobUnits = pkpdIntegration.reconstructedIobUnits().takeIf { pkpd != null },
    )
    val t3cHints = T3cAnticipation.buildHints(
        predictions = rT.predBGs,
        bgNow = state.bg,
        lgsThresholdMgdl = min(90.0, profile.lgsThreshold?.toDouble() ?: 70.0),
        activationThreshold = state.targetBg.toDouble() + 30.0,
        eventualBg = state.eventualBG.takeIf { it.isFinite() },
        strengthRaw = preferences.get(DoubleKey.OApsAIMIT3cAnticipationStrength),
    )
    val t3cEnabled = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
    val t3cGovernance = basalNeuralLearner.getGovernanceSnapshot()
    val recentBgs = recentGlucose.getRecentGlucose()
    val postHypo = postHypoClassification.classifyPostHypoState(
        recentBGs = recentBgs,
        cob = rT.COB?.toDouble() ?: 0.0,
        explicitMealMode = state.mealTime || state.bfastTime || state.lunchTime || state.dinnerTime || state.highCarbTime || state.snackTime,
        shortAvgDelta = state.shortAvgDelta,
        delta = state.delta,
        slopeFromMinDeviation = 0.0,
        estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs),
        estimatedCarbsAgeMs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong(),
        localHour = state.hourOfDay,
        reason = StringBuilder(),
    )
    val postHypoOrdinal = when (postHypo) {
        is PostHypoState.None -> 0
        is PostHypoState.ReboundSuspected -> 1
        is PostHypoState.MealConfirmed -> 2
    }
    tickWrites.setLastPostHypoOrdinal(postHypoOrdinal)
    val ngrConfig = nightGrowthConfig.buildNightGrowthResistanceConfig(profile, autosens, glucoseStatus, state.targetBg.toDouble())
    val ngrResult = nightGrowthResistanceMode.evaluate(
        now = KotlinInstant.fromEpochMilliseconds(dateUtil.now()),
        bg = state.bg,
        delta = state.delta.toDouble(),
        shortAvgDelta = state.shortAvgDelta.toDouble(),
        longAvgDelta = state.longAvgDelta.toDouble(),
        eventualBG = state.eventualBG,
        targetBG = state.targetBg.toDouble(),
        iob = state.iob.toDouble(),
        cob = rT.COB?.toDouble() ?: 0.0,
        react = state.bg,
        isMealActive = state.mealTime || state.bfastTime || state.lunchTime || state.dinnerTime || state.highCarbTime || state.snackTime,
        config = ngrConfig,
    )
    // Expose the NGR nocturnal basal multiplier to the T3C engine (executeT3cBrittleMode consumes it).
    tickWrites.setLastNgrBasalMultiplier(ngrResult.basalMultiplier)
    val endoFactors = readRbtOptional("endometriosis", consoleLog) {
        endoAdjuster.calculateFactors(state.bg, state.delta.toDouble())
    }.valueOrNull()
    val physioTrace = physio.getLastDecisionTrace()
    val physioCtx = physio.getEffectiveContext()
    val wearableSnap = physio.getLatestSnapshot()
    val auditorVerdict = readRbtOptional("auditorCache", consoleLog) {
        app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(300_000)?.verdict
    }.valueOrNull()
    val traj = trajectoryGuard.getLastAnalysis()
    val spiralCap = if (traj?.classification == TrajectoryType.TIGHT_SPIRAL) {
        max(state.maxSMBHB, state.maxSMB) * (1.0 - (traj.metrics.energyBalance / 10.0).coerceIn(0.0, 0.75))
    } else {
        null
    }
    val latentState = state.lastPhysioLatentState
    val hypothesisState = state.lastUamHypothesisState
    val patientState = state.lastPatientState
    val patientModeDecision = state.lastPatientModeDecision
    val clockIsNight = state.hourOfDay >= 23 || state.hourOfDay < 6
    val sleepLive = SleepLiveDetector.evaluate(
        SleepLiveDetector.Input(
            therapySleepTime = state.sleepTime,
            stepsLast15m = wearableSnap.stepsLast15m,
            stepsLast5m = wearableSnap.stepsLast5m,
            hrNowBpm = wearableSnap.hrNow,
            rhrRestingBpm = wearableSnap.rhrResting,
            hcSessionActive = wearableSnap.hcSleepSessionActive,
            clockIsNight = clockIsNight,
        ),
    )
    if (sleepLive.isAsleep) {
        consoleLog.add("😴 SLEEP_LIVE: ${sleepLive.summary} conf=${aimiFmt2(sleepLive.confidence)}")
    }
    val t3cTrajectory = if (t3cEnabled) {
        val lgsT3c = min(90.0, (profile.lgsThreshold?.toDouble() ?: 70.0).coerceAtLeast(70.0))
        val minPredT3c = rT.predBGs?.IOB?.minOrNull()?.toDouble() ?: state.bg
        val eventualT3c = rT.eventualBG?.takeIf { it.isFinite() } ?: state.eventualBG.coerceAtLeast(40.0)
        T3cTrajectoryContext.build(
            minPredBg = minPredT3c,
            eventualPredBg = eventualT3c,
            bg = state.bg,
            lgsThresholdMgdl = lgsT3c,
            trajectoryEnabled = rT.trajectoryEnabled == true,
            lastAnalysis = traj,
        )
    } else {
        null
    }
    val t3cDemandRate = if (t3cEnabled) {
        val baseBasal = profile.current_basal
        val maxBasalCap = profile.max_basal.coerceAtLeast(baseBasal)
        val rawAggressiveness = basalNeuralLearner.getT3cAdaptiveFactor(
            bg = state.bg,
            basal = baseBasal,
            accel = state.bgacc,
            duraMin = state.duraISFminutes,
            duraAvg = state.duraISFaverage,
            iob = state.iob.toDouble(),
            physioFeatures = SmbRefinementFeatureSchema.latentFeatureValues(state.lastPhysioLatentState) +
                SmbRefinementFeatureSchema.modeFeatureValues(state.lastPatientModeDecision) +
                SmbRefinementFeatureSchema.causalFeatureValues(state.lastPatientState?.causalPosterior),
        )
        val adaptiveBoost = if (state.adaptiveMult > 1.0) {
            (state.adaptiveMult - 1.0).coerceAtMost(0.40)
        } else {
            state.adaptiveMult - 1.0
        }
        val aggressiveness = (rawAggressiveness + rawAggressiveness * adaptiveBoost).coerceIn(0.3, 2.0)
        val computedRate = DynamicBasalController.computeT3c(
            bg = state.bg,
            targetBg = state.targetBg.toDouble(),
            delta = state.delta,
            shortAvgDelta = state.shortAvgDelta.toDouble(),
            longAvgDelta = state.longAvgDelta.toDouble(),
            accel = state.bgacc,
            iob = state.iob.toDouble(),
            maxIob = state.maxIob,
            profileBasal = baseBasal,
            isf = state.variableSensitivity.toDouble().coerceAtLeast(10.0),
            duraISFminutes = state.duraISFminutes,
            duraISFaverage = state.duraISFaverage,
            eventualBg = state.eventualBG.takeIf { it.isFinite() },
            activationThreshold = preferences.get(DoubleKey.OApsAIMIT3cActivationThreshold),
            aggressiveness = aggressiveness,
            maxBasalCap = maxBasalCap,
            trajectory = t3cTrajectory,
            anticipationHints = t3cHints,
        )
        if (computedRate > 0.0 && abs(state.adaptiveMult - 1.0) > 0.01) {
            (computedRate * state.adaptiveMult).coerceIn(0.0, maxBasalCap)
        } else {
            computedRate
        }
    } else {
        0.0
    }
    val t3cMealConflict = t3cEnabled &&
        state.lastMealAbsorptionOutput?.mealDeliveryPriority == true &&
        (
            hypothesisState?.suppressMealInterpretation == true ||
                state.lastPhysiologicalPhaseOutput?.policy?.suppressMealLikeScenario == true
            )
    val t3cPostHypoBlock = t3cEnabled && (
        postHypoOrdinal > 0 ||
            (
                state.lastPostHypoDeliveryAuthority.active &&
                    state.lastPostHypoDeliveryAuthority.forceMealInterpretationSuppressed
                )
        )
    val t3cExerciseBlock = t3cEnabled &&
        state.exerciseInsulinLockoutActive &&
        !state.exerciseHyperBasalOverrideActive &&
        state.bg <= state.exerciseBasalResumeBgMgdl
    val t3cHardSafetyBlock = t3cEnabled && (
        state.bg < (profile.lgsThreshold?.toDouble() ?: 70.0) ||
            t3cExerciseBlock
        )
    val t3cBlockReason = when {
        !t3cEnabled -> null
        state.bg < (profile.lgsThreshold?.toDouble() ?: 70.0) -> "HYPO_TERMINAL"
        t3cExerciseBlock -> "EXERCISE_LOCKOUT"
        t3cPostHypoBlock -> "POST_HYPO"
        t3cMealConflict -> "MEAL_CONFLICT"
        t3cDemandRate <= 0.0 -> "NO_BASAL_DEMAND"
        else -> null
    }
    val harmoniaDecision = state.lastHarmoniaDecision
    val harmoniaActive = harmoniaDecision != null
    val harmoniaMealConflict = harmoniaActive &&
        state.lastMealAbsorptionOutput?.mealDeliveryPriority == true &&
        (
            hypothesisState?.suppressMealInterpretation == true ||
                state.lastPhysiologicalPhaseOutput?.policy?.suppressMealLikeScenario == true
            )
    // Aggressive post-hypo rise: do not keep Harmonia in POST_HYPO block once we are acting normally
    // (same contract as RBT episode/mode bypass). Otherwise eligible MEAL_SUPPORT never reaches
    // basal-first and ticks log rbt_no_harmonia_channel / post_hypo during the climb.
    val aggressiveRiseExit = PostHypoAggressiveRiseExit.shouldExit(
        bgMgdl = state.bg,
        targetBgMgdl = state.targetBg.toDouble(),
        deltaMgdl5m = state.delta.toDouble(),
    )
    val harmoniaPostHypoBlock = harmoniaActive &&
        !aggressiveRiseExit &&
        (
            postHypoOrdinal > 0 ||
                state.lastPostHypoDeliveryAuthority.active
            )
    val harmoniaExerciseBlock = harmoniaActive && state.exerciseInsulinLockoutActive
    val harmoniaHardSafetyBlock = harmoniaActive && (
        state.bg < (profile.lgsThreshold?.toDouble() ?: 70.0) ||
            harmoniaExerciseBlock ||
            state.lastPhysiologicalTreeSnapshot?.trunk?.riskLevel == PhysiologicalRiskLevel.CRITICAL
        )
    val harmoniaProductionAction = harmoniaDecision?.action in setOf(
        HarmoniaAction.BASAL_FIRST,
        HarmoniaAction.MEAL_SUPPORT,
        HarmoniaAction.PROTECTIVE_REDUCTION,
        HarmoniaAction.STABILIZE,
    )
    val harmoniaBlockReason = when {
        !harmoniaActive -> null
        state.bg < (profile.lgsThreshold?.toDouble() ?: 70.0) -> "HYPO_TERMINAL"
        harmoniaExerciseBlock -> "EXERCISE_LOCKOUT"
        harmoniaPostHypoBlock -> "POST_HYPO"
        harmoniaMealConflict -> "MEAL_CONFLICT"
        harmoniaHardSafetyBlock -> "HARD_SAFETY"
        harmoniaDecision?.eligible != true -> harmoniaDecision?.blockers?.firstOrNull()?.uppercase()
            ?: "SIMULATION_INELIGIBLE"
        !harmoniaProductionAction -> "NO_PRODUCTION_ACTION"
        (harmoniaDecision?.targetBasalUph ?: 0.0) <= 0.0 -> "NO_BASAL_DEMAND"
        else -> null
    }
    return RbtExtendedSignals(
        tubeAdvisorCapScale = state.lastTubeAdvisorSmbCapScale,
        insulinActivityStageOrdinal = state.tickInsulinActionState?.activityStage?.ordinal
            ?: pkpd?.activity?.stage?.ordinal,
        pkpdTailFactor = pkpd?.tailFraction,
        compressionImpossibleRise = CompressionReboundGuard.isImpossibleRise(state.delta),
        highBgOverrideActive = state.highBgOverrideUsed,
        iobConsensusDelta = iobConsensus.deltaUnits,
        realtimeIobUnits = state.tickInsulinActionState?.effectiveIob ?: state.iob.toDouble(),
        mealAdvisorEstimateU = if (state.mealAdvisorOneShotThisTick) v3SmbU else null,
        mpcFeedForwardRa = mpcFeedForwardRa,
        cbfShieldDeltaU = cbfShieldDeltaU,
        t3cAnticipationStrength = t3cHints.strength,
        t3cActive = t3cEnabled,
        t3cBasalDemandRateUph = t3cDemandRate.takeIf { t3cEnabled },
        t3cBasalMaxRateUph = profile.max_basal.coerceAtLeast(profile.current_basal).takeIf { t3cEnabled },
        t3cMealConflict = t3cMealConflict,
        t3cPostHypoBlock = t3cPostHypoBlock,
        t3cExerciseBlock = t3cExerciseBlock,
        t3cHardSafetyBlock = t3cHardSafetyBlock,
        t3cBlockReason = t3cBlockReason,
        t3cGovernanceBasalFloorUph = t3cGovernance.activeBasalFloor,
        t3cGovernanceAggressivenessFloor = t3cGovernance.activeAggressivenessFloor,
        harmoniaActive = harmoniaActive,
        harmoniaDecisionEligible = harmoniaDecision?.eligible == true,
        harmoniaAction = harmoniaDecision?.action?.name,
        harmoniaBranch = harmoniaDecision?.branch,
        harmoniaBasalDemandRateUph = harmoniaDecision?.targetBasalUph,
        harmoniaBasalMaxRateUph = harmoniaDecision?.environment?.maxBasalUph
            ?.coerceAtMost(profile.max_basal.coerceAtLeast(profile.current_basal)),
        harmoniaSmbDemandU = harmoniaDecision?.targetSmbU,
        harmoniaSmbMaxU = harmoniaDecision?.environment?.maxSmbU
            ?.coerceAtMost(state.maxSMBHB.coerceAtLeast(state.maxSMB)),
        harmoniaMealConflict = harmoniaMealConflict,
        harmoniaPostHypoBlock = harmoniaPostHypoBlock,
        harmoniaExerciseBlock = harmoniaExerciseBlock,
        harmoniaHardSafetyBlock = harmoniaHardSafetyBlock,
        harmoniaBlockReason = harmoniaBlockReason,
        insulinIntent = state.lastPhysiologicalTreeSnapshot?.insulinIntent?.name,
        mealCertaintySupports = state.lastMealCertainty?.supportsMealSupport == true,
        riseConfirmed = state.lastMealCertainty?.riseGeometry == MealRiseGeometry.OK ||
            (state.delta >= 1.2f && state.bg >= 140.0),
        postHypoDeliverySuppressSmb =
            state.lastPostHypoDeliveryAuthority.active && state.lastPostHypoDeliveryAuthority.suppressMealDelivery,
        postHypoOrdinal = postHypoOrdinal,
        trajSpiralCapMaxSmb = spiralCap,
        pkpdLearnedDiaH = pkpd?.params?.diaHrs,
        pkpdLearnedPeakMin = pkpd?.params?.peakMin,
        isfFusionRatio = pkpd?.let { it.fusedIsf / it.profileIsf.coerceAtLeast(1.0) },
        kalmanIsf = state.variableSensitivity.toDouble().takeIf { it > 0.0 },
        pkpdTailDamping = app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping.effectiveStoredValue(
            preferences.get(DoubleKey.OApsAIMISmbTailDamping),
        ),
        correctionAggressionLevel = state.correctionAggressionDecision?.tier?.ordinal?.toDouble(),
        hormonalCapApplied = state.lastScenarioBestCappedForPhysio,
        ngrSmbMult = ngrResult.smbMultiplier.takeIf { it != 1.0 },
        ngrBasalMult = ngrResult.basalMultiplier.takeIf { it != 1.0 },
        inflammationSmbMult = state.lastInflammationResult?.smbMultiplier?.takeIf { it != 1.0 },
        inflammationIsfMult = state.lastInflammationResult?.isfMultiplier?.takeIf { it != 1.0 },
        basalAdaptMult = state.adaptiveMult.takeIf { it != 1.0 },
        ctxManagerIntentCount = rT.contextIntentCount,
        endometriosisFactor = endoFactors?.basalMult?.takeIf { it != 1.0 },
        thyroidIsfMult = state.currentThyroidEffects.isfMultiplier.takeIf { it != 1.0 },
        thyroidDiaMult = state.currentThyroidEffects.diaMultiplier.takeIf { it != 1.0 },
        thyroidGuardActive = state.currentThyroidEffects.status ==
            app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidStatus.NORMALIZING,
        basalLearnerShortMult = basalLearner.shortTermMultiplier,
        basalLearnerMedMult = basalLearner.mediumTermMultiplier,
        basalLearnerLongMult = basalLearner.longTermMultiplier,
        shadowAuditorConfidence = auditorVerdict?.confidence,
        shadowSentinelVerdictLabel = auditorVerdict?.verdict?.name,
        shadowOrchestratorActive = physioTrace?.shadowOrchestratorEnabled == true,
        tuningContextLabel = preferences.get(StringKey.AimiTuningContextSelection),
        htrLeafSmbFloorU = htr.smbFloorU,
        sleepDebtMinutes = wearableSnap.sleepDebtMinutes.toDouble().takeIf { it > 0 },
        latentMealProb = latentState?.mealProb,
        latentEndogenousGlucoseDrive = latentState?.endogenousGlucoseDrive,
        latentCircadianSiFactor = latentState?.circadianSiFactor,
        latentTransientResistanceProb = latentState?.transientResistanceProb,
        latentSleepDebtScore = latentState?.sleepDebtScore,
        latentSensorConfidence = latentState?.sensorConfidence,
        uamHypothesisDominant = hypothesisState?.dominant?.name,
        uamMealProb = hypothesisState?.mealProb,
        uamEndogenousProb = hypothesisState?.dawnEndogenousProb,
        uamStressProb = hypothesisState?.stressProb,
        uamPostHypoProb = hypothesisState?.postHypoProb,
        uamLateFatProb = hypothesisState?.lateFatProb,
        uamSuppressMealInterpretation = hypothesisState?.suppressMealInterpretation == true,
        patientMode = patientModeDecision?.mode?.name,
        patientModeConfidence = patientModeDecision?.confidence,
        patientStrategyHint = patientModeDecision?.strategyHint?.name,
        patientModeMealBias = patientModeDecision?.mealBias,
        patientModeProtectionBias = patientModeDecision?.protectionBias,
        causalDominantState = patientState?.causalPosterior?.dominant?.name,
        causalDominantConfidence = patientState?.causalPosterior?.dominantConfidence,
        causalMealConfidence = patientState?.causalPosterior?.mealConfidence,
        causalProtectiveConfidence = patientState?.causalPosterior?.protectiveConfidence,
        causalLearningQuality = patientState?.causalPosterior?.learningQuality,
        contextIntentDominant = patientState?.userIntent?.dominantIntent,
        contextIntentConfidence = patientModeDecision?.userIntentConfidence,
        physioMtrStateOrdinal = physioCtx.state.ordinal,
        hrvDeviationZ = physioCtx.hrvDeviationZ.takeIf { physioCtx.confidence > 0.0 },
        sleepQualityScore = physioCtx.features?.sleepQualityScore,
        sleepLiveConfidence = sleepLive.confidence,
        sleepLiveSource = sleepLive.source.name,
    )
}
