package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaHarmonizer
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyLevel
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects
import app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbTbrThrottleLogic
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.quality.IobSurveillanceExport
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtResolutionBridge
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiSmbFinalizeMath
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyNet
import app.aaps.plugins.aps.openAPSAIMI.safety.signalEventualDrop
import app.aaps.plugins.aps.openAPSAIMI.safety.signalMinPredDrop
import app.aaps.plugins.aps.openAPSAIMI.safety.signalTrajectoryStack

/** `resolveMealCorrectionContext`, at the line the reference calls it. */
internal fun interface AimiFinalizeMealCorrection {
    fun resolve(mealData: MealData, bgMgdl: Double, deltaMgdlPer5: Double, shortAvgDeltaMgdlPer5: Double): MealCorrectionContextResolverOutput
}

/** Dose-facing eventual and min-pred. Each reads the prediction-authority preference only when called. */
internal interface AimiFinalizeDoseBg {
    fun eventual(fallback: Double): Double
    fun minPred(rT: RT, rawMinPred: Double?): Double?
}

/** `applySafetyPrecautions`. Stays on Android: it logs and may set the critical-safety flag. */
internal fun interface AimiFinalizeSafety {
    fun apply(
        mealData: MealData,
        smbToGiveParam: Float,
        hypoThreshold: Double,
        reason: StringBuilder,
        pkpdRuntime: PkPdRuntime?,
        exerciseFlag: Boolean,
        suspectedLateFatMeal: Boolean,
        ignoreSafetyConditions: Boolean,
    ): Float
}

/** `calculateSMBInterval`. Reads the interval preferences and may log. */
internal fun interface AimiFinalizeSmbInterval {
    fun minutes(): Int
}

/** Dia, profile dia, and the insulin observer, read only when the action is not explicit. */
internal interface AimiFinalizeThrottle {
    fun diaHoursOrNull(): Double?
    fun profileDiaOrNull(): Double?
    fun actionStateOrNull(): InsulinActionState?
    fun updateAction(
        currentBg: Double,
        bgDelta: Double,
        iobTotal: Double,
        iobActivityNow: Double,
        iobActivityIn30: Double,
        minutesToPeak: Int,
        diaHours: Double,
        carbsActiveG: Double,
        now: Long,
    ): InsulinActionState
}

/** `resolveTdd24hForLoop`. Logs the cache line. */
internal fun interface AimiFinalizeTdd {
    fun resolve(fallback: Double): Double
}

/** `isMealModeCondition` and the manual meal flags, each at its own line. */
internal interface AimiFinalizeMealFlags {
    fun mealModeCondition(): Boolean
    fun anyManualMeal(): Boolean
}

/** `minPredictedBgForRbtWiring`. */
internal fun interface AimiFinalizeRbtMinPred {
    fun forWiring(raw: Double?): Double?
}

/** `criticalSafetyZeroedThisTick`, read after safety precautions may have set it. */
internal fun interface AimiFinalizeCriticalFlag {
    fun zeroedThisTick(): Boolean
}

/** Context SMB ceiling and hard off, read into the finalize seed. */
internal interface AimiFinalizeContextSmb {
    fun suppress(): Boolean
    fun ceilingU(): Double?
}

/** Slow-carb early start. The shell calls `dateUtil.now()` only inside the `takeIf`, as the reference does. */
internal fun interface AimiFinalizeSlowCarb {
    fun earlyStartMs(): Long?
}

/** Thyroid gate body. Called only when status is NORMALIZING. */
internal fun interface AimiFinalizeThyroid {
    fun apply(): AimiSmbFinalizeMath.ThyroidGate
}

internal interface AimiFinalizePhrases {
    fun surveillanceApplied(): String
    fun limitsSmb(proposed: Float, allowed: Float): String
}

internal fun interface AimiFinalizeClock {
    fun nowMs(): Long
}

/** `internalLastSmbMillis = dateUtil.now()`, which writes `LastPrebolusTime`. */
internal fun interface AimiFinalizeLatch {
    fun markDelivered(nowMs: Long)
}

internal fun interface AimiFinalizeSeal {
    fun seal()
}

