package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiLegacySmbCapMath
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance

/** Result of `applyPkpdAbsorptionGuardOncePerTick`. The Android body keeps the multiply and its logs. */
internal data class AimiPkpdGuardApply(
    val smbOut: Float,
    val skippedDuplicate: Boolean,
    val multiplicationApplied: Boolean,
    val guardReason: String?,
    val effectiveFactor: Double,
)

internal fun interface AimiPkpdAbsorptionGuard {
    fun apply(smbIn: Float, reason: StringBuilder): AimiPkpdGuardApply
}

internal data class AimiMealCorrectionView(
    val redCarpetEligible: Boolean,
    val summary: String,
)

internal fun interface AimiMealCorrectionContext {
    fun resolve(mealData: MealData, bgMgdl: Double, deltaMgdlPer5: Double, shortAvgDeltaMgdlPer5: Double): AimiMealCorrectionView
}

internal fun interface AimiAuthoritativeEventual {
    fun bg(fallback: Double): Double
}

internal fun interface AimiAuthoritativeMinPred {
    fun bg(rT: RT, rawMinPred: Double?): Double?
}

internal fun interface AimiMinPredWiring {
    fun bg(rawMinPred: Double?): Double?
}

internal interface AimiPkpdGuardState {
    fun maxSmb(): Double
    fun maxSmbHb(): Double
    fun maxIob(): Double
    fun memberIob(): Double
    fun endoSmbMult(): Double
    fun bindingDraft(): SmbBindingTrace.Draft
    fun setBindingDraft(value: SmbBindingTrace.Draft)
    fun criticalSafetyZeroed(): Boolean
    fun endogenousCounterRegulatory(): Boolean
    fun mealAbsorptionPhase(): MealAbsorptionPhase
}

internal data class AimiPkpdGuardEndoRedCarpetSmbStage(
    val smbToGive: Float,
    val intervalsmb: Int,
)

/**
 * `runPkpdGuardEndoDampenRedCarpetAndCapSmb`.
 *
 * The absorption guard, the meal-correction context and the dose-facing predictions stay on Android
 * and are called at the same lines. This function has no swallowed [Exception].
 */
