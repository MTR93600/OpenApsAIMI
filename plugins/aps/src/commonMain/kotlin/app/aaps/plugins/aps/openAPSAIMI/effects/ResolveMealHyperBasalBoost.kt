package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.physio.EndogenousBasalBridgePolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/** Lectures de champs, chacune au moment où la branche les consulte. */
internal interface AimiMealHyperFields {
    fun snackTime(): Boolean
    fun snackRunTime(): Long
    fun delta(): Float
    fun mealTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun highCarbTime(): Boolean
    fun bfastTime(): Boolean
    fun mealRuntime(): Long
    fun lunchRuntime(): Long
    fun dinnerRuntime(): Long
    fun highCarbRunTime(): Long
    fun bfastRuntime(): Long
    fun bg(): Double
    fun shortAvgDelta(): Double
    fun mealAbsorption(): MealAbsorptionPhaseEngine.Output?
    fun cob(): Float
    fun phase(): PhysiologicalPhase?
    fun hyperReleaseActive(): Boolean
    fun aggression(): CorrectionAggressionGate.Decision?
    fun basalFirstActive(): Boolean
    fun fragileBg(): Boolean
    fun fastingTime(): Boolean
}

/** `setTempBasal`. Le corps reste dans la coquille Android. */
internal fun interface AimiMealHyperTempBasal {
    fun set(
        rate: Double,
        durationMin: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currentTemp: CurrentTemp,
        overrideSafetyLimits: Boolean,
        adaptiveMultiplier: Double,
    ): RT
}

/** `dateUtil.now` pour l’âge de la phase d’absorption. */
internal fun interface AimiMealHyperClock {
    fun nowMs(): Long
}

internal sealed class AimiMealHyperBasalBoostOutcome {
    data class ContinueWithOptionalRate(val rate: Double?) : AimiMealHyperBasalBoostOutcome()
    data class CompleteWithTempBasal(val rT: RT) : AimiMealHyperBasalBoostOutcome()
}

/**
 * `resolveMealHyperBasalBoostOutcome`.
 * Le débit vient des mêmes formules. `setTempBasal` est appelé au même endroit, via [tempBasal].
 */
