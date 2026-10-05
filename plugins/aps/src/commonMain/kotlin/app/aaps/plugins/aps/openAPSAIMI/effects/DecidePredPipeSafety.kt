package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioPhaseFusion
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.prediction.PredictionSanityResult
import app.aaps.plugins.aps.openAPSAIMI.prediction.sanitizePredictionValues
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelopeBuilder
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminals
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminalsResolver
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoEvaluator
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoInput
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyStartResolution
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionContext
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionInput
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AimiPredPipePrep(
    val sanity: PredictionSanityResult,
    val minBg: Double,
    val threshold: Double,
    val scenario: ScenarioProjectionPair,
)

/**
 * Android reads and writes of [decideAdvancedPredictionsAndPredPipePrep], each at the line that uses it.
 */
internal interface AimiPredPipeCalls {
    fun nowMs(): Long
    fun setAdvancedCurves(curves: AdvancedPredictionCurves)
    fun recordSoftFloor(curves: AdvancedPredictionCurves)
    fun mealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal): MealSafetyContext
    fun phaseClassifierInput(
        rT: RT,
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        bestTerminalMgdl: Double,
        floorTerminalMgdl: Double,
        mealAbsorptionMemoryActive: Boolean,
    ): PhysiologicalPhaseClassifier.Input
    fun targetBg(): Float
    fun trajectoryAnalysis(): TrajectoryAnalysis?
    fun activityProtection(): Boolean
    fun contextActivityActive(): Boolean
    fun setScenario(scenario: ScenarioProjectionPair)
    fun refreshPhysiologicalPhase(
        rT: RT,
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        basePhysioMultipliers: PhysioMultipliersMTR,
    )
    fun refreshMealAbsorptionPhase(
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        mealContext: MealSafetyContext,
        lastBolusTimeMs: Long?,
        nowMs: Long,
    ): MealAbsorptionPhaseEngine.Output
    fun publishPatientState(glucoseStatus: GlucoseStatusAIMI, nowMs: Long)
    fun setPredictedBg(value: Float)
    fun setEventualBgSnapshot(value: Double)
    fun setPredictionSize(value: Int)
    fun setPredictionAvailable(value: Boolean)
    fun pumpReachable(): Boolean
    fun setEarlyRiskEnvelope(envelope: AimiRiskEnvelope)
}

/**
 * `runAdvancedPredictionsAndPredPipePrep`.
 *
 * Builds the scenario from the advanced curves and publishes the best terminal as the tick's
 * predicted glucose. A low floor is what the following safety halt turns into a reduced basal.
 * Pump reachability used to swallow [Exception] and report false; the same fallback is kept and
 * the failure is written on the decision log.
 */
