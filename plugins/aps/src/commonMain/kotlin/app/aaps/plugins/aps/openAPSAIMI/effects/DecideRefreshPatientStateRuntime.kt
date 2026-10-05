package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.SourceSensor
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.PhysiologicalStressMaskBuilder
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.patient.BodyKineticsDigest
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEnvironment
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSensorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemoryCalculator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientRefreshSource
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateLoopCache
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmplitudeGovernor
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineApplicationMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleInfo
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences

/**
 * Android `refreshPatientStateRuntime` behind an already-read snapshot.
 * The caller catches a snapshot failure and passes an empty snapshot. This function does not catch.
 */
internal interface PatientRuntimeCalls {
    fun enrichThermal(snapshot: HealthContextSnapshot): HealthContextSnapshot
    fun eventMemory(
        currentBgMgdl: Double,
        latentState: PhysioLatentState?,
        thermalBelief: ThermalBeliefDigest?,
        nowMs: Long,
    ): PatientEventMemory
    fun phase(): PhysiologicalPhaseClassifier.Output?
    fun mealAbsorption(): MealAbsorptionPhaseEngine.Output?
    fun pattern(): PhysiologicalPatternSnapshot?
    fun latent(): PhysioLatentState?
    fun hypothesis(): UamHypothesisState?
    fun ensureWCycle(): WCycleInfo?
    fun wCyclePreferences(): WCyclePreferences
    fun hourOfDay(): Int
    fun hypoGuardActive(): Boolean
    fun effectiveDiaHours(): Double?
    fun effectivePeakMinutes(): Double?
    fun insulinAction(): InsulinActionState?
    fun effortAssessment(): EffortActivityBelief.Assessment?
    fun cgmNoise(): Double
    fun sensorInsertionMs(nowMs: Long): Long?
    fun scenarioBest(): ScenarioProjectionCurve?
    fun cachedEventualTerminal(): Double?
    fun eventualBg(): Double
    fun authoritativeEventual(fallback: Double): Double
    fun bg(): Double
    fun delta(): Double
    fun targetBg(): Double
    fun cob(): Double
    fun shortAvgDelta(): Double
    fun effortVeto(): Boolean
    fun effortLive(): Boolean
    fun basalUph(): Float
    fun autodriveMaxBasal(): Double
    fun maxSmb(): Double
    fun maxSmbHb(): Double
    fun maxIob(): Double
    fun iob(): Double
    fun chaosScore(): Double
    fun priorRuntimeBlocker(): String?
    fun priorBlockedStreak(): Int
    fun aggression(): CorrectionAggressionGate.Decision?
    fun inflammation(): InflammationAdjuster.InflammationResult?
    fun physioContext(): PhysioContextMTR?
    fun physioTrace(): PhysioDecisionTraceMTR?
    fun uamConfidence(): Double
    fun storedSourceSensor(): SourceSensor?
    fun log(line: String)
}

internal data class PatientRuntimeResult(
    val patientState: PatientStateSnapshot,
    val patientModeDecision: PatientModeOrchestrator.Decision,
    val wCycleBelief: WCycleBelief?,
    val physiologicalTree: PhysiologicalTreeSnapshot?,
    val mealCertainty: MealCertainty?,
    val harmoniaDecision: HarmoniaDecision?,
)

