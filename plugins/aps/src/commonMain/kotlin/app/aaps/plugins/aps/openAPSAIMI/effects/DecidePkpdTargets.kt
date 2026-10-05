package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PredictionPhysioModulationResolver
import app.aaps.plugins.aps.openAPSAIMI.prediction.NaiveEventualBgSignGuard
import app.aaps.plugins.aps.openAPSAIMI.prediction.PredictionDivergenceAuditor
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelopeBuilder
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.risk.IobConsensus
import app.aaps.plugins.aps.openAPSAIMI.risk.IobDecisionSource
import app.aaps.plugins.aps.openAPSAIMI.risk.PredictionPathBounds
import app.aaps.plugins.aps.openAPSAIMI.risk.PredictionPathMath
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.JsonObject

internal data class AimiPkpdPrediction(
    val eventual: Double,
    val series: List<Int>,
    val pathBounds: PredictionPathBounds,
)

internal data class AimiPkpdTargetStage(
    val bgi: Double,
    val deviation: Int,
    val minBg: Double,
    val targetBg: Double,
    val maxBg: Double,
)

/**
 * Android reads and writes of the PKPD prediction and the noisy-target adjustment.
 * Each method is the call that already existed at that line.
 */
internal interface AimiPkpdCurveCalls {
    fun setAdvancedCurves(curves: AdvancedPredictionCurves)
    fun recordSoftFloor(curves: AdvancedPredictionCurves): PkpdSoftFloorTelemetry
}

internal interface AimiPkpdTargetCalls : AimiPkpdCurveCalls {
    fun mealAbsorptionOutput(): MealAbsorptionPhaseEngine.Output?
    fun uamHypothesis(): UamHypothesisState?
    fun latentState(): PhysioLatentState?
    fun uamConfidence(): Double
    fun setEventualBg(value: Double)
    fun eventualBg(): Double
    fun setPredictedBg(value: Float)
    fun scenarioBestTerminalMgdl(): Double?
    fun physioPhaseName(): String?
    fun mealPhaseName(): String?
    fun setPredDivergenceExport(value: JsonObject)
    fun reconstructedIobUnits(): Double
    fun cachedPkpdRuntimePresent(): Boolean
    fun pkpdRelativeActivity(): Double?
    fun pkpdActivityStage(): InsulinActivityStage?
    fun minBgInLastMinutes(minutes: Int): Double
    fun roundToIntUnits(value: Double): Int
    fun publishDoseTerminal(
        rT: RT,
        profile: OapsProfileAimi,
        mealData: MealData,
        pkpdEventualMgdl: Double,
        pkpdPredTerminalMgdl: Double,
        targetBgMgdl: Double,
        stageTag: String,
    )
    fun refineAfterDose(rT: RT)
    fun decisionPrediction(): DecisionPredictionAuthority?
    fun cob(): Float
    fun projectionInput(
        targetBgValue: Double,
        cobValue: Double,
        combinedDeltaValue: Float,
    ): CorrectionAggressionGate.Input
    fun doseSnapshotTerminals(): Pair<Double?, Double?>
    fun mealSafetyContext(iobData: IobTotal): MealSafetyContext
    fun mealCertainty(): MealCertainty?
    fun setRiskEnvelope(envelope: AimiRiskEnvelope)
    fun reconcileSafetyRisk()
    fun logError(line: String)
}

/**
 * `computePkpdPredictions`.
 *
 * BG 180 and sensitivity 50 produce eventual 195. The working-target stage turns that into a
 * correction of 2.00 U once the target drops to 80. The prediction engine used to swallow
 * [Exception] and substitute a flat curve. That fallback is unchanged, and the failure stays the
 * line `Error in AdvancedPredictionEngine: <message>`.
 */