/** Rise-ceiling fields on the pending export baseline. */
internal fun interface AimiFinalizeRiseExport {
    fun note(block: Boolean, reason: String, repeats: Int, withheldU: Double)
}

internal fun interface AimiFinalizeBindingDraft {
    fun current(): SmbBindingTrace.Draft
}

/**
 * Output type of [MealCorrectionContextResolver] without pulling the resolver's name into every port.
 * The shell's `resolveMealCorrectionContext` already returns that output; this alias keeps the port thin.
 */
internal typealias MealCorrectionContextResolverOutput =
    app.aaps.plugins.aps.openAPSAIMI.MealCorrectionContextResolver.Output

/** Field writes that do not themselves log. Applied by the shell before it returns to the caller. */
internal data class FinalizeSmbSideEffects(
    val decisionSource: String,
    val smbProposed: Double,
    val pkpdThrottleIntervalAdd: Int,
    val pkpdPreferTbrBoost: Double,
    val slowCarbWindowMs: Long,
    val slowCarbDeliveredU: Double,
    val ceilingRepeatCount: Int,
    val ceilingRepeatLastMs: Long,
    val effortFactorRaw: Double,
    val effortFactorApplied: Double,
    val effortBeforeU: Double,
    val effortAfterU: Double,
    val bindingTrace: SmbBindingTrace.Draft,
    val smbCapped: Double,
    val smbFinal: Double,
    val iobSurveillance: IobSurveillanceExport,
)

private data class SmbGateAudit(
    val sinceBolus: Double,
    val refractoryWindow: Double,
    val absorptionFactor: Double,
    val predMissing: Boolean,
    val maxIobLimit: Double,
    val maxSmbLimit: Double,
)

/**
 * SMB finalize chain. The math stays in [AimiSmbFinalizeMath]. This function still reads
 * preferences and calls the Android guards at the same lines. An auditor-cache [Exception]
 * keeps the null confidence and is an [OptionalSignal.Failed] plus a console line.
 * Returns null when post-hypo delivery blocks the SMB and the reference returns immediately.
 */