internal fun decideRefreshPatientStateRuntime(
    nowMs: Long,
    contextSnapshot: ContextSnapshot?,
    healthSnapshot: HealthContextSnapshot,
    sourceSensor: SourceSensor?,
    refreshSource: PatientRefreshSource,
    calls: PatientRuntimeCalls,
): PatientRuntimeResult {
    val wearableSnap = healthSnapshot
    val enrichedSnap = calls.enrichThermal(wearableSnap)
    val eventMemory = calls.eventMemory(
        currentBgMgdl = calls.bg(),
        latentState = calls.latent(),
        thermalBelief = enrichedSnap.thermalBelief,
        nowMs = nowMs,
    )
    val patientState = PatientStateEngine.build(
        timestampMs = nowMs,
        phaseOutput = calls.phase(),
        mealAbsorptionOutput = calls.mealAbsorption(),
        patternSnapshot = calls.pattern(),
        latentState = calls.latent(),
        hypothesisState = calls.hypothesis(),
        contextSnapshot = contextSnapshot,
        thermalBelief = enrichedSnap.thermalBelief,
        eventMemory = eventMemory,
    )
    val patientModeDecision = PatientModeOrchestrator.evaluate(patientState)
    val physioLive = PhysioLiveDigest.from(enrichedSnap, nowMs)
    val cycleInfo = calls.ensureWCycle()
    val hypoGuardActive = calls.hypoGuardActive()
    val wCycleBelief = if (cycleInfo == null || !cycleInfo.enabled) {
        WCycleBelief.DISABLED
    } else {
        EndocrineAmplitudeGovernor.from(
            info = cycleInfo,
            prefs = calls.wCyclePreferences(),
            hypoLoad = eventMemory.recentHypoLoad,
            hypoGuardActive = hypoGuardActive,
            hourOfDay = calls.hourOfDay(),
        )
    }
    val bodyKinetics = BodyKineticsDigest.fromTick(
        effectiveDiaHours = calls.effectiveDiaHours(),
        effectivePeakMinutes = calls.effectivePeakMinutes(),
        insulinActionState = calls.insulinAction(),
    )
    val effort = calls.effortAssessment()
    var physiologicalTree = PhysiologicalTreeBuilder.build(
        enabled = true,
        patientState = patientState,
        patientModeDecision = patientModeDecision,
        physioLive = physioLive,
        thermalBelief = enrichedSnap.thermalBelief,
        timestampMs = nowMs,
        currentBgMgdl = calls.bg(),
        deltaMgdl5m = calls.delta(),
        effortActiveConfidence = effort
            ?.takeIf { it.state == EffortActivityBelief.State.ACTIVE }?.confidence ?: 0.0,
        effortRecentConfidence = effort
            ?.takeIf { it.state == EffortActivityBelief.State.RECENT_EFFORT }?.confidence ?: 0.0,
        wCycleBelief = wCycleBelief,
        bodyKinetics = bodyKinetics,
    )
    val sensorTelemetry = HarmoniaSensorTelemetry.resolve(
        cgmNoiseRaw = calls.cgmNoise(),
        sensorInsertionMs = calls.sensorInsertionMs(nowMs),
        nowMs = nowMs,
    )
    val scenarioBestForMeal = calls.scenarioBest()
    val pkpdForMeal = calls.cachedEventualTerminal()?.takeIf { it.isFinite() }
        ?: calls.authoritativeEventual(calls.eventualBg()).takeIf { it.isFinite() && it > 1.0 }
    val mealAbsorption = calls.mealAbsorption()
    val mealCertainty = physiologicalTree?.let { tree ->
        MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = tree.trunk.globalState,
                mealBranchConfidence = tree.branches.meal.confidence,
                digestionDetected = tree.branches.digestion.detected,
                absorptionPhase = mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
                bgMgdl = calls.bg(),
                deltaMgdl5m = calls.delta(),
                targetBgMgdl = calls.targetBg(),
                cobG = calls.cob(),
                mealRiseConfirmedLegacy = false,
                effortVeto = calls.effortVeto(),
                shortAvgDeltaMgdl5m = calls.shortAvgDelta(),
                effortLive = calls.effortLive(),
                softCorroboration = MealCertaintyBuilder.softCorroborationFromPhysio(physioLive),
                pkpdEventualMgdl = pkpdForMeal,
                scenarioTerminalMgdl = scenarioBestForMeal?.terminalMgdl,
                scenarioPathMinMgdl = scenarioBestForMeal?.gatePathMinMgdl,
                scenarioPathMinHitFloor = scenarioBestForMeal?.gatePathMinHitFloor == true,
            ),
        )
    }
    val treeBeforeMealCertainty = physiologicalTree
    if (treeBeforeMealCertainty != null && mealCertainty?.supportsMealOverProtective == true) {
        val revisedTree = PhysiologicalTreeBuilder.withMealCertainty(
            snapshot = treeBeforeMealCertainty,
            mealOverridesProtective = true,
            deltaMgdl5m = calls.delta(),
            currentBgMgdl = calls.bg(),
        )
        if (revisedTree.insulinIntent != treeBeforeMealCertainty.insulinIntent) {
            calls.log(
                "🌳 TREE_INTENT: ${treeBeforeMealCertainty.insulinIntent} → ${revisedTree.insulinIntent} " +
                    "(meal certainty HIGH outranks activity veto)",
            )
        }
        physiologicalTree = revisedTree
    }
    val harmoniaMealRiseConfirmed = mealCertainty?.supportsMealSupport == true
    val harmoniaEnvironment = physiologicalTree?.let {
        val currentBasalForSimulation = calls.basalUph().toDouble().takeIf { basal -> basal.isFinite() && basal > 0.0 } ?: 1.0
        val requestedMax = calls.autodriveMaxBasal()
        val maxBasalForSimulation = requestedMax
            .takeIf { maxBasal -> maxBasal.isFinite() && maxBasal > 0.1 }
            ?: maxOf(currentBasalForSimulation * 3.0, currentBasalForSimulation + 2.0, 3.0)
        HarmoniaDecisionEnvironment(
            currentBgMgdl = calls.bg(),
            deltaMgdl5m = calls.delta(),
            iobU = calls.iob(),
            cobG = calls.cob(),
            currentBasalUph = currentBasalForSimulation,
            maxBasalUph = maxBasalForSimulation,
            maxSmbU = maxOf(calls.maxSmb(), calls.maxSmbHb()),
            maxIobU = calls.maxIob(),
            sensorAgeMin = sensorTelemetry.sensorAgeMin,
            sensorNoise = sensorTelemetry.sensorNoise,
            mealRiseConfirmed = harmoniaMealRiseConfirmed,
            targetBgMgdl = calls.targetBg(),
            correctionFragilityScore = eventMemory.correctionFragilityScore,
            postHyperExhaustionScore = eventMemory.postHyperExhaustionScore,
            chaoticEpisodeLoad = calls.chaosScore(),
            effectiveDiaHours = calls.effectiveDiaHours(),
            effectivePeakMinutes = calls.effectivePeakMinutes(),
            bodyKinetics = bodyKinetics,
            endocrineBasalAmp = wCycleBelief
                ?.takeIf {
                    it.enabled && it.applicationMode == EndocrineApplicationMode.APPLIED
                }
                ?.effectiveBasalAmp,
            priorRuntimeBlocker = calls.priorRuntimeBlocker(),
            priorBlockedStreak = calls.priorBlockedStreak(),
        )
    }
    val harmoniaDecision = HarmoniaDecisionEngine.evaluate(
        tree = physiologicalTree,
        environment = harmoniaEnvironment,
        timestampMs = nowMs,
        mealCertainty = mealCertainty,
    )
    if (refreshSource == PatientRefreshSource.LOOP_TICK) {
        physiologicalTree?.let { tree ->
            calls.log(
                "TREE_DEPLOYED trunk=${tree.trunk.globalState.name} " +
                    "conf=${aimiFmt2(tree.trunk.confidence)} " +
                    "risk=${tree.trunk.riskLevel.name} " +
                    "kinetics=${bodyKinetics.reason}",
            )
            calls.log(tree.compactSummary)
        }
        mealCertainty?.let { mc ->
            calls.log(
                "MEAL_CERTAINTY level=${mc.level.name} tree=${mc.treeState.name} " +
                    "rise=${mc.riseGeometry.name} terminals=${mc.terminalsAgree.name} " +
                    "effortVeto=${mc.effortVeto}",
            )
        }
        harmoniaDecision?.let { decision ->
            calls.log(decision.compactSummary)
            if (!decision.decisionBasis.actionCoherentWithTrunk) {
                calls.log(
                    "HARMONIA_BRANCH_MISMATCH action=${decision.action.name} " +
                        "trunk=${decision.decisionBasis.trunkState.name} " +
                        "reason=${decision.decisionBasis.mismatchReason} " +
                        "primary=${decision.decisionBasis.primaryReason}",
                )
            }
        }
    }
    val publishedSource = sourceSensor ?: calls.storedSourceSensor()
    val loopCache = PatientStateLoopCache(
        phaseOutput = calls.phase(),
        mealAbsorptionOutput = calls.mealAbsorption(),
        patternSnapshot = calls.pattern(),
        contextSnapshot = contextSnapshot,
        sourceSensor = publishedSource,
        correctionAggressionDecision = calls.aggression(),
        chronicInflammation = calls.inflammation(),
        physioContext = calls.physioContext(),
        physioTrace = calls.physioTrace(),
        hypothesisState = calls.hypothesis(),
        uamConfidence = calls.uamConfidence(),
    )
    PatientStateRuntimeRepository.publish(
        patientState = patientState,
        patientModeDecision = patientModeDecision,
        updatedAtMs = nowMs,
        physioLive = physioLive,
        thermalBelief = enrichedSnap.thermalBelief,
        physiologicalTree = physiologicalTree,
        harmoniaDecision = harmoniaDecision,
        loopCache = loopCache,
        refreshSource = refreshSource,
    )
    return PatientRuntimeResult(
        patientState = patientState,
        patientModeDecision = patientModeDecision,
        wCycleBelief = wCycleBelief,
        physiologicalTree = physiologicalTree,
        mealCertainty = mealCertainty,
        harmoniaDecision = harmoniaDecision,
    )
}