internal fun decideMealHyperBasalBoost(
    profile: OapsProfileAimi,
    rT: RT,
    basal: Double,
    profileCurrentBasal: Double,
    isMealAdvisorOneShot: Boolean,
    targetBg: Double,
    timeSinceEstimateMin: Double,
    estimatedCarbs: Double,
    currentTemp: CurrentTemp,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    fields: AimiMealHyperFields,
    basalCap: AimiBasalCap,
    tempBasal: AimiMealHyperTempBasal,
    clock: AimiMealHyperClock,
): AimiMealHyperBasalBoostOutcome {
    val mealModesMaxBasal = preferences.get(DoubleKey.meal_modes_MaxBasal)
    val aggressionDecision = fields.aggression()
    return when {
        isMealAdvisorOneShot -> {
            val safeMax = if (mealModesMaxBasal > 0.1) mealModesMaxBasal else profile.max_basal
            val rawRate = mealHyperRate(basal, safeMax, 1.3, "Meal Advisor Trigger (One-Shot)", currentTemp, rT, overrideSafety = true)
            val boostedRate = basalCap.capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "MealAdvisorOneShot")
            AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(
                tempBasal.set(boostedRate, 30, profile, rT, currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0),
            )
        }
        fields.snackTime() && fields.snackRunTime() in 0..30 && fields.delta() < 15 -> {
            val rawRate = mealHyperRate(basal, profileCurrentBasal, 4.0, "AI Force basal because Snack Time ${fields.snackRunTime()}.", currentTemp, rT, overrideSafety = true)
            val boostedRate = basalCap.capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "SnackTimeBoost")
            AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(
                tempBasal.set(boostedRate, 30, profile, rT, currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0),
            )
        }
        (fields.mealTime() || fields.lunchTime() || fields.dinnerTime() || fields.highCarbTime() || fields.bfastTime()) &&
            (listOf(fields.mealRuntime(), fields.lunchRuntime(), fields.dinnerRuntime(), fields.highCarbRunTime(), fields.bfastRuntime()).maxOrNull()?.toInt() ?: 0) in 0..30 -> {
            val safeMax = if (mealModesMaxBasal > 0.1) mealModesMaxBasal else profileCurrentBasal * 5.0
            val rawRate = mealHyperRate(basal, safeMax, 1.0, "Meal Boost 30min (Force MaxBasal)", currentTemp, rT, overrideSafety = true)
            val boostedRate = basalCap.capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "MealBoost30m")
            AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(
                tempBasal.set(boostedRate, 30, profile, rT, currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0),
            )
        }
        (
            fields.mealTime() || fields.lunchTime() || fields.dinnerTime() || fields.highCarbTime() ||
                fields.bfastTime() || fields.snackTime() || (timeSinceEstimateMin <= 120 && estimatedCarbs > 10.0)
            ) -> {
            val runTime = listOf(
                fields.mealRuntime(),
                fields.lunchRuntime(),
                fields.dinnerRuntime(),
                fields.highCarbRunTime(),
                fields.bfastRuntime(),
                fields.snackRunTime(),
            ).maxOrNull()?.toInt() ?: timeSinceEstimateMin.toInt()
            val target = targetBg
            val rocketStart = fields.delta() > 5.0f || fields.bg() > targetBg + 40
            val safeMax = if (rocketStart) profile.max_basal else if (mealModesMaxBasal > 0) mealModesMaxBasal else profileCurrentBasal * 2.0
            val boostedRate = AimiTickPolicyMath.adjustBasalForMealHyper(
                suggestedBasalUph = profileCurrentBasal,
                bg = fields.bg(),
                targetBg = target,
                delta = fields.delta().toDouble(),
                shortAvgDelta = fields.shortAvgDelta(),
                isMealModeActive = true,
                minutesSinceMealStart = runTime,
                mealMaxBasalUph = safeMax,
            )
            val optionalRate = if (boostedRate > profileCurrentBasal * 1.05) {
                mealHyperRate(basal, profileCurrentBasal, boostedRate / profileCurrentBasal, "Post-Meal Boost active ($runTime m)", currentTemp, rT)
            } else {
                null
            }
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
        }
        aggressionDecision?.tier == CorrectionAggressionGate.Tier.REBOUND_GUARD &&
            aggressionDecision.allowGlobalHyperKicker == false &&
            fields.bg() < targetBg + CorrectionAggressionGate.REBOUND_BG_MARGIN_MGDL &&
            fields.delta() >= 0.0f -> {
            val bridgeRate = mealHyperRate(
                basal,
                profileCurrentBasal,
                1.5,
                "${CorrectionAggressionGate.LOG_PREFIX}: post-hypo TBR bridge (REBOUND_GUARD)",
                currentTemp,
                rT,
            )
            consoleLog.add(
                "${CorrectionAggressionGate.LOG_PREFIX}: rebound basal bridge " +
                    "${aimiFmt2(bridgeRate)} U/h (no Global Hyper Kicker)",
            )
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
                if (bridgeRate > profileCurrentBasal * 1.05) bridgeRate else null,
            )
        }
        fields.mealAbsorption()?.phase?.isActive == true &&
            (
                fields.mealAbsorption()?.phase == MealAbsorptionPhase.FIRST_WAVE ||
                    fields.mealAbsorption()?.phase == MealAbsorptionPhase.SECOND_WAVE ||
                    fields.mealAbsorption()?.phase == MealAbsorptionPhase.INTER_WAVE ||
                    fields.mealAbsorption()?.phase == MealAbsorptionPhase.PEAK_CORRECTION
                ) -> {
            val runTime: Int = MealAbsorptionMemory.lastActiveAtMs.takeIf { it > 0L }?.let {
                ((clock.nowMs() - it) / 60_000L).toInt().coerceAtLeast(0)
            } ?: listOf(
                fields.mealRuntime(),
                fields.lunchRuntime(),
                fields.dinnerRuntime(),
                fields.highCarbRunTime(),
                fields.bfastRuntime(),
                fields.snackRunTime(),
            ).maxOrNull()?.toInt() ?: 0
            val target = targetBg
            val rocketStart = fields.delta() > 5.0f || fields.bg() > targetBg + 40
            val safeMax = if (rocketStart) profile.max_basal else if (mealModesMaxBasal > 0) mealModesMaxBasal else profileCurrentBasal * 2.0
            val boostedRate = AimiTickPolicyMath.adjustBasalForMealHyper(
                suggestedBasalUph = profileCurrentBasal,
                bg = fields.bg(),
                targetBg = target,
                delta = fields.delta().toDouble(),
                shortAvgDelta = fields.shortAvgDelta(),
                isMealModeActive = true,
                minutesSinceMealStart = runTime,
                mealMaxBasalUph = safeMax,
            )
            val optionalRate = if (boostedRate > profileCurrentBasal * 1.05) {
                mealHyperRate(
                    basal,
                    profileCurrentBasal,
                    boostedRate / profileCurrentBasal,
                    "Meal absorption ${fields.mealAbsorption()?.phase?.name} ($runTime m)",
                    currentTemp,
                    rT,
                )
            } else {
                null
            }
            consoleLog.add(
                "🍽️ MEAL_ABSORPTION_BASAL: phase=${fields.mealAbsorption()?.phase?.name} " +
                    "rate=${optionalRate?.let { r -> aimiFmt2(r) } ?: "skip"}",
            )
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
        }
        fields.phase() == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY &&
            fields.cob() < 1.0f &&
            estimatedCarbs < 1.0 -> {
            val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
            val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
            val bridge = EndogenousBasalBridgePolicy.computeBridgeRateUph(
                bgMgdl = fields.bg(),
                targetBgMgdl = targetBg,
                isfMgdlPerU = profile.sens,
                profileBasalUph = profileCurrentBasal,
                maxBasalUph = safeMax,
            )
            val optionalRate = bridge?.let {
                mealHyperRate(
                    basal,
                    profileCurrentBasal,
                    it / profileCurrentBasal,
                    "Endogenous basal bridge (R_HGP)",
                    currentTemp,
                    rT,
                )
            }
            consoleLog.add(
                "🌅 ENDOGENOUS_BRIDGE: rate=${optionalRate?.let { r -> aimiFmt2(r) } ?: "skip"} " +
                    "ISF=${aimiFmt1(profile.sens)} profileBasal=${aimiFmt2(profileCurrentBasal)}",
            )
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
        }
        run {
            val aggression = fields.aggression()
            val htrActive = fields.hyperReleaseActive()
            val endogenousActive = fields.phase() == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
            val allowHyper = aggression?.allowGlobalHyperKicker == true &&
                (fields.delta() >= 0.3 || fields.shortAvgDelta() >= 0.2) &&
                !htrActive &&
                !endogenousActive
            if (htrActive && aggression?.allowGlobalHyperKicker == true) {
                consoleLog.add("🚀 HTR: Global Hyper Kicker skipped (trajectory SMB release active)")
            }
            allowHyper
        } -> {
            val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
            val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
            val scaleCap = fields.aggression()?.maxBasalScaleCap ?: 10.0
            val boostedRate = AimiTickPolicyMath.adjustBasalForGeneralHyper(
                suggestedBasalUph = profileCurrentBasal,
                bg = fields.bg(),
                targetBg = targetBg,
                delta = fields.delta().toDouble(),
                shortAvgDelta = fields.shortAvgDelta(),
                maxBasalConfig = safeMax,
                maxScaleCap = scaleCap,
            )
            val tag = fields.aggression()?.tier?.name ?: "n/a"
            val optionalRate = if (boostedRate > profileCurrentBasal * 1.1) {
                mealHyperRate(
                    basal,
                    profileCurrentBasal,
                    boostedRate / profileCurrentBasal,
                    "Global Hyper Kicker ($tag / ${CorrectionAggressionGate.LOG_PREFIX})",
                    currentTemp,
                    rT,
                    overrideSafety = true,
                )
            } else {
                null
            }
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
        }
        fields.basalFirstActive() && !fields.fragileBg() && fields.bg() > targetBg -> {
            val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
            val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
            AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
                mealHyperRate(basal, safeMax, 1.4, "Prudent Compensation (SMB blocked)", currentTemp, rT),
            )
        }
        fields.fastingTime() -> AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
            mealHyperRate(
                profileCurrentBasal,
                profileCurrentBasal,
                fields.delta().coerceAtLeast(0.0f).toDouble(),
                "AI Force basal because fastingTime",
                currentTemp,
                rT,
            ),
        )
        else -> AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(null)
    }
}

private fun mealHyperRate(
    basal: Double,
    currentBasal: Double,
    multiplier: Double,
    reason: String,
    currentTemp: CurrentTemp,
    rT: RT,
    overrideSafety: Boolean = false,
): Double {
    rT.reason.append("${currentTemp.duration}m@${aimiFmt2(currentTemp.rate)} $reason")
    val rawRate = if (overrideSafety || basal == 0.0) currentBasal * multiplier else AimiTickPolicyMath.roundBasal(basal * multiplier)
    return rawRate.coerceAtLeast(0.0)
}
