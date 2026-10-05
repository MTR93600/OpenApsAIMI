package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision

/**
 * From [runMealHyperBasalBoostTickStage] through [runInsulinReqActivityRelaxAndMicrobolusStage].
 * `nightbis` is the flag already set by the tick clock. This slice does not read the wall hour.
 */
internal interface AimiTickMealNgrCalls {
    fun mealHyper(
        basal: Double,
        profileCurrentBasal: Double,
        isMealAdvisorOneShot: Boolean,
        targetBg: Double,
        estimatedCarbs: Double,
        estimatedCarbsTimeMs: Long,
    ): AimiTickMealHyperStep

    fun applyOverlay(overlayRate: Double?, deliverAt: Long): AimiTickMealBoost
    fun appendAutodriveSummary()
    fun csf(sens: Double, minDelta: Double, bgi: Double, sensitivityRatio: Double): AimiTickCsf
    fun carbsGate(
        csf: Double,
        slopeFromDeviations: Double,
        sens: Double,
        bgi: Double,
        deviation: Int,
        targetBg: Double,
        maxBg: Double,
    ): AimiTickCarbsGate

    fun mealNgr(
        safetyDecision: SafetyDecision,
        forcedBasalMealModes: Double,
        maxIobLimit: Double,
        basal: Double,
        smbToGive: Float,
        targetBg: Double,
    ): AimiTickMealNgrStep

    fun maxIob(
        mealModeActive: Boolean,
        maxIobLimit: Double,
        safetyDecision: SafetyDecision,
        basal: Double,
        targetBg: Double,
    ): AimiTickMaxIobStep

    fun insulinReq(
        smbToGive: Float,
        allowMealHighIob: Boolean,
        mealHighIobDamping: Double,
        maxIobLimit: Double,
        safetyDecision: SafetyDecision,
        enableSmb: Boolean,
        isMealActive: Boolean,
        basalBoostApplied: Boolean,
        basalBoostSource: String?,
    )
}

internal sealed class AimiTickMealHyperStep {
    class ReturnEarly(val rT: RT) : AimiTickMealHyperStep()
    class Continue(val overlayRate: Double?) : AimiTickMealHyperStep()
}

internal data class AimiTickMealBoost(val applied: Boolean, val source: String?)

internal data class AimiTickCsf(val csf: Double, val slopeFromDeviations: Double)

internal data class AimiTickCarbsSafety(
    val forcedBasalMealModes: Double,
    val forcedBasal: Double,
    val enableSmb: Boolean,
    val mealModeActive: Boolean,
    val zeroSinceMin: Int,
    val minutesSinceLastChange: Int,
    val safetyDecision: SafetyDecision,
)

internal sealed class AimiTickCarbsGate {
    class ReturnEarly(val rT: RT) : AimiTickCarbsGate()
    class Continue(val stage: AimiTickCarbsSafety) : AimiTickCarbsGate()
}

internal data class AimiTickMealNgrContinue(
    val isMealActive: Boolean,
    val runtimeMinValue: Int,
    val maxIobLimit: Double,
    val basal: Double,
    val smbToGive: Float,
)

internal sealed class AimiTickMealNgrStep {
    class ReturnEarly(val rT: RT) : AimiTickMealNgrStep()
    class Continue(val stage: AimiTickMealNgrContinue) : AimiTickMealNgrStep()
}

internal sealed class AimiTickMaxIobStep {
    class ReturnEarly(val rT: RT) : AimiTickMaxIobStep()
    class Continue(val allowMealHighIob: Boolean, val mealHighIobDamping: Double) : AimiTickMaxIobStep()
}

internal sealed class AimiTickMealNgrOutcome {
    class ReturnEarly(val rT: RT) : AimiTickMealNgrOutcome()
    class Continue(
        val basal: Double,
        val maxIobLimit: Double,
        val smbToGive: Float,
        val isMealActive: Boolean,
        val runtimeMinValue: Int,
        val forcedBasalMealModes: Double,
        val forcedBasal: Double,
        val enableSmb: Boolean,
        val zeroSinceMin: Int,
        val minutesSinceLastChange: Int,
        val safetyDecision: SafetyDecision,
        val allowMealHighIob: Boolean,
        val mealHighIobDamping: Double,
    ) : AimiTickMealNgrOutcome()
}