/**
 * Low-prediction scene. The shell `bg` field is still 0; the glucose argument 100 has not been copied.
 * The path minimum is the locked floor, 39 mg/dL, which is under 80 and marks HYPO_CONFLICT.
 * Latent state is built from the snapshot the same way `decideUpdatePhysioLatentState` does
 * before it calls the runtime: confidence 0 gives sensor confidence 0.105 and trunk confidence 0.90.
 */
internal fun lowPredictionPatientLog(
    snapshot: HealthContextSnapshot,
    nowMs: Long,
): List<String> {
    val enriched = enrichPatientThermal(snapshot, wCyclePhase = null)
    val hypothesis = UamHypothesisStateBuilder.build(
        phaseOutput = null,
        mealAbsorptionOutput = null,
        patternSnapshot = null,
        correctionAggressionDecision = null,
        uamConfidence = 0.0,
        behaviorProfile = null,
    )
    val stress = PhysiologicalStressMaskBuilder.build(
        snapshot = enriched,
        physioContext = null,
        physioTrace = null,
        phaseOutput = null,
        patternSnapshot = null,
        correctionAggressionDecision = null,
        chronicInflammation = null,
    )
    val latent = PhysioLatentStateBuilder.build(
        snapshot = enriched,
        sourceSensor = null,
        phaseOutput = null,
        mealAbsorptionOutput = null,
        hypothesisState = hypothesis,
        patternSnapshot = null,
        physioContext = null,
        physioTrace = null,
        correctionAggressionDecision = null,
        chronicInflammation = null,
        autonomicStress = stress.autonomicStress,
        inflammationRecovery = stress.inflammationRecovery,
        hormonalCircadian = stress.hormonalCircadian,
        cgmFirstSensorConfidence = false,
    )
    val curve = ScenarioProjectionCurve(
        kind = ScenarioProjectionKind.SCENARIO_BEST,
        pointsMgdl = listOf(39),
        terminalMgdl = 43.78040816326531,
        pathMinMgdl = 39.0,
        pathMinHitFloor = true,
    )
    return publishPatientLog(
        enriched = enriched,
        hypothesis = hypothesis,
        latent = latent,
        nowMs = nowMs,
        curve = curve,
    )
}