internal fun decideComputePkpdPredictions(
    currentBg: Double,
    iobArray: Array<IobTotal>,
    finalSensitivity: Double,
    cobG: Double,
    profile: OapsProfileAimi,
    rT: RT,
    delta: Double,
    pkpdRuntime: PkPdRuntime?,
    mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
    hypothesisState: UamHypothesisState?,
    latentState: PhysioLatentState?,
    uamConfidence: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiPkpdCurveCalls,
): AimiPkpdPrediction {
    consoleLog.add("Debug: computePkpdPredictions called with delta=$delta")
    val predictionModulation = PredictionPhysioModulationResolver.resolve(
        fallbackSensitivityMgdlPerU = finalSensitivity,
        pkpdRuntime = pkpdRuntime,
        mealAbsorptionOutput = mealAbsorptionOutput,
        hypothesisState = hypothesisState,
        latentState = latentState,
        uamConfidence = uamConfidence,
    )
    if (!predictionModulation.isNeutral() || predictionModulation.falseMealSuppression) {
        consoleLog.add(PredictionPhysioModulationResolver.formatLogLine(predictionModulation))
    }
    val curves = try {
        AdvancedPredictionEngine.predictCurves(
            currentBG = currentBg,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile,
            delta = delta,
            modulation = predictionModulation,
            endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
            hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
            stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
        )
    } catch (e: Exception) {
        consoleLog.add("Error in AdvancedPredictionEngine: ${e.message}")
        val flat = List(48) { currentBg }
        AdvancedPredictionCurves(flat, flat, flat, flat, flat)
    }
    calls.setAdvancedCurves(curves)
    val softFloor = calls.recordSoftFloor(curves)

    val pathBounds = PredictionPathMath.boundsFromPredictions(
        Predictions().apply {
            IOB = curves.iob.map { AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt() }
            COB = curves.cob.map { AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt() }
            UAM = curves.uam.map { AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt() }
            ZT = curves.zt.map { AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt() }
        },
    ).let { curveBounds ->
        val rawBounds = PredictionPathMath.boundsFromRawSeries(curves.hybrid)
        PredictionPathBounds(
            pathMinRawMgdl = rawBounds.pathMinRawMgdl,
            pathMinClampedMgdl = curveBounds.pathMinClampedMgdl ?: rawBounds.pathMinClampedMgdl,
            pathMinHitNumericFloor = rawBounds.pathMinHitNumericFloor || curveBounds.pathMinHitNumericFloor,
        )
    }
    fun sanitizeInts(points: List<Double>): List<Int> =
        points.map { AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt() }
    val iobInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeInts(curves.iob), softFloor)
    val cobInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeInts(curves.cob), softFloor)
    val uamInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeInts(curves.uam), softFloor)
    val ztInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeInts(curves.zt), softFloor)
    val hybridInts = sanitizeInts(curves.hybrid)
    rT.predBGs = Predictions().apply {
        IOB = iobInts
        COB = cobInts
        ZT = ztInts
        UAM = uamInts
    }

    val eventual = hybridInts.lastOrNull()?.toDouble() ?: currentBg
    consoleLog.add(
        "PKPD predictions → eventual=${aimiFmt0(eventual)} mg/dL from ${hybridInts.size} steps " +
            "uamT=${uamInts.lastOrNull() ?: "n/a"} " +
            "pathMinRaw=${pathBounds.pathMinRawMgdl?.let { aimiFmt0(it) } ?: "n/a"} " +
            "pathMinClamp=${pathBounds.pathMinClampedMgdl?.let { aimiFmt0(it) } ?: "n/a"}",
    )
    return AimiPkpdPrediction(eventual, hybridInts, pathBounds)
}

/**
 * `runPkpdPredictionsBgiDeviationAndNoisyTargetsStage`.
 *
 * High glucose with advanced target adjustments lowers the working target to 80, so the
 * correction against ISF 50 is 2.00 U. This function has no swallowed [Exception]. The
 * prediction engine's catch lives in [decideComputePkpdPredictions].
 */