internal fun decideAdvancedPredictionsAndPredPipePrep(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    bg: Double,
    delta: Float,
    sens: Double,
    predictedBg: Float,
    glucoseStatus: GlucoseStatusAIMI,
    minAgo: Double,
    isExplicitAdvisorRun: Boolean,
    physioMultipliers: PhysioMultipliersMTR,
    iobData: IobTotal,
    stepsLast15m: Int,
    heartRateBpm: Int,
    restingHeartRateBpm: Int,
    combinedDelta: Float,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    consoleError: MutableList<String>,
    calls: AimiPredPipeCalls,
): AimiPredPipePrep {
    val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
    val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
    val isFreshAdvisor = (calls.nowMs() - advisorTime) < 60 * 60_000L
    val effectiveCob = if (ctx.mealData.mealCOB > 0) ctx.mealData.mealCOB
    else if (isFreshAdvisor) advisorCarbs else 0.0
    val curves = AdvancedPredictionEngine.predictCurves(
        currentBG = bg,
        iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
        finalSensitivity = sens,
        cobG = effectiveCob,
        profile = profile,
        delta = delta.toDouble(),
        endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
        hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
        stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
    )
    calls.setAdvancedCurves(curves)
    calls.recordSoftFloor(curves)
    val mealContext = calls.mealSafetyContext(isExplicitAdvisorRun, iobData)
    val floorPreview = bg - 25.0
    val previewBest = PhysioPhaseFusion.previewBestTerminalMgdl(
        bgMgdl = bg,
        deltaMgdlPer5 = delta.toDouble(),
        hybridTerminalMgdl = curves.hybrid.lastOrNull(),
    )
    val preScenarioFused = PhysioPhaseFusion.classifyAndFuse(
        calls.phaseClassifierInput(
            rT = rT,
            combinedDelta = combinedDelta,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            bestTerminalMgdl = previewBest,
            floorTerminalMgdl = floorPreview,
            mealAbsorptionMemoryActive = MealAbsorptionMemory.isActive(calls.nowMs()),
        ),
        physioMultipliers,
    )
    val phasePolicy = preScenarioFused.phaseOutput.policy
    val nowMs = calls.nowMs()
    val mealMemoryActive = MealAbsorptionMemory.isActive(nowMs)
    val mealHighBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
        calls.targetBg().toDouble(),
        preferences.get(DoubleKey.OApsAIMIHighBg),
    )
    val mealBestTFloorAbove = if (mealMemoryActive) {
        min(mealHighBand * 0.35, 55.0)
    } else {
        null
    }
    val scenarioCtx = ScenarioProjectionContext(
        mealContext = mealContext,
        effectiveCobG = effectiveCob,
        targetBgMgdl = calls.targetBg().toDouble(),
        trajectoryAnalysis = calls.trajectoryAnalysis(),
        trajectoryRelevanceScore = preScenarioFused.multipliers.trajectoryRelevanceScore,
        activityProtectionMode = calls.activityProtection(),
        contextActivityActive = calls.contextActivityActive(),
        contextSmbFactor = rT.contextModulation.takeIf { rT.contextEnabled }?.toFloat() ?: 1.0f,
        physioSmbFactor = preScenarioFused.multipliers.smbFactor,
        physioReactivityFactor = preScenarioFused.multipliers.reactivityFactor,
        physioBasalFactor = preScenarioFused.multipliers.basalFactor,
        physiologicalPhase = phasePolicy.phase,
        suppressMealLikeUam = phasePolicy.suppressMealLikeScenario,
        scenarioBestCapAboveBgMgdl = phasePolicy.capScenarioBestAboveBgMgdl,
        mealAbsorptionPhase = MealAbsorptionMemory.lastPhase,
        mealAbsorptionMemoryActive = mealMemoryActive,
        mealAbsorptionBestTFloorAboveBgMgdl = mealBestTFloorAbove,
    )
    val scenario = ScenarioProjectionEngine.build(
        ScenarioProjectionInput(
            bgNowMgdl = bg,
            deltaMgdlPer5 = delta,
            curves = curves,
            context = scenarioCtx,
        ),
    )
    calls.setScenario(scenario)
    calls.refreshPhysiologicalPhase(
        rT = rT,
        combinedDelta = combinedDelta,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = restingHeartRateBpm,
        basePhysioMultipliers = physioMultipliers,
    )
    calls.refreshMealAbsorptionPhase(
        combinedDelta = combinedDelta,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = restingHeartRateBpm,
        mealContext = mealContext,
        lastBolusTimeMs = iobData.lastBolusTime.takeIf { it > 0L },
        nowMs = nowMs,
    )
    calls.publishPatientState(
        glucoseStatus = glucoseStatus,
        nowMs = nowMs,
    )
    val cappedBest = scenario.scenarioBest.terminalMgdl
    calls.setPredictedBg(cappedBest.toFloat())
    calls.setEventualBgSnapshot(cappedBest)
    calls.setPredictionSize(scenario.scenarioBest.pointsMgdl.size)
    calls.setPredictionAvailable(scenario.scenarioBest.pointsMgdl.isNotEmpty())
    consoleLog.add(scenario.formatLogLine())
    consoleError.add(
        "🔮 SCENARIO: floor=${scenario.clinicalFloor.terminalMgdl.toInt()} best=${scenario.scenarioBest.terminalMgdl.toInt()} " +
            "Δ=${(scenario.scenarioBest.terminalMgdl - scenario.clinicalFloor.terminalMgdl).toInt()}",
    )

    fun safePredPipeValue(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
    val sanity = sanitizePredictionValues(
        bg = bg,
        delta = delta,
        predBgRaw = scenario.scenarioBest.terminalMgdl,
        eventualBgRaw = scenario.scenarioBest.terminalMgdl,
        series = rT.predBGs,
        log = consoleLog,
    )
    val floorComposite = minOf(
        safePredPipeValue(bg),
        safePredPipeValue(scenario.clinicalFloor.pathMinMgdl),
        safePredPipeValue(scenario.clinicalFloor.terminalMgdl),
    )
    val minBg = minOf(
        safePredPipeValue(bg),
        safePredPipeValue(sanity.predBg),
        safePredPipeValue(sanity.eventualBg),
    )
    val threshold = HypoThresholdMath.computeHypoThreshold(floorComposite, profile.lgsThreshold)
    val pumpReachable = try {
        calls.pumpReachable()
    } catch (e: Exception) {
        consoleLog.add(
            "PRED_PIPE pump reachability failed (${e::class.simpleName}): ${e.message.orEmpty()} — value false",
        )
        false
    }
    consoleLog.add(
        "PRED_PIPE: bg=${bg.roundToInt()} delta=${aimiFmt1(delta)} bestT=${sanity.predBg.roundToInt()} " +
            "floorT=${scenario.clinicalFloor.terminalMgdl.toInt()} floorMin=${scenario.clinicalFloor.pathMinMgdl.toInt()} " +
            "min=${minBg.roundToInt()} th=${threshold.toInt()} " +
            "noise=${glucoseStatus.noise} dataAge=${minAgo}m pumpReachable=$pumpReachable sanity=${sanity.label}",
    )
    val early = AimiRiskEnvelopeBuilder.buildEarly(
        bg = bg,
        delta = delta,
        predTerminal = scenario.clinicalFloor.terminalMgdl,
        eventualTerminal = scenario.scenarioBest.terminalMgdl,
        predBGs = rT.predBGs,
        lgsThreshold = profile.lgsThreshold,
    )
    calls.setEarlyRiskEnvelope(early)
    consoleLog.add(AimiRiskEnvelopeBuilder.formatLogLine(early))
    return AimiPredPipePrep(sanity, minBg, threshold, scenario)
}