internal fun decideFinalizeAndCapSmb(
    rT: RT,
    proposedUnits: Double,
    reasonHeader: String,
    mealData: MealData,
    hypoThreshold: Double,
    isExplicitUserAction: Boolean,
    decisionSource: String,
    isMealActive: Boolean,
    hyperReleaseFloorU: Double,
    bypassSmbRefractory: Boolean,
    postHypo: PostHypoDeliveryAuthority.Decision,
    uamHypotheses: UamHypothesisState?,
    mealAbsorption: MealAbsorptionPhaseEngine.Output?,
    rbtHints: RbtResolutionBridge.AppliedHints?,
    bg: Double,
    delta: Float,
    shortAvgDelta: Float,
    targetBg: Float,
    iob: Float,
    maxIob: Double,
    maxSmb: Double,
    maxSmbHb: Double,
    memberEventualBg: Double,
    harmoniaDecision: HarmoniaDecision?,
    harmonizerOutcome: HarmoniaHarmonizer.Outcome?,
    pkpdRuntime: PkPdRuntime?,
    sportTime: Boolean,
    lateFatRise: Boolean,
    thyroidEffects: ThyroidEffects,
    predictionAvailable: Boolean,
    predictionSize: Int,
    lastBolusAgeMinutes: Double,
    iobActivityNow: Double,
    cob: Float,
    effort: EffortActivityBelief.Assessment?,
    mealCertainty: MealCertainty?,
    physiologicalPhase: PhysiologicalPhaseClassifier.Output?,
    slowCarbBudgetU: Double,
    slowCarbWindowMs: Long,
    slowCarbDeliveredU: Double,
    ceilingRepeatCount: Int,
    ceilingRepeatLastMs: Long,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    uamConfidence: AimiUamConfidence,
    mealCorrection: AimiFinalizeMealCorrection,
    doseBg: AimiFinalizeDoseBg,
    safety: AimiFinalizeSafety,
    smbInterval: AimiFinalizeSmbInterval,
    throttleInputs: AimiFinalizeThrottle,
    tdd: AimiFinalizeTdd,
    mealFlags: AimiFinalizeMealFlags,
    rbtMinPred: AimiFinalizeRbtMinPred,
    criticalFlag: AimiFinalizeCriticalFlag,
    contextSmb: AimiFinalizeContextSmb,
    slowCarb: AimiFinalizeSlowCarb,
    thyroidGate: AimiFinalizeThyroid,
    phrases: AimiFinalizePhrases,
    clock: AimiFinalizeClock,
    latch: AimiFinalizeLatch,
    seal: AimiFinalizeSeal,
    riseExport: AimiFinalizeRiseExport,
    bindingDraft: AimiFinalizeBindingDraft,
    smbAction: AimiSmbActionType,
    format2f: (Float) -> String,
): FinalizeSmbSideEffects? {
    if (postHypo.active && postHypo.suppressMealDelivery && !isExplicitUserAction) {
        consoleLog.add(PostHypoDeliveryAuthority.formatLogLine(postHypo))
        consoleLog.add("${PostHypoDeliveryAuthority.LOG_PREFIX}: smb_blocked source=$decisionSource")
        return null
    }

    val uamConfidenceValue = uamConfidence.confidenceOrZero()
    val suppressMealInterpretation = uamHypotheses?.suppressMealInterpretation == true ||
        rbtHints?.suppressMealInterpretation == true
    val mealDeliveryPriority = !isExplicitUserAction &&
        !suppressMealInterpretation &&
        (mealAbsorption?.mealDeliveryPriority == true)
    val mealCorrectionContext = mealCorrection.resolve(
        mealData = mealData,
        bgMgdl = bg,
        deltaMgdlPer5 = delta.toDouble(),
        shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
    )
    val highBgBandForHtr = HyperTrajectoryHypoCredibility.highBgBandMgdl(
        targetBg.toDouble(),
        preferences.get(DoubleKey.OApsAIMIHighBg),
    )
    val decisionEventualBgForSmb = doseBg.eventual(memberEventualBg)
    val prioritySeed = AimiSmbFinalizeMath.Input(
        proposedUnits = proposedUnits,
        waitBias = rbtHints?.waitBiasMultiplier ?: 1.0,
        isExplicitUserAction = isExplicitUserAction,
        isMealActive = isMealActive,
        hyperReleaseFloorU = hyperReleaseFloorU,
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        targetBg = targetBg.toDouble(),
        iob = iob.toDouble(),
        maxIob = maxIob,
        mealCob = mealData.mealCOB,
        uamConfidence = uamConfidenceValue,
        mealCompatibleProb = uamHypotheses?.mealCompatibleProb() ?: 0.0,
        suppressMealInterpretation = suppressMealInterpretation,
        mealDeliveryPriority = mealDeliveryPriority,
        rbtMealPriority = rbtHints?.mealPriorityContext == true,
        mealPriorityEligible = mealCorrectionContext.mealPriorityEligible,
        mealSummary = mealCorrectionContext.summary(),
        mealPhaseName = mealAbsorption?.phase?.name ?: "legacy",
        highBgBand = highBgBandForHtr,
    )
    val priority = AimiSmbFinalizeMath.contexts(prioritySeed)
    val allowAuditorSoftLanding =
        !HarmoniaHarmonizer.blocksAuditorSoftLanding(harmonizerOutcome) &&
            harmoniaDecision?.action != HarmoniaAction.BLOCKED
    val auditorLastConfidence: Double? = if (!allowAuditorSoftLanding) {
        null
    } else {
        readRbtOptional(
            source = "auditorCache",
            consoleLog = consoleLog,
        ) { AuditorVerdictCache.get(300_000)?.verdict?.confidence }.valueOrNull()
    }
    val baseLimit = SafetyNet.calculateSafeSmbLimit(
        bg = bg,
        targetBg = targetBg.toDouble(),
        eventualBg = decisionEventualBgForSmb,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        maxSmbLow = maxSmb,
        maxSmbHigh = maxSmbHb,
        isExplicitUserAction = isExplicitUserAction,
        auditorConfidence = auditorLastConfidence,
        mealPriorityContext = priority.smbDeliveryPriorityContext,
        allowAuditorSoftLanding = allowAuditorSoftLanding,
    )
    AimiSmbFinalizeMath.openingLogs(prioritySeed).forEach { consoleLog.add(it) }
    val safetyUnits = safety.apply(
        mealData = mealData,
        smbToGiveParam = (proposedUnits * (rbtHints?.waitBiasMultiplier ?: 1.0)).toFloat(),
        hypoThreshold = hypoThreshold,
        reason = rT.reason,
        pkpdRuntime = pkpdRuntime,
        exerciseFlag = sportTime,
        suspectedLateFatMeal = lateFatRise,
        ignoreSafetyConditions = isExplicitUserAction,
    )
    val proposedFloatForSafety = (proposedUnits * (rbtHints?.waitBiasMultiplier ?: 1.0)).toFloat()
    val safetyCappedForLog = safetyUnits.coerceAtMost(baseLimit.toFloat())
    if (safetyCappedForLog < proposedFloatForSafety) {
        consoleLog.add(
            "Safety Precautions reduced SMB: $proposedFloatForSafety -> $safetyCappedForLog (BaseLimit=${aimiFmt2(baseLimit)})",
        )
    }
    val thyroid = if (thyroidEffects.status == ThyroidStatus.NORMALIZING) {
        thyroidGate.apply()
    } else {
        AimiSmbFinalizeMath.ThyroidGate()
    }
    val predMissing = !predictionAvailable || predictionSize < 3
    val baseRefractoryMinutes = smbInterval.minutes().toDouble()
    val throttle = if (!isExplicitUserAction) {
        val throttleDiaHours = throttleInputs.diaHoursOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: throttleInputs.profileDiaOrNull()
            ?: 6.0
        val minutesToPeak = throttleInputs.actionStateOrNull()?.timeToPeakMin?.takeIf { it > 0 } ?: 0
        val actionState = throttleInputs.actionStateOrNull() ?: throttleInputs.updateAction(
            currentBg = bg,
            bgDelta = delta.toDouble(),
            iobTotal = iob.toDouble(),
            iobActivityNow = iobActivityNow,
            iobActivityIn30 = 0.0,
            minutesToPeak = minutesToPeak,
            diaHours = throttleDiaHours,
            carbsActiveG = cob.toDouble(),
            now = clock.nowMs(),
        )
        val computed = SmbTbrThrottleLogic.computeThrottle(
            actionState = actionState,
            bgDelta = delta.toDouble(),
            bgRising = bg > targetBg,
            targetBg = targetBg.toDouble(),
            currentBg = bg,
        )
        AimiSmbFinalizeMath.Throttle(computed.smbFactor, computed.intervalAddMin, computed.preferTbr, computed.reason)
    } else {
        AimiSmbFinalizeMath.Throttle(1.0, 0, false, "")
    }
    val slowCarbEarlyStart = slowCarb.earlyStartMs()
    val rawEffortFactor = effort?.smbFactor ?: 1.0
    val seed = AimiSmbFinalizeMath.Input(
        proposedUnits = proposedUnits,
        waitBias = rbtHints?.waitBiasMultiplier ?: 1.0,
        isExplicitUserAction = isExplicitUserAction,
        isMealActive = isMealActive,
        hyperReleaseFloorU = hyperReleaseFloorU,
        bypassSmbRefractory = bypassSmbRefractory,
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        targetBg = targetBg.toDouble(),
        iob = iob.toDouble(),
        maxIob = maxIob,
        mealCob = mealData.mealCOB,
        uamConfidence = uamConfidenceValue,
        mealCompatibleProb = uamHypotheses?.mealCompatibleProb() ?: 0.0,
        suppressMealInterpretation = suppressMealInterpretation,
        mealDeliveryPriority = mealDeliveryPriority,
        rbtMealPriority = rbtHints?.mealPriorityContext == true,
        mealPriorityEligible = mealCorrectionContext.mealPriorityEligible,
        redCarpetEligible = mealCorrectionContext.redCarpetEligible,
        mealSummary = mealCorrectionContext.summary(),
        mealPhaseName = mealAbsorption?.phase?.name ?: "legacy",
        highBgBand = highBgBandForHtr,
        baseLimit = baseLimit,
        safetyUnits = safetyUnits,
        maxSmb = maxSmb,
        maxSmbHb = maxSmbHb,
        predMissing = predMissing,
        baseRefractoryMinutes = baseRefractoryMinutes,
        lastBolusAgeMinutes = lastBolusAgeMinutes,
        thyroid = thyroid,
        tdd24h = tdd.resolve(30.0),
        iobActivityNow = iobActivityNow,
        throttle = throttle,
        mealModeCondition = mealFlags.mealModeCondition(),
        criticalSafetyZeroed = criticalFlag.zeroedThisTick(),
        contextSuppressSmb = contextSmb.suppress(),
        contextCeilingU = contextSmb.ceilingU(),
        slowCarbEarlyStartMs = slowCarbEarlyStart,
        slowCarbBudgetU = slowCarbBudgetU,
        slowCarbWindowMs = slowCarbWindowMs,
        slowCarbDeliveredU = slowCarbDeliveredU,
        effortFactorRaw = rawEffortFactor,
        effortFactorApplied = MealCertaintyBuilder.effortSmbFactorFor(mealCertainty, rawEffortFactor),
        confirmedMeal = mealCertainty?.level == MealCertaintyLevel.HIGH,
        effortStateName = "${effort?.state?.name}",
        effortPostureName = "${effort?.posture?.name}",
        surveillancePhrase = phrases.surveillanceApplied(),
        riseCeilingArmed = preferences.get(BooleanKey.OApsAIMIRiseCeilingGuard),
        ceilingRepeatCount = ceilingRepeatCount,
        ceilingRepeatLastMs = ceilingRepeatLastMs,
        nowMs = clock.nowMs(),
        format2f = format2f,
    )
    val eventualForStacking = decisionEventualBgForSmb.takeIf { it.isFinite() && it > 1.0 }
        ?: when {
            memberEventualBg > 1.0 -> memberEventualBg
            rT.eventualBG != null && rT.eventualBG!! > 1.0 -> rT.eventualBG!!
            else -> null
        }
    val rawMinPred = minPredictedAcrossCurves(rT.predBGs)
    val minPredForStacking = rbtMinPred.forWiring(doseBg.minPred(rT, rawMinPred))
    val stackingEval = InsulinStackingStance.evaluate(
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        targetBg = targetBg.toDouble(),
        iob = iob.toDouble(),
        maxIob = maxIob,
        eventualBg = eventualForStacking?.takeIf { it.isFinite() },
        minPredBg = minPredForStacking,
        trajectoryEnergy = rT.trajectoryEnergy,
        isExplicitUserAction = isExplicitUserAction,
        enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
        mealPriorityContext = priority.smbDeliveryPriorityContext,
        endogenousCounterRegulatory =
            physiologicalPhase?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY,
        mealAbsorptionPhase = mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
        mealModeActive = mealFlags.anyManualMeal(),
    )
    val out = AimiSmbFinalizeMath.decide(seed.copy(stacking = stackingEval))
    out.logs.forEach { consoleLog.add(it) }
    out.reasonBits.forEach { rT.reason.append(it) }
    riseExport.note(
        block = out.riseCeilingBlock,
        reason = out.riseCeilingReason,
        repeats = out.riseCeilingRepeats,
        withheldU = out.riseCeilingWithheldU,
    )
    val proposedFloat = out.effectiveProposed.toFloat()
    val finalUnits = out.finalUnits
    val safeCap = out.safeCap
    val gatedUnits = out.gatedAfterStacking
    val trace = bindingDraft.current().copy(
        originOwner = bindingDraft.current().originOwner.takeUnless { it == "NONE" } ?: decisionSource,
        finalOwner = decisionSource,
        maxSmbU = maxSmb,
        maxSmbHighBgU = maxSmbHb,
        iobHeadroomU = (maxIob - iob).coerceAtLeast(0.0),
        safetyNetBaseLimitU = baseLimit,
        throttleBeforeU = out.beforeThrottle.toDouble(),
        throttleAfterU = out.chainAfterThrottle.toDouble(),
        redCarpetBeforeU = safeCap.toDouble(),
        redCarpetAfterU = out.afterRedCarpet,
    )
        .appendStage("SAFETY_PRECAUTIONS_PKPD", proposedFloat.toDouble(), safetyUnits.toDouble(), phase = "FINALIZE", kind = "GUARD")
        .appendStage("SAFETY_NET", safetyUnits.toDouble(), out.chainSafetyCapped.toDouble(), baseLimit, phase = "FINALIZE", kind = "CAP")
        .appendStage("REFRACTORY_AND_THYROID", out.chainSafetyCapped.toDouble(), out.chainAfterRefractory.toDouble(), phase = "FINALIZE", kind = "GUARD")
        .appendStage("ABSORPTION_AND_PREDICTION", out.chainAfterRefractory.toDouble(), out.beforeThrottle.toDouble(), phase = "FINALIZE", kind = "GUARD")
        .appendStage("PKPD_THROTTLE", out.beforeThrottle.toDouble(), out.chainAfterThrottle.toDouble(), phase = "FINALIZE", kind = "DAMPEN")
        .appendStage("IOB_SURVEILLANCE", out.chainAfterThrottle.toDouble(), gatedUnits.toDouble(), stackingEval.smbAbsoluteCapU, phase = "FINALIZE", kind = "CAP")
        .appendStage("MAX_SMB_IOB_CAP", gatedUnits.toDouble(), safeCap.toDouble(), baseLimit, phase = "FINALIZE", kind = "CAP")
        .appendStage(
            "RED_CARPET",
            safeCap.toDouble(),
            out.afterRedCarpet,
            phase = "FINALIZE",
            kind = if (out.afterRedCarpet > safeCap + SmbBindingTrace.REDUCTION_TOLERANCE_U) "RESTORE" else "PASS",
        )
        .appendStage("TERMINAL_PROTECTIONS", out.afterRedCarpet, finalUnits, phase = "FINALIZE", kind = "GUARD")

    if (finalUnits > 0) {
        latch.markDelivered(clock.nowMs())
    }
    rT.units = finalUnits.coerceAtLeast(0.0)
    seal.seal()
    recordSmbActionType(smbAction, if (finalUnits > 0.0) "smb" else "none")
    rT.reason.append(reasonHeader)
    val audit = SmbGateAudit(
        sinceBolus = out.sinceBolus,
        refractoryWindow = out.refractoryWindow,
        absorptionFactor = out.absorptionFactor,
        predMissing = out.predMissing,
        maxIobLimit = maxIob,
        maxSmbLimit = baseLimit,
    )
    if (proposedUnits > 0 || safeCap > 0f) {
        logSmbGateExplain(consoleLog, audit, proposedFloat, gatedUnits, safeCap, out.activityThreshold, iobActivityNow, iob)
    }
    if (safeCap < proposedFloat) {
        rT.reason.appendLine(phrases.limitsSmb(proposedFloat, safeCap))
        consoleLog.add("SMB_CAP: Proposed=$proposedFloat Allowed=$safeCap Reason=$reasonHeader")
        consoleLog.add("  -> Limits: MaxSMB=$baseLimit MaxIOB=$maxIob IOB=$iob")
        if (safeCap == 0f && iob >= maxIob) {
            consoleLog.add("  -> BLOCK: IOB_SATURATION (IOB $iob >= MaxIOB $maxIob)")
        }
    }
    out.mealPriorityChainLine?.let { chainLine ->
        consoleLog.add(chainLine)
        rT.reason.append(" | $chainLine")
    }
    val minPredForExport = minPredictedAcrossCurves(rT.predBGs)
    val evExp = eventualForStacking?.takeIf { it.isFinite() }
    val mnExp = minPredForExport?.takeIf { it.isFinite() }
    val teExp = rT.trajectoryEnergy
    val iobFloorExp = InsulinStackingStance.iobFloorU(maxIob)
    val surveillance = IobSurveillanceExport(
        pref_enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
        preference_key = BooleanKey.OApsAIMIIobSurveillanceGuard.key,
        kind = stackingEval.kind.name,
        active_reason = stackingEval.activeReason,
        meal_priority_context = out.mealPriorityContext,
        bg_mgdl = bg,
        target_bg_mgdl = targetBg.toDouble(),
        delta_mgdl_5m = delta.toDouble(),
        short_avg_delta_mgdl_5m = shortAvgDelta.toDouble(),
        iob_u = iob.toDouble(),
        max_iob_u = maxIob,
        iob_floor_u = iobFloorExp,
        eventual_bg = evExp,
        min_predicted_bg = mnExp,
        trajectory_energy = teExp?.takeIf { it.isFinite() },
        signal_eventual_drop = signalEventualDrop(bg, evExp),
        signal_min_pred_drop = signalMinPredDrop(bg, mnExp),
        signal_trajectory_stack = signalTrajectoryStack(teExp),
        smb_multiplier = stackingEval.smbMultiplier,
        smb_cap_u = stackingEval.smbAbsoluteCapU,
        suppress_red_carpet_restore = stackingEval.suppressRedCarpetRestore,
        tbr_boost_floor = stackingEval.tbrBoostFloor,
        smb_u_after_pkpd_before_stacking = out.chainAfterThrottle.toDouble(),
        smb_u_after_stacking_step = gatedUnits.toDouble(),
        stacking_reduced_smb = out.stackingReduced,
        pkpd_tbr_boost_after_finalize = out.pkpdPreferTbrBoost,
        smb_u_after_cap_smb_dose = safeCap.toDouble(),
        smb_u_final_for_delivery = out.chainFinal,
        smb_final_source = out.smbFinalSource,
        summary_line = when (stackingEval.kind) {
            InsulinStackingStance.Kind.SURVEILLANCE_IOB -> stackingEval.summary
            InsulinStackingStance.Kind.CORRECTION_ACTIVE ->
                "CORRECTION_ACTIVE reason=${stackingEval.activeReason ?: "default"} meal_priority=${out.mealPriorityContext} " +
                    "signals(ev=${signalEventualDrop(bg, evExp)} mn=${signalMinPredDrop(bg, mnExp)} traj=${signalTrajectoryStack(teExp)})"
        },
        tuning_reference = InsulinStackingStance.tuningReferenceAscii(),
    )
    return FinalizeSmbSideEffects(
        decisionSource = decisionSource,
        smbProposed = out.effectiveProposed,
        pkpdThrottleIntervalAdd = out.pkpdThrottleIntervalAdd,
        pkpdPreferTbrBoost = out.pkpdPreferTbrBoost,
        slowCarbWindowMs = out.slowCarbWindowMs,
        slowCarbDeliveredU = out.slowCarbDeliveredU,
        ceilingRepeatCount = out.ceilingRepeatCount,
        ceilingRepeatLastMs = out.ceilingRepeatLastMs,
        effortFactorRaw = out.effortFactorRaw,
        effortFactorApplied = out.effortFactorApplied,
        effortBeforeU = out.effortBeforeU,
        effortAfterU = out.effortAfterU,
        bindingTrace = trace,
        smbCapped = finalUnits,
        smbFinal = finalUnits,
        iobSurveillance = surveillance,
    )
}