internal fun decidePkpdPredictionsAndNoisyTargets(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    pkpdRuntime: PkPdRuntime?,
    iobData: IobTotal,
    bg: Double,
    delta: Float,
    sens: Double,
    minDelta: Double,
    minAvgDelta: Double,
    minBg: Double,
    targetBg: Double,
    maxBg: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    texts: TextResolver,
    calls: AimiPkpdTargetCalls,
): AimiPkpdTargetStage {
    val effectiveSens = sens * ctx.autosensData.ratio
    val pkpdPredictions = decideComputePkpdPredictions(
        currentBg = bg,
        iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
        finalSensitivity = effectiveSens,
        cobG = ctx.mealData.mealCOB,
        profile = profile,
        rT = rT,
        delta = delta.toDouble(),
        pkpdRuntime = pkpdRuntime,
        mealAbsorptionOutput = calls.mealAbsorptionOutput(),
        hypothesisState = calls.uamHypothesis(),
        latentState = calls.latentState(),
        uamConfidence = calls.uamConfidence(),
        preferences = preferences,
        consoleLog = consoleLog,
        calls = calls,
    )
    calls.setEventualBg(pkpdPredictions.eventual)
    calls.setPredictedBg(pkpdPredictions.eventual.toFloat())
    rT.eventualBG = pkpdPredictions.eventual
    val divergenceAudit = PredictionDivergenceAuditor.audit(
        bgMgdl = bg,
        pkpdEventualMgdl = pkpdPredictions.eventual,
        scenarioBestMgdl = calls.scenarioBestTerminalMgdl(),
    )
    val physioPhaseName = calls.physioPhaseName()
    val mealPhaseName = calls.mealPhaseName()
    consoleLog.add(PredictionDivergenceAuditor.formatLogLine(divergenceAudit, physioPhaseName, mealPhaseName))
    calls.setPredDivergenceExport(
        PredictionDivergenceAuditor.toJsonObject(divergenceAudit, physioPhaseName, mealPhaseName),
    )
    val reconstructedIob = calls.reconstructedIobUnits()
    val iobConsensus = IobConsensus.resolve(
        aapsIobUnits = iobData.iob,
        pkpdIobUnits = reconstructedIob.takeIf { calls.cachedPkpdRuntimePresent() },
    )
    if (iobConsensus.source == IobDecisionSource.PKPD_WHEN_AAPS_NEGATIVE) {
        consoleLog.add(
            "🛡️ IOB_CONSENSUS_PKPD: AAPS=${aimiFmt2(iobConsensus.aapsIobUnits)} → " +
                "PKPD=${aimiFmt2(iobConsensus.pkpdIobUnits)} (Δ=${aimiFmt2(iobConsensus.deltaUnits)})",
        )
    }
    val bgi = AimiTickPolicyMath.round((-iobData.activity * effectiveSens * 5), 2)
    var deviation = calls.roundToIntUnits((30 / 5) * (minDelta - bgi))
    if (deviation < 0) {
        deviation = calls.roundToIntUnits((30 / 5) * (minAvgDelta - bgi))
        if (deviation < 0) {
            deviation = calls.roundToIntUnits((30 / 5) * (glucoseStatus.longAvgDelta - bgi))
        }
    }
    val naiveEbgResolution = NaiveEventualBgSignGuard.resolve(
        bgMgdl = bg,
        iobUnits = iobConsensus.decisionIobUnits,
        sensMgDlPerU = sens,
        pkpdRelativeActivity = calls.pkpdRelativeActivity(),
        pkpdStage = calls.pkpdActivityStage(),
        minBgLookback75mMgdl = calls.minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
    )
    if (naiveEbgResolution.signGuardApplied) {
        consoleLog.add(
            "🛡️ NAIVE_EBG_SIGN_GUARD: ${naiveEbgResolution.collapseReason} " +
                "→ collapse naive ebg ${naiveEbgResolution.rawNaiveRoundedMgdl.toInt()} → ${bg.toInt()}",
        )
    }
    val naiveEventualBg = naiveEbgResolution.naiveEventualBgMgdl
    val legacyEventual = naiveEventualBg + deviation
    val pkpdPredTerminalBefore = minPredictedAcrossCurves(rT.predBGs) ?: pkpdPredictions.eventual
    calls.publishDoseTerminal(
        rT = rT,
        profile = profile,
        mealData = ctx.mealData,
        pkpdEventualMgdl = pkpdPredictions.eventual,
        pkpdPredTerminalMgdl = pkpdPredTerminalBefore,
        targetBgMgdl = targetBg,
        stageTag = "late_pkpd",
    )
    calls.refineAfterDose(rT)
    val decisionPrediction = checkNotNull(calls.decisionPrediction()) {
        "Dose terminal snapshot publish must set lastDecisionPredictionAuthority"
    }

    val projectionInput = calls.projectionInput(
        targetBgValue = targetBg,
        cobValue = calls.cob().toDouble(),
        combinedDeltaValue = glucoseStatus.combinedDelta.toFloat(),
    )
    val (snapMin, snapEventual) = calls.doseSnapshotTerminals()
    val envelope = AimiRiskEnvelopeBuilder.buildDecision(
        bg = bg,
        delta = delta,
        predTerminal = snapMin ?: pkpdPredictions.eventual,
        eventualTerminal = snapEventual ?: pkpdPredictions.eventual,
        pathBounds = pkpdPredictions.pathBounds,
        aapsIobUnits = iobData.iob,
        iobConsensus = iobConsensus,
        lgsThreshold = profile.lgsThreshold,
        naiveEbgSignGuardApplied = naiveEbgResolution.signGuardApplied,
        predictionAuthority = decisionPrediction,
        mealSafetyContext = calls.mealSafetyContext(iobData),
        mealAbsorptionPhase = calls.mealAbsorptionOutput()?.phase ?: MealAbsorptionPhase.NONE,
        targetBgMgdl = projectionInput.targetBg,
        minBgLookback75m = projectionInput.minBgLookback75m,
        hasIndependentMealEvidence = CorrectionAggressionGate.hasIndependentMealEvidence(projectionInput),
        mealCertainty = calls.mealCertainty(),
    )
    calls.setRiskEnvelope(envelope)
    consoleLog.add(AimiRiskEnvelopeBuilder.formatLogLine(envelope))
    calls.reconcileSafetyRisk()

    var minBgOut = minBg
    var targetBgOut = targetBg
    var maxBgOut = maxBg

    if (bg > maxBg && profile.adv_target_adjustments && !profile.temptargetSet) {
        val adjustedMinBG = AimiTickPolicyMath.round(max(80.0, minBgOut - (bg - minBgOut) / 3.0), 0)
        val adjustedTargetBG = AimiTickPolicyMath.round(max(80.0, targetBgOut - (bg - targetBgOut) / 3.0), 0)
        val adjustedMaxBG = AimiTickPolicyMath.round(max(80.0, maxBgOut - (bg - maxBgOut) / 3.0), 0)
        if (calls.eventualBg() > adjustedMinBG && legacyEventual > adjustedMinBG && minBgOut > adjustedMinBG) {
            consoleLog.add(texts.gs(ApsStrings.console_min_bg_adjusted, minBgOut, adjustedMinBG))
            minBgOut = adjustedMinBG
        } else {
            consoleLog.add(texts.gs(ApsStrings.console_min_bg_unchanged, minBgOut))
        }
        if (calls.eventualBg() > adjustedTargetBG && legacyEventual > adjustedTargetBG && targetBgOut > adjustedTargetBG) {
            consoleLog.add(texts.gs(ApsStrings.console_target_bg_adjusted, targetBgOut, adjustedTargetBG))
            targetBgOut = adjustedTargetBG
        } else {
            consoleLog.add(texts.gs(ApsStrings.console_target_bg_unchanged, targetBgOut))
        }
        if (calls.eventualBg() > adjustedMaxBG && legacyEventual > adjustedMaxBG && maxBgOut > adjustedMaxBG) {
            calls.logError(texts.gs(ApsStrings.console_max_bg_adjusted, maxBgOut, adjustedMaxBG))
            maxBgOut = adjustedMaxBG
        } else {
            calls.logError(texts.gs(ApsStrings.console_max_bg_unchanged, maxBgOut))
        }
    }

    return AimiPkpdTargetStage(
        bgi = bgi,
        deviation = deviation,
        minBg = minBgOut,
        targetBg = targetBgOut,
        maxBg = maxBgOut,
    )
}