/**
 * Meal, sport, and night ticks. Same builder as [lowPredictionPatientLog], without the
 * path-39 curve that marks HYPO_CONFLICT. Glucose is the locked scene value.
 */
internal fun scenePatientLog(
    snapshot: HealthContextSnapshot,
    nowMs: Long,
    bgMgdl: Double,
    deltaMgdl: Double,
    targetBgMgdl: Double = 100.0,
): List<String> {
    val enriched = enrichPatientThermal(snapshot, wCyclePhase = null)
    val hypothesis = UamHypothesisStateBuilder.build(
        phaseOutput = null,
        mealAbsorptionOutput = null,
        patternSnapshot = null,
        correctionAggressionDecision = null,
        uamConfidence = 0.0,
        behaviorProfile = null,
    )
    val stress = PhysiologicalStressMaskBuilder.build(
        snapshot = enriched,
        physioContext = null,
        physioTrace = null,
        phaseOutput = null,
        patternSnapshot = null,
        correctionAggressionDecision = null,
        chronicInflammation = null,
    )
    val latent = PhysioLatentStateBuilder.build(
        snapshot = enriched,
        sourceSensor = null,
        phaseOutput = null,
        mealAbsorptionOutput = null,
        hypothesisState = hypothesis,
        patternSnapshot = null,
        physioContext = null,
        physioTrace = null,
        correctionAggressionDecision = null,
        chronicInflammation = null,
        autonomicStress = stress.autonomicStress,
        inflammationRecovery = stress.inflammationRecovery,
        hormonalCircadian = stress.hormonalCircadian,
        cgmFirstSensorConfidence = false,
    )
    return publishPatientLog(
        enriched = enriched,
        hypothesis = hypothesis,
        latent = latent,
        nowMs = nowMs,
        curve = null,
        bgMgdl = bgMgdl,
        deltaMgdl = deltaMgdl,
        targetBgMgdl = targetBgMgdl,
    )
}