private fun logSmbGateExplain(
    consoleLog: MutableList<String>,
    audit: SmbGateAudit,
    proposed: Float,
    gated: Float,
    final: Float,
    activityThreshold: Double,
    iobActivityNow: Double,
    iob: Float,
) {
    val refractoryLine =
        "GATE_REFRACTORY sinceLastBolus=${aimiFmt1(audit.sinceBolus)}m window=${aimiFmt1(audit.refractoryWindow)}"
    val maxIobLine = "GATE_MAXIOB allowed=${aimiFmt2(audit.maxIobLimit)} current=${aimiFmt2(iob)}"
    val maxSmbLine = "GATE_MAXSMB cap=${aimiFmt2(audit.maxSmbLimit)} proposed=${aimiFmt2(proposed)}"
    val absorptionLine = "GATE_ABSORPTION activity=${aimiFmt3(iobActivityNow)} threshold=${aimiFmt3(activityThreshold)} factor=${aimiFmt2(audit.absorptionFactor)}"
    val predLine = "GATE_PRED_MISSING fallback=${if (audit.predMissing) "ON" else "OFF"}"

    if (final > 0f || gated == 0f || final == 0f) {
        consoleLog.add(refractoryLine)
        consoleLog.add(maxIobLine)
        consoleLog.add(maxSmbLine)
        consoleLog.add(absorptionLine)
        consoleLog.add(predLine)
    }
}