internal fun decideDetermineBasalTickMealNgr(
    basalIn: Double,
    maxIobLimitIn: Double,
    smbToGiveIn: Float,
    profileCurrentBasal: Double,
    isMealAdvisorOneShot: Boolean,
    targetBg: Double,
    maxBg: Double,
    estimatedCarbs: Double,
    estimatedCarbsTimeMs: Long,
    deliverAt: Long,
    sens: Double,
    minDelta: Double,
    bgi: Double,
    deviation: Int,
    sensitivityRatio: Double,
    calls: AimiTickMealNgrCalls,
): AimiTickMealNgrOutcome {
    val boost = when (
        val stage = calls.mealHyper(
            basal = basalIn,
            profileCurrentBasal = profileCurrentBasal,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            targetBg = targetBg,
            estimatedCarbs = estimatedCarbs,
            estimatedCarbsTimeMs = estimatedCarbsTimeMs,
        )
    ) {
        is AimiTickMealHyperStep.ReturnEarly -> return AimiTickMealNgrOutcome.ReturnEarly(stage.rT)
        is AimiTickMealHyperStep.Continue -> calls.applyOverlay(stage.overlayRate, deliverAt)
    }
    calls.appendAutodriveSummary()
    val csf = calls.csf(sens, minDelta, bgi, sensitivityRatio)
    val carbs = when (
        val gate = calls.carbsGate(
            csf = csf.csf,
            slopeFromDeviations = csf.slopeFromDeviations,
            sens = sens,
            bgi = bgi,
            deviation = deviation,
            targetBg = targetBg,
            maxBg = maxBg,
        )
    ) {
        is AimiTickCarbsGate.ReturnEarly -> return AimiTickMealNgrOutcome.ReturnEarly(gate.rT)
        is AimiTickCarbsGate.Continue -> gate.stage
    }
    val meal = when (
        val mealNgr = calls.mealNgr(
            safetyDecision = carbs.safetyDecision,
            forcedBasalMealModes = carbs.forcedBasalMealModes,
            maxIobLimit = maxIobLimitIn,
            basal = basalIn,
            smbToGive = smbToGiveIn,
            targetBg = targetBg,
        )
    ) {
        is AimiTickMealNgrStep.ReturnEarly -> return AimiTickMealNgrOutcome.ReturnEarly(mealNgr.rT)
        is AimiTickMealNgrStep.Continue -> mealNgr.stage
    }
    val maxIob = when (
        val gate = calls.maxIob(
            mealModeActive = carbs.mealModeActive,
            maxIobLimit = meal.maxIobLimit,
            safetyDecision = carbs.safetyDecision,
            basal = meal.basal,
            targetBg = targetBg,
        )
    ) {
        is AimiTickMaxIobStep.ReturnEarly -> return AimiTickMealNgrOutcome.ReturnEarly(gate.rT)
        is AimiTickMaxIobStep.Continue -> gate
    }
    calls.insulinReq(
        smbToGive = meal.smbToGive,
        allowMealHighIob = maxIob.allowMealHighIob,
        mealHighIobDamping = maxIob.mealHighIobDamping,
        maxIobLimit = meal.maxIobLimit,
        safetyDecision = carbs.safetyDecision,
        enableSmb = carbs.enableSmb,
        isMealActive = meal.isMealActive,
        basalBoostApplied = boost.applied,
        basalBoostSource = boost.source,
    )
    return AimiTickMealNgrOutcome.Continue(
        basal = meal.basal,
        maxIobLimit = meal.maxIobLimit,
        smbToGive = meal.smbToGive,
        isMealActive = meal.isMealActive,
        runtimeMinValue = meal.runtimeMinValue,
        forcedBasalMealModes = carbs.forcedBasalMealModes,
        forcedBasal = carbs.forcedBasal,
        enableSmb = carbs.enableSmb,
        zeroSinceMin = carbs.zeroSinceMin,
        minutesSinceLastChange = carbs.minutesSinceLastChange,
        safetyDecision = carbs.safetyDecision,
        allowMealHighIob = maxIob.allowMealHighIob,
        mealHighIobDamping = maxIob.mealHighIobDamping,
    )
}