private fun publishPatientLog(
    enriched: HealthContextSnapshot,
    hypothesis: UamHypothesisState,
    latent: PhysioLatentState,
    nowMs: Long,
    curve: ScenarioProjectionCurve?,
    bgMgdl: Double = 0.0,
    deltaMgdl: Double = 0.0,
    targetBgMgdl: Double = 100.0,
): List<String> {
    val log = mutableListOf<String>()
    decideRefreshPatientStateRuntime(
        nowMs = nowMs,
        contextSnapshot = null,
        healthSnapshot = enriched,
        sourceSensor = null,
        refreshSource = PatientRefreshSource.LOOP_TICK,
        calls = LowPredictionPatientCalls(
            log = log,
            latentState = latent,
            hypothesisState = hypothesis,
            curve = curve,
            bgMgdl = bgMgdl,
            deltaMgdl = deltaMgdl,
            targetBgMgdl = targetBgMgdl,
        ),
    )
    return log
}

internal fun enrichPatientThermal(
    snapshot: HealthContextSnapshot,
    wCyclePhase: app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase?,
): HealthContextSnapshot {
    val thermalBelief = ThermalBeliefEngine.buildFromCache(
        hrNowBpm = snapshot.hrNow,
        rhrRestingBpm = snapshot.rhrResting,
        sleepDebtMinutes = snapshot.sleepDebtMinutes,
        hrvRmssd = snapshot.hrvRmssd,
        wCyclePhase = wCyclePhase,
    )
    return snapshot.copy(thermalBelief = thermalBelief)
}

private class LowPredictionPatientCalls(
    private val log: MutableList<String>,
    private val latentState: PhysioLatentState,
    private val hypothesisState: UamHypothesisState,
    private val curve: ScenarioProjectionCurve?,
    private val bgMgdl: Double = 0.0,
    private val deltaMgdl: Double = 0.0,
    private val targetBgMgdl: Double = 100.0,
) : PatientRuntimeCalls {
    override fun enrichThermal(snapshot: HealthContextSnapshot): HealthContextSnapshot =
        enrichPatientThermal(snapshot, wCyclePhase = null)

    override fun eventMemory(
        currentBgMgdl: Double,
        latentState: PhysioLatentState?,
        thermalBelief: ThermalBeliefDigest?,
        nowMs: Long,
    ): PatientEventMemory = PatientEventMemoryCalculator.compute(
        currentBgMgdl = currentBgMgdl,
        windowedSamples = emptyList(),
        hypoFloor75m = 200.0,
        latentState = latentState,
        recoveryBurden = thermalBelief?.recoveryBurden ?: 0.0,
        nowMs = nowMs,
    )

    override fun phase() = null
    override fun mealAbsorption() = null
    override fun pattern() = null
    override fun latent() = latentState
    override fun hypothesis() = hypothesisState
    override fun ensureWCycle() = null
    override fun wCyclePreferences(): WCyclePreferences = error("low prediction has no cycle profile")
    override fun hourOfDay() = 0
    override fun hypoGuardActive() = false
    override fun effectiveDiaHours(): Double? = null
    override fun effectivePeakMinutes(): Double? = null
    override fun insulinAction() = null
    override fun effortAssessment() = null
    override fun cgmNoise() = 0.0
    override fun sensorInsertionMs(nowMs: Long): Long? = null
    override fun scenarioBest() = curve
    override fun cachedEventualTerminal(): Double? = null
    override fun eventualBg() = 0.0
    override fun authoritativeEventual(fallback: Double) = fallback
    override fun bg() = bgMgdl
    override fun delta() = deltaMgdl
    override fun targetBg() = targetBgMgdl
    override fun cob() = 0.0
    override fun shortAvgDelta() = 0.0
    override fun effortVeto() = false
    override fun effortLive() = true
    override fun basalUph() = 0.0f
    override fun autodriveMaxBasal() = 0.0
    override fun maxSmb() = 0.5
    override fun maxSmbHb() = 0.5
    override fun maxIob() = 0.0
    override fun iob() = 0.0
    override fun chaosScore() = 0.0
    override fun priorRuntimeBlocker(): String? = null
    override fun priorBlockedStreak() = 0
    override fun aggression() = null
    override fun inflammation() = null
    override fun physioContext() = null
    override fun physioTrace() = null
    override fun uamConfidence() = 0.0
    override fun storedSourceSensor() = null
    override fun log(line: String) {
        log += line
    }
}