internal sealed class AimiSafetyHalt {
    data object Continue : AimiSafetyHalt()
    data class Halt(val rT: RT) : AimiSafetyHalt()
}

/**
 * Android reads and writes of [decidePredPipelineSafetyHalt], each at the line that uses it.
 */
internal interface AimiSafetyHaltCalls {
    fun mealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal): MealSafetyContext
    fun projectionInput(
        targetBgValue: Double,
        cobValue: Double,
        combinedDeltaValue: Float,
    ): CorrectionAggressionGate.Input
    fun cob(): Float
    fun mealAbsorptionPhase(): MealAbsorptionPhase
    fun mealCertainty(): MealCertainty?
    fun setSafetyTerminals(terminals: SafetyPredictionTerminals)
    fun trySafetyStart(
        bg: Double,
        delta: Float,
        profile: OapsProfileAimi,
        iob: IobTotal,
        noise: Int,
        predBg: Double,
        eventualBg: Double,
        mealContext: MealSafetyContext,
    ): SafetyStartResolution
    fun setSafetyRiskExport(snapshot: SafetyRiskExportSnapshot)
    fun adaptiveMult(): Double
    fun requestTempBasal(
        rate: Double,
        durationMin: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currentTemp: CurrentTemp,
        overrideSafetyLimits: Boolean,
        adaptiveMultiplier: Double,
        allowPartialSafetyTbr: Boolean,
        mealContext: MealSafetyContext,
    )
    fun setDecisionSource(source: String)
    fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float)
    fun markFinalLoop(rT: RT, currentTemp: CurrentTemp)
}

/**
 * `runPredPipelineSafetyHaltOrReturn`.
 *
 * A predicted glucose under the LGS threshold, while the current glucose is still above it,
 * requests a quarter of the profile basal. This function has no swallowed [Exception].
 * The safety-start port still owns its own log lines.
 */
