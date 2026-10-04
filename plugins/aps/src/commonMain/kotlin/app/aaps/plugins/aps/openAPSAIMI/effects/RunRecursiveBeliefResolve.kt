package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternInputBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefEngine
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefPreferences
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefResolver
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefTickContext
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminals
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmpAxis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmplitudeGovernor
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import kotlin.math.max

/** Lectures de champs du tick, chacune à la ligne où la référence les fait. */
internal interface AimiRbtResolveFields {
    fun scenario(): ScenarioProjectionPair?
    fun curves(): AdvancedPredictionCurves?
    fun bg(): Double
    fun targetBg(): Double
    fun delta(): Double
    fun shortAvgDelta(): Double
    fun mealAbsorption(): MealAbsorptionPhaseEngine.Output?
    fun phaseOutput(): PhysiologicalPhaseClassifier.Output?
    fun uamHypothesis(): UamHypothesisState?
    fun iob(): Double
    fun maxIob(): Double
    fun eventualBg(): Double
    fun mealModeActive(): Boolean
    fun hourOfDay(): Int
    fun exerciseLockout(): Boolean
    fun sportTime(): Boolean
    fun sleepTime(): Boolean
    fun trajectoryRelevance(): Double
    fun maxSmbEffective(): Double
    fun barrierPermittedU(): Double?
    fun insulinActivity(): InsulinActionState?
    fun loadGovernorMultiplierG(): Double
    fun safetyTerminals(): SafetyPredictionTerminals?
    fun riskEnvelope(): AimiRiskEnvelope?
    fun contextActivityActive(): Boolean
    fun trajBridgePending(): Boolean
    fun fusedMultipliers(): PhysioMultipliersMTR?
    fun wCycleBelief(): WCycleBelief?
    fun patientMode(): PatientModeOrchestrator.Decision?
    fun contextIntentCount(): Int
}

/** `authoritativeMinPredBg` / `authoritativeEventualBg`. */
internal interface AimiRbtResolveDoseBg {
    fun authoritativeMinPredBg(rT: RT, rawMinPred: Double?): Double?
    fun authoritativeEventualBg(fallback: Double): Double
}

/** `dwellAboveHighBgMinutes`. */
internal fun interface AimiRbtDwell {
    fun minutes(): Int
}

/** `trajectoryGuard.getLastAnalysis`. */
internal fun interface AimiRbtTrajectory {
    fun lastAnalysis(): TrajectoryAnalysis?
}

/** `ensureWCycleInfo`. */
internal fun interface AimiRbtWCycleEnsure {
    fun ensure()
}

/** `buildRbtExtendedSignals`. */
internal fun interface AimiRbtExtendedBuild {
    fun build(
        rT: RT,
        profile: OapsProfileAimi,
        htr: HyperTrajectoryReleaseResult,
        v3SmbU: Double,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        mpcFeedForwardRa: Double?,
        cbfShieldDeltaU: Double?,
    ): RbtExtendedSignals
}

/** `physioAdapter` pour le pattern et le contexte de croyance. */
internal interface AimiRbtResolvePhysio {
    fun getEffectiveContext(): PhysioContextMTR
    fun getLatestSnapshot(): HealthContextSnapshot
}

/** `contextManager.getSnapshot`. Une [Exception] est journalisée, le repli reste null. */
internal fun interface AimiRbtContextSnapshot {
    fun snapshot(nowMs: Long): ContextSnapshot?
}

/** Écritures faites par la référence avant la mise à jour latente. */
internal interface AimiRbtResolveWrites {
    fun setStacking(evaluation: InsulinStackingStance.Evaluation)
    fun setPattern(snapshot: PhysiologicalPatternSnapshot)
    fun setContext(snapshot: ContextSnapshot?)
}

/** `updatePhysioLatentState`, avec le snapshot de pattern de ce resolve. */
internal fun interface AimiRbtResolveLatent {
    fun update(
        snapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
        patternSnapshot: PhysiologicalPatternSnapshot,
    )
}

/** `mealAbsorptionDeltaPrevOfTick`. */
internal fun interface AimiRbtDeltaPrev {
    fun previous(): Double?
}

/** `dateUtil.now`. */
internal fun interface AimiRbtResolveClock {
    fun nowMs(): Long
}

/**
 * `runRecursiveBeliefResolve`.
 *
 * Le repli null de `contextManager.getSnapshot` est inchangé. L'échec n'est plus avalé :
 * [readRbtOptional] écrit une ligne `RBT contextSnapshot failed …` puis rend null.
 */