internal fun decidePkpdGuardEndoDampenRedCarpetAndCapSmb(
    ctx: AimiTickContext,
    rT: RT,
    finalSmb: Float,
    isExplicitAdvisorRun: Boolean,
    isMealAdvisorOneShot: Boolean,
    isConfirmedHighRiseLocal: Boolean,
    bg: Double,
    delta: Float,
    shortAvgDelta: Float,
    predictedBg: Float,
    eventualBG: Double,
    targetBg: Double,
    honeymoon: Boolean,
    mealTime: Boolean,
    bfastTime: Boolean,
    lunchTime: Boolean,
    dinnerTime: Boolean,
    highCarbTime: Boolean,
    snackTime: Boolean,
    intervalsmb: Int,
    smbToGive: Float,
    iob: Float,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    state: AimiPkpdGuardState,
    absorptionGuard: AimiPkpdAbsorptionGuard,
    mealCorrection: AimiMealCorrectionContext,
    eventual: AimiAuthoritativeEventual,
    minPred: AimiAuthoritativeMinPred,
    minPredWiring: AimiMinPredWiring,
): AimiPkpdGuardEndoRedCarpetSmbStage {
    var smbToGiveLocal = smbToGive
    var intervalsmbLocal = intervalsmb

    val anyMealModeForGuard = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
    val isAggressivePriorityContext = isMealAdvisorOneShot || anyMealModeForGuard || isConfirmedHighRiseLocal
    val pkpdReliefEnabled = preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
    val redCarpetRestoreThresholdPref = preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold).coerceIn(0.50, 0.95).toFloat()
    val currentMaxSmb = AimiLegacySmbCapMath.currentMaxSmb(
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        bg = bg,
        honeymoon = honeymoon,
        slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
        mealLunchDinnerOrHc = mealTime || lunchTime || dinnerTime || highCarbTime,
        maxSmb = state.maxSmb(),
        maxSmbHb = state.maxSmbHb(),
    )
    val iobRelief = AimiLegacySmbCapMath.iobRelief(
        pkpdReliefEnabled = pkpdReliefEnabled,
        isAggressivePriorityContext = isAggressivePriorityContext,
        maxIob = state.maxIob(),
        priorityMaxIobFactor = preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor).coerceIn(1.0, 1.6),
        priorityMaxIobExtraU = preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU).coerceIn(0.0, 5.0),
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        predictedBg = predictedBg.toDouble(),
        eventualBg = eventualBG,
    )
    val effectiveMaxIobForDebridage = iobRelief.effectiveMaxIobForDebridage
    val pkpdGuardInput = smbToGiveLocal
    val pkpdGuardApply = absorptionGuard.apply(smbToGiveLocal, rT.reason)
    smbToGiveLocal = pkpdGuardApply.smbOut
    state.setBindingDraft(
        if (pkpdGuardApply.skippedDuplicate) {
            state.bindingDraft().appendStage(
                "PKPD_GUARD_SKIPPED_DUPLICATE",
                pkpdGuardInput.toDouble(),
                pkpdGuardInput.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "OBSERVATION",
            )
        } else {
            state.bindingDraft().copy(
                pkpdBeforeU = state.bindingDraft().pkpdBeforeU ?: pkpdGuardInput.toDouble(),
                pkpdAfterU = state.bindingDraft().pkpdAfterU ?: smbToGiveLocal.toDouble(),
            ).appendStage(
                "PKPD_GUARD",
                pkpdGuardInput.toDouble(),
                smbToGiveLocal.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "GUARD",
            )
        },
    )
    intervalsmbLocal = intervalsmb
    if (pkpdGuardApply.skippedDuplicate) {
        consoleLog.add("PKPD_GUARD_SKIP: already applied this tick (e.g. Autodrive V3 finalize)")
    } else if (pkpdGuardApply.multiplicationApplied) {
        pkpdGuardApply.guardReason?.let { guardReason ->
            rT.reason.append(" | $guardReason x${aimiFmt2(pkpdGuardApply.effectiveFactor)}")
        }
    }
    iobRelief.logs.forEach { consoleLog.add(it) }

    val endoSmbMult = state.endoSmbMult()
    if (endoSmbMult < 1.0) {
        val beforeEndo = smbToGiveLocal
        smbToGiveLocal = (smbToGiveLocal * endoSmbMult.toFloat()).coerceAtLeast(0f)
        state.setBindingDraft(
            state.bindingDraft().appendStage(
                "ENDO_DAMPEN",
                beforeEndo.toDouble(),
                smbToGiveLocal.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "DAMPEN",
            ),
        )
        if (smbToGiveLocal < beforeEndo) {
            consoleLog.add("SMB_ENDO_DAMPEN: ${aimiFmt2(beforeEndo)}U → ${aimiFmt2(smbToGiveLocal)}U (x${aimiFmt2(endoSmbMult)})")
            rT.reason.append(" | EndoDampen x${aimiFmt2(endoSmbMult)}")
        }
    }

    val beforeCap = smbToGiveLocal

    val isExplicitAction = isMealAdvisorOneShot
    val implicitMealCorrection = mealCorrection.resolve(
        mealData = ctx.mealData,
        bgMgdl = bg,
        deltaMgdlPer5 = delta.toDouble(),
        shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
    )
    val proposedUnits = finalSmb

    val stackingEvalV3 = InsulinStackingStance.evaluate(
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        targetBg = targetBg,
        iob = iob.toDouble(),
        maxIob = state.maxIob(),
        eventualBg = eventual.bg(eventualBG).takeIf { it > 1.0 && it.isFinite() },
        minPredBg = minPredWiring.bg(
            minPred.bg(rT, minPredictedAcrossCurves(rT.predBGs)),
        ),
        trajectoryEnergy = rT.trajectoryEnergy,
        isExplicitUserAction = isExplicitAction,
        enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
        mealPriorityContext = isAggressivePriorityContext,
        endogenousCounterRegulatory = state.endogenousCounterRegulatory(),
        mealAbsorptionPhase = state.mealAbsorptionPhase(),
        mealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime,
    )
    val carpet = AimiLegacySmbCapMath.redCarpetOrCap(
        smbAfterGuards = smbToGiveLocal,
        proposedUnits = proposedUnits,
        finalSmb = finalSmb.toDouble(),
        isExplicitAction = isExplicitAction,
        anyMealMode = anyMealModeForGuard,
        redCarpetEligible = implicitMealCorrection.redCarpetEligible,
        mealSummary = implicitMealCorrection.summary,
        isConfirmedHighRise = isConfirmedHighRiseLocal,
        mealCob = ctx.mealData.mealCOB,
        delta = delta.toDouble(),
        bg = bg,
        shortAvgDelta = shortAvgDelta.toDouble(),
        pkpdReliefEnabled = pkpdReliefEnabled,
        isAggressivePriorityContext = isAggressivePriorityContext,
        restoreThresholdPref = redCarpetRestoreThresholdPref,
        criticalSafetyZeroed = state.criticalSafetyZeroed(),
        suppressRedCarpet = stackingEvalV3.suppressRedCarpetRestore,
        suppressSummary = stackingEvalV3.summary,
        currentMaxSmb = currentMaxSmb,
        maxSmbHb = state.maxSmbHb(),
        effectiveMaxIob = effectiveMaxIobForDebridage,
        iobForCap = iob.toDouble(),
        memberIob = state.memberIob(),
    )
    smbToGiveLocal = carpet.units
    carpet.logs.forEach { consoleLog.add(it) }
    carpet.reasonCap?.let { rT.reason.append(it) }
    state.setBindingDraft(
        state.bindingDraft().copy(
            safetyNetBaseLimitU = state.bindingDraft().safetyNetBaseLimitU ?: currentMaxSmb,
            redCarpetBeforeU = state.bindingDraft().redCarpetBeforeU ?: beforeCap.toDouble(),
            redCarpetAfterU = state.bindingDraft().redCarpetAfterU ?: smbToGiveLocal.toDouble(),
        ).appendStage(
            "LEGACY_RED_CARPET_MAX_SMB_IOB",
            beforeCap.toDouble(),
            smbToGiveLocal.toDouble(),
            currentMaxSmb,
            phase = "LEGACY_GUARD",
            kind = "COMPOSITE",
        ),
    )

    return AimiPkpdGuardEndoRedCarpetSmbStage(
        smbToGive = smbToGiveLocal,
        intervalsmb = intervalsmbLocal,
    )
}