internal fun decidePredPipelineSafetyHalt(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    bg: Double,
    delta: Float,
    combinedDelta: Float,
    iobData: IobTotal,
    glucoseStatus: GlucoseStatusAIMI,
    scenario: ScenarioProjectionPair,
    isExplicitAdvisorRun: Boolean,
    consoleLog: MutableList<String>,
    calls: AimiSafetyHaltCalls,
): AimiSafetyHalt {
    val mealContext = calls.mealSafetyContext(isExplicitAdvisorRun, iobData)
    val projectionInput = calls.projectionInput(
        targetBgValue = profile.target_bg.toDouble(),
        cobValue = calls.cob().toDouble(),
        combinedDeltaValue = combinedDelta,
    )
    val safetyTerminals = SafetyPredictionTerminalsResolver.resolveFromScenario(
        bg = bg,
        delta = delta,
        mealContext = mealContext,
        projection = scenario,
        mealAbsorptionPhase = calls.mealAbsorptionPhase(),
        targetBgMgdl = projectionInput.targetBg,
        minBgLookback75m = projectionInput.minBgLookback75m,
        hasIndependentMealEvidence = CorrectionAggressionGate.hasIndependentMealEvidence(projectionInput),
        cobG = calls.cob().toDouble(),
        mealCertainty = calls.mealCertainty(),
    )
    calls.setSafetyTerminals(safetyTerminals)
    val lgsTh = HypoThresholdMath.computeHypoThreshold(
        safetyTerminals.compositeMinMgdl,
        profile.lgsThreshold,
    )
    val suppression = PredictiveHypoEvaluator.evaluateSuppression(
        PredictiveHypoInput(
            bgNow = bg,
            predicted = safetyTerminals.predBg,
            eventual = safetyTerminals.eventualBg,
            hypoThreshold = lgsTh,
            delta = delta.toDouble(),
            mealContext = mealContext,
        ),
    )
    consoleLog.add(
        "RISK_SAFETY_EARLY: compositeMin=${safetyTerminals.compositeMinMgdl.toInt()} " +
            "predT=${safetyTerminals.predBg.toInt()} evT=${safetyTerminals.eventualBg.toInt()} " +
            "bestT=${scenario.scenarioBest.terminalMgdl.toInt()} floorT=${scenario.clinicalFloor.terminalMgdl.toInt()} " +
            "mealRise=${safetyTerminals.mealRiseConfirmed} suppressed=${suppression.suppressed}",
    )
    val resolution = calls.trySafetyStart(
        bg = bg,
        delta = delta,
        profile = profile,
        iob = iobData,
        noise = glucoseStatus.noise.toInt(),
        predBg = safetyTerminals.predBg,
        eventualBg = safetyTerminals.eventualBg,
        mealContext = mealContext,
    )
    calls.setSafetyRiskExport(
        SafetyRiskExportSnapshot(
            predictiveHypoSuppressed = suppression.suppressed,
            safetyGate = resolution.lastSafetySource,
            haltRemainingPipeline = resolution.haltRemainingPipeline,
            mealContextActive = mealContext.hasMealIntent,
            mealRiseConfirmed = safetyTerminals.mealRiseConfirmed,
            compositeMinMgdl = safetyTerminals.compositeMinMgdl,
            predBgMgdl = safetyTerminals.predBg,
            eventualBgMgdl = safetyTerminals.eventualBg,
            uamTerminalMgdl = safetyTerminals.uamTerminalMgdl,
            hypoThresholdMgdl = lgsTh,
        ),
    )
    if (resolution.decision !is DecisionResult.Applied) return AimiSafetyHalt.Continue
    val safetyRes = resolution.decision
    consoleLog.add("SAFETY_APPLIED_TBR intent=${safetyRes.tbrUph} haltPipeline=${resolution.haltRemainingPipeline}")
    if (safetyRes.tbrUph != null) {
        calls.requestTempBasal(
            rate = safetyRes.tbrUph,
            durationMin = safetyRes.tbrMin ?: 30,
            profile = profile,
            rT = rT,
            currentTemp = ctx.currentTemp,
            overrideSafetyLimits = true,
            adaptiveMultiplier = calls.adaptiveMult(),
            allowPartialSafetyTbr = !resolution.haltRemainingPipeline,
            mealContext = mealContext,
        )
    }
    if (resolution.haltRemainingPipeline) {
        rT.insulinReq = 0.0
        rT.reason.append(" | ⚠ Safety Halt: ${safetyRes.reason}")
        calls.setDecisionSource(safetyRes.source)
        calls.logDecisionFinal("SAFETY", rT, bg, delta)
        calls.markFinalLoop(rT, ctx.currentTemp)
        return AimiSafetyHalt.Halt(rT)
    }
    rT.reason.append(" | ⚠ Safety TBR: ${safetyRes.reason}")
    calls.setDecisionSource(safetyRes.source)
    return AimiSafetyHalt.Continue
}