internal fun decideRecursiveBeliefResolve(
    v3SmbU: Double,
    htr: HyperTrajectoryReleaseResult,
    rT: RT,
    combinedDelta: Float,
    tdd24hU: Double,
    profile: OapsProfileAimi,
    autosens: AutosensResult,
    glucoseStatus: GlucoseStatusAIMI?,
    stepsLast15m: Int,
    heartRateBpm: Int,
    autodriveGateOpen: Boolean,
    mpcFeedForwardRa: Double?,
    cbfShieldDeltaU: Double?,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    fields: AimiRbtResolveFields,
    htrTerminals: AimiHtrTerminals,
    doseBg: AimiRbtResolveDoseBg,
    dwell: AimiRbtDwell,
    trajectory: AimiRbtTrajectory,
    recentGlucose: AimiRecentGlucose,
    uamConfidence: AimiUamConfidence,
    wCycle: AimiRbtWCycleEnsure,
    extendedBuild: AimiRbtExtendedBuild,
    physio: AimiRbtResolvePhysio,
    contextSnapshot: AimiRbtContextSnapshot,
    writes: AimiRbtResolveWrites,
    latent: AimiRbtResolveLatent,
    deltaPrev: AimiRbtDeltaPrev,
    clock: AimiRbtResolveClock,
): RecursiveBeliefSnapshot? {
    val rbtPrefs = RecursiveBeliefPreferences.from(preferences)
    if (!RecursiveBeliefPreferences.isActive(rbtPrefs)) return null
    val scenario = fields.scenario() ?: return null
    val curves = fields.curves() ?: return null
    val (floorTerminal, bestTerminal) = htrTerminals.resolveHtrScenarioTerminals(rT)
    val htrClass = HyperSeverityClassifier.classify(
        HyperSeverityClassifier.Input(
            bgMgdl = fields.bg(),
            targetBgMgdl = fields.targetBg(),
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = fields.delta(),
            shortAvgDeltaMgdlPer5 = fields.shortAvgDelta(),
            combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
            floorTerminalMgdl = floorTerminal,
            bestTerminalMgdl = bestTerminal,
            tdd24hU = tdd24hU,
            dwellAboveHighBgMinutes = dwell.minutes(),
            trajectoryType = trajectory.lastAnalysis()?.classification,
            establishedDevOverrideMgdl = preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl),
            deepDevOverrideMgdl = preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl),
            mealAbsorptionPhase = fields.mealAbsorption()?.phase ?: MealAbsorptionPhase.NONE,
            gapPrevMgdl = MealAbsorptionMemory.lastGapMgdl,
        ),
    )
    val bgHistory = recentGlucose.getRecentGlucose().map { it.toDouble() }
    val bgDerivShort = if (bgHistory.size >= 3) {
        bgHistory.takeLast(3).let { it.last() - it.first() } / 2.0
    } else {
        null
    }
    val rawMinPred = minPredictedAcrossCurves(rT.predBGs)
    val hypoIgnored = htr.hypoMinPredIgnored
    val previewMinPred = doseBg.authoritativeMinPredBg(rT, rawMinPred)
    val minPredForStacking = if (hypoIgnored && previewMinPred != null) {
        val dev = fields.bg() - fields.targetBg()
        max(previewMinPred, fields.bg() - HyperTrajectoryHypoCredibility.hypoCredibilityDropMgdl(dev))
    } else {
        previewMinPred
    }
    val endogenousCounterRegulatory =
        fields.phaseOutput()?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
    val stackingEval = InsulinStackingStance.evaluate(
        bg = fields.bg(),
        delta = fields.delta(),
        shortAvgDelta = fields.shortAvgDelta(),
        targetBg = fields.targetBg(),
        iob = fields.iob(),
        maxIob = fields.maxIob(),
        eventualBg = doseBg.authoritativeEventualBg(fields.eventualBg()).takeIf { it.isFinite() },
        minPredBg = minPredForStacking,
        trajectoryEnergy = rT.trajectoryEnergy,
        isExplicitUserAction = false,
        enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
        mealPriorityContext =
            fields.mealAbsorption()?.mealDeliveryPriority == true &&
                fields.uamHypothesis()?.suppressMealInterpretation != true,
        endogenousCounterRegulatory = endogenousCounterRegulatory,
        mealAbsorptionPhase = fields.mealAbsorption()?.phase ?: MealAbsorptionPhase.NONE,
        mealModeActive = fields.mealModeActive(),
    )
    writes.setStacking(stackingEval)
    wCycle.ensure()
    val extended = extendedBuild.build(
        rT = rT,
        profile = profile,
        htr = htr,
        v3SmbU = v3SmbU,
        autosens = autosens,
        glucoseStatus = glucoseStatus,
        mpcFeedForwardRa = mpcFeedForwardRa,
        cbfShieldDeltaU = cbfShieldDeltaU,
    )
    val physioContext = physio.getEffectiveContext()
    val wearableSnap = physio.getLatestSnapshot()
    val contextSnap = if (!preferences.get(BooleanKey.OApsAIMIContextEnabled)) {
        null
    } else {
        readRbtOptional(
            source = "contextSnapshot",
            consoleLog = consoleLog,
        ) { contextSnapshot.snapshot(clock.nowMs()) }.valueOrNull()
    }
    val patternInput = PhysiologicalPatternInputBuilder.build(
        bgMgdl = fields.bg(),
        targetBgMgdl = fields.targetBg(),
        highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
        deltaMgdlPer5 = fields.delta(),
        shortAvgDeltaMgdlPer5 = fields.shortAvgDelta(),
        combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
        mealCobG = rT.COB?.toDouble() ?: 0.0,
        hourOfDay = fields.hourOfDay(),
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = wearableSnap.rhrResting,
        iobU = fields.iob(),
        maxIobU = fields.maxIob(),
        bestTerminalMgdl = bestTerminal,
        floorTerminalMgdl = floorTerminal,
        phaseOutput = fields.phaseOutput(),
        physioContext = physioContext,
        sleepDebtMinutes = wearableSnap.sleepDebtMinutes,
        sleepEfficiency = wearableSnap.sleepEfficiency,
        mealAbsorption = fields.mealAbsorption(),
        stackingEval = stackingEval,
        endogenousCounterRegulatory = endogenousCounterRegulatory,
        postHypoOrdinal = extended.postHypoOrdinal,
        exerciseLockout = fields.exerciseLockout(),
        sportTime = fields.sportTime(),
        sleepTime = fields.sleepTime(),
        contextSnapshot = contextSnap,
        compressionImpossibleRise = extended.compressionImpossibleRise,
        dwellAboveHighBgMinutes = dwell.minutes(),
        trajectoryRelevanceScore = fields.trajectoryRelevance(),
        nowMs = clock.nowMs(),
        // The catalogue reduces the user's own ceiling instead of imposing absolute units.
        //
        // ⚠️ The **configured** ceiling, not `this.maxSMBHB`. That field is reassigned during the
        // tick and ramps with glucose (0.88 → 1.12 → 1.36 → 1.60 on the dinner of 2026-08-10), so
        // multiplying a fraction by it applies the reduction twice: the cap fell to 0.66 U at
        // BG 111 where it should have been 1.20 — 45 % tighter at the start of a meal, exactly
        // when the prebolus matters. The catalogue reduces a *setting*, and the tick's own
        // maxSMB/maxSMBHB selection still bounds the dose afterwards.
        maxSmbHbU = maxOf(
            preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB),
            preferences.get(DoubleKey.OApsAIMIMaxSMB),
        ),
    )
    val patternSnapshot = PhysiologicalPatternDetector.detect(patternInput)
    writes.setPattern(patternSnapshot)
    writes.setContext(contextSnap)
    latent.update(
        snapshot = wearableSnap,
        sourceSensor = glucoseStatus?.sourceSensor,
        patternSnapshot = patternSnapshot,
    )
    patternSnapshot.dominant?.let { dominant ->
        consoleLog.add(
            "🧬 PATTERN: dominant=$dominant conf=${aimiFmt2(patternSnapshot.dominantConfidence)} " +
                "cap=${patternSnapshot.smbCapU?.let { aimiFmt2(it) + "U" } ?: "none"}" +
                "${patternSnapshot.smbCapKind?.let { "/$it" } ?: ""} " +
                "mealSupp=${patternSnapshot.suppressMealInterpretation} " +
                "hyperSupp=${patternSnapshot.suppressHyperRelease} | ${patternSnapshot.reasonSummary}",
        )
    }
    fields.patientMode()?.takeIf {
        it.mode != PatientMode.STABLE_BASELINE ||
            it.confidence >= 0.60 ||
            fields.contextIntentCount() > 0
    }?.let { modeDecision ->
        consoleLog.add("🫀 PATIENT_MODE: ${modeDecision.summary()}")
    }
    val ctx = RecursiveBeliefTickContext(
        bgMgdl = fields.bg(),
        targetBgMgdl = fields.targetBg(),
        highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
        deltaMgdlPer5 = fields.delta(),
        shortAvgDeltaMgdlPer5 = fields.shortAvgDelta(),
        combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
        iobU = fields.iob(),
        maxIobU = fields.maxIob(),
        maxSmbEffectiveU = fields.maxSmbEffective(),
        barrierPermittedU = fields.barrierPermittedU(),
        tdd24hU = tdd24hU,
        patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
        deltaPrevMgdlPer5 = deltaPrev.previous(),
        eventualBgMgdl = fields.eventualBg().takeIf { it.isFinite() },
        insulinActivityNow = fields.insulinActivity()?.activityNow,
        lastLoadGovernorMultiplierG = fields.loadGovernorMultiplierG(),
        curves = curves,
        scenario = scenario,
        mealAbsorption = fields.mealAbsorption(),
        physioPhase = fields.phaseOutput(),
        behavioralRisk = fields.phaseOutput()?.policy,
        physioContext = physioContext,
        physiologicalPatterns = patternSnapshot,
        trajectoryAnalysis = trajectory.lastAnalysis(),
        trajectoryRelevanceScore = fields.trajectoryRelevance(),
        safetyTerminals = fields.safetyTerminals(),
        riskEnvelope = fields.riskEnvelope(),
        stackingStance = stackingEval,
        uamConfidence = uamConfidence.confidenceOrZero(),
        contextSmbFactor = rT.contextModulation.takeIf { rT.contextEnabled }?.toFloat() ?: 1.0f,
        contextActivityActive = fields.contextActivityActive(),
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        isNight = fields.hourOfDay() >= 23 || fields.hourOfDay() < 6,
        exerciseLockout = fields.exerciseLockout(),
        asleepLiveConfidence = extended.sleepLiveConfidence ?: 0.0,
        hypoMinPredIgnored = hypoIgnored,
        minPredictedBgMgdl = rawMinPred,
        dwellAboveHighBgMinutes = dwell.minutes(),
        trajBridgePending = fields.trajBridgePending(),
        tubeAdvisorCapScale = extended.tubeAdvisorCapScale,
        v3SmbU = v3SmbU,
        htrResult = htr,
        htrClassification = htrClass,
        tier1Hypo = fields.bg() < (profile.lgsThreshold ?: 70),
        bgHistoryMgdl = bgHistory,
        physioMultipliers = fields.fusedMultipliers(),
        wCycleBasalMult = EndocrineAmplitudeGovernor.productionAmp(fields.wCycleBelief(), EndocrineAmpAxis.BASAL)
            .takeIf { it != 1.0 },
        wCycleSmbMult = EndocrineAmplitudeGovernor.productionAmp(fields.wCycleBelief(), EndocrineAmpAxis.SMB)
            .takeIf { it != 1.0 },
        wCycleHypoLoad = fields.wCycleBelief()?.hypoLoad,
        bgDerivShort = bgDerivShort,
        insulinActivityStageOrdinal = extended.insulinActivityStageOrdinal,
        autodriveV3GateOpen = autodriveGateOpen,
        endogenousCounterRegulatory = endogenousCounterRegulatory,
        pkpdTailDamping = extended.pkpdTailDamping,
        correctionAggressionLevel = extended.correctionAggressionLevel,
        ngrSmbMult = extended.ngrSmbMult,
        shadowAuditorConfidence = extended.shadowAuditorConfidence,
        shadowOrchestratorActive = extended.shadowOrchestratorActive,
        tuningContextLabel = extended.tuningContextLabel,
        replaceHtrRelease = rbtPrefs.authorityEnabled,
        extended = extended,
    )
    val scales = RecursiveBeliefEngine.build(
        ctx = ctx,
        nowMs = clock.nowMs(),
        waveletEnabled = rbtPrefs.waveletEnabled,
        includeShadowLeaves = rbtPrefs.shadowEnabled,
    )
    return RecursiveBeliefResolver.resolve(
        RecursiveBeliefResolver.Input(
            ctx = ctx,
            scales = scales,
            authorityEnabled = rbtPrefs.authorityEnabled,
        ),
    )
}
