package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Reusable
class BasalDecisionEngine @Inject constructor(
    private val context: Context,
    private val aimiAdaptiveBasal: AIMIAdaptiveBasal,
    private val basalPlanner: BasalPlanner
) {

    data class Input(
        val bg: Double,
        val profileCurrentBasal: Double,
        val basalEstimate: Double,
        val tdd7P: Double,
        val tdd7Days: Double,
        val variableSensitivity: Double,
        val predictedBg: Double,
        val eventualBg: Double,
        val iob: Double,
        val maxIob: Double,
        val allowMealHighIob: Boolean,
        val safetyDecision: SafetyDecision,
        val mealData: MealData,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val combinedDelta: Double,
        val bgAcceleration: Double,
        val slopeFromMaxDeviation: Double,
        val slopeFromMinDeviation: Double,
        val forcedBasal: Double,
        val forcedMealActive: Boolean,
        val isMealActive: Boolean,
        val runtimeMinValue: Int,
        val snackTime: Boolean,
        val snackRuntimeMin: Int,
        val fastingTime: Boolean,
        val sportTime: Boolean,
        val honeymoon: Boolean,
        val pregnancyEnable: Boolean,
        val mealTime: Boolean,
        val mealRuntimeMin: Int,
        val bfastTime: Boolean,
        val bfastRuntimeMin: Int,
        val lunchTime: Boolean,
        val lunchRuntimeMin: Int,
        val dinnerTime: Boolean,
        val dinnerRuntimeMin: Int,
        val highCarbTime: Boolean,
        val highCarbRuntimeMin: Int,
        val timenow: Int,
        val sixAmHour: Int,
        val recentSteps5Minutes: Int,
        val nightMode: Boolean,
        val modesCondition: Boolean,
        val autodrive: Boolean,
        val currentTemp: CurrentTemp,
        val glucoseStatus: GlucoseStatusAIMI?,
        val featuresCombinedDelta: Double?,
        val smbToGive: Double,
        val zeroSinceMin: Int,
        val minutesSinceLastChange: Int
    )

    data class Helpers(
        val calculateRate: (Double, Double, Double, String) -> Double,
        val calculateBasalRate: (Double, Double, Double) -> Double,
        val detectMealOnset: (Float, Float, Float) -> Boolean,
        val round: (Double, Int) -> Double
    )

    data class Decision(
        val rate: Double,
        val duration: Int,
        val overrideSafety: Boolean
    )

    fun decide(
        input: Input,
        rT: RT,
        helpers: Helpers
    ): Decision {
        // ===== 0) BasalPlanner d'abord (avec tes modèles LoopContext / BgSnapshot / PumpCaps etc.) =====
        run {
            val gs = input.glucoseStatus
            val bgSnap = BgSnapshot(
                mgdl = gs?.glucose ?: input.bg,
                delta5 = gs?.delta ?: input.delta,
                shortAvgDelta = gs?.shortAvgDelta ?: input.shortAvgDelta,
                longAvgDelta = gs?.longAvgDelta ?: input.longAvgDelta,
                accel = gs?.bgAcceleration ?: input.bgAcceleration,
                r2 = gs?.corrSqu,
                parabolaMinutes = gs?.parabolaMinutes,
                combinedDelta = input.featuresCombinedDelta ?: input.combinedDelta,
                epochMillis = System.currentTimeMillis()
            )

            // Capacités pompe prudentes par défaut (tu peux passer les vraies depuis DetermineBasalAIMI2 plus tard)
            val pumpCaps = PumpCaps(
                basalStep = 0.05,
                bolusStep = 0.05,
                minDurationMin = 30,
                maxBasal = max(input.profileCurrentBasal * 1.6, input.profileCurrentBasal),
                maxSmb = 3.0
            )

            val modes = ModeState(
                autodrive = input.autodrive,
                meal = input.mealTime || input.isMealActive,
                breakfast = input.bfastTime,
                lunch = input.lunchTime,
                dinner = input.dinnerTime,
                highCarb = input.highCarbTime,
                snack = input.snackTime,
                sleep = input.nightMode
            )

            val profile = LoopProfile(
                targetMgdl = input.predictedBg,              // non critique ici
                isfMgdlPerU = input.variableSensitivity,    // dispo si tu veux exploiter plus tard
                basalProfileUph = input.profileCurrentBasal
            )

            val ctx = LoopContext(
                bg = bgSnap,
                iobU = input.iob,
                cobG = 0.0, // (non utilisé ici)
                profile = profile,
                pump = pumpCaps,
                modes = modes,
                settings = AimiSettings(
                    smbIntervalMin = 5,
                    wCycleEnabled = true
                ),
                tdd24hU = input.tdd7Days,   // ou tdd 24h réel si dispo
                eventualBg = input.eventualBg,
                nowEpochMillis = System.currentTimeMillis()
            )

            basalPlanner.plan(ctx)?.let { plan ->
                rT.duration = plan.durationMin
                rT.reason.append(" | BasalPlanner: ").append(plan.reason)
                return Decision(
                    rate = plan.rateUph,
                    duration = plan.durationMin,
                    overrideSafety = false
                )
            }
        }

        // ===== 1) Calculs auxiliaires gardés (inchangés) =====
        val basalAdjustmentFactor = interpolateBasal(input.bg)
        val finalBasalRate = computeFinalBasal(
            input.bg,
            input.tdd7P.toFloat(),
            input.tdd7Days.toFloat(),
            input.basalEstimate.toFloat()
        )

        var overrideSafety = false
        var chosenRate: Double? = null

        val lastTempIsZero = input.currentTemp.rate == 0.0
        val zeroSinceMin = input.zeroSinceMin
        val minutesSinceLastChange = input.minutesSinceLastChange

        // ===== 2) Délégation AIMIAdaptiveBasal (si GS dispo) =====
        input.glucoseStatus?.let { status ->
            val inAimi = AIMIAdaptiveBasal.Input(
                bg = status.glucose,
                delta = status.delta,
                shortAvgDelta = status.shortAvgDelta,
                longAvgDelta = status.longAvgDelta,
                accel = status.bgAcceleration,
                r2 = status.corrSqu,
                parabolaMin = status.parabolaMinutes,
                combinedDelta = input.featuresCombinedDelta ?: 0.0,
                profileBasal = input.profileCurrentBasal,
                lastTempIsZero = lastTempIsZero,
                zeroSinceMin = zeroSinceMin,
                minutesSinceLastChange = minutesSinceLastChange
            )
            val aimiDecision = aimiAdaptiveBasal.suggest(inAimi)
            aimiDecision.rateUph?.let { candidate ->
                val dur = aimiDecision.durationMin
                if (chosenRate == null) {
                    chosenRate = candidate
                    rT.duration = dur
                    rT.reason.append(" | ").append(aimiDecision.reason)
                } else if (candidate > chosenRate!!) {
                    chosenRate = candidate
                    val currentDuration = rT.duration ?: dur
                    rT.duration = minOf(currentDuration, dur)
                    rT.reason.append(" | override by AIMI+: ").append(aimiDecision.reason)
                }
                val hardCap = 1.8 * input.profileCurrentBasal
                chosenRate = min(chosenRate!!, hardCap)
            }
        }

        // ===== 3) … le reste de ta logique (inchangée) =====
        val inMealFirst30 = input.isMealActive && input.runtimeMinValue in 0..30
        if (input.safetyDecision.basalLS &&
            input.combinedDelta in -1.0..3.0 &&
            input.predictedBg > 130 &&
            input.iob > 0.1 &&
            !inMealFirst30 &&
            !input.forcedMealActive
        ) {
            return Decision(input.profileCurrentBasal, 30, false)
        }

        if (helpers.detectMealOnset(
                input.delta.toFloat(),
                input.predictedBg.toFloat(),
                input.bgAcceleration.toFloat()
            ) &&
            !input.nightMode &&
            input.modesCondition &&
            input.bg > 100 &&
            input.autodrive
        ) {
            chosenRate = input.forcedBasal
            overrideSafety = true
            rT.reason.append(context.getString(R.string.reason_early_meal, input.forcedBasal))
        } else {
            chosenRate = when {
                input.snackTime && input.snackRuntimeMin in 0..30 && input.delta < 10 -> {
                    helpers.calculateRate(input.basalEstimate, input.profileCurrentBasal, 4.0, "SnackTime")
                }
                input.fastingTime -> helpers.calculateRate(input.profileCurrentBasal, input.profileCurrentBasal, input.delta, "FastingTime")
                input.sportTime && input.bg > 169 && input.delta > 4 -> helpers.calculateRate(input.profileCurrentBasal, input.profileCurrentBasal, 1.3, "SportTime")
                input.honeymoon && input.delta in 0.0..6.0 && input.bg in 99.0..141.0 -> helpers.calculateRate(input.profileCurrentBasal, input.profileCurrentBasal, input.delta, "Honeymoon")
                input.bg in 81.0..99.0 && input.delta in 3.0..7.0 && input.honeymoon -> helpers.calculateRate(input.basalEstimate, input.profileCurrentBasal, 1.0, "Honeymoon small-rise")
                input.bg > 120 && input.delta > 0 && input.smbToGive == 0.0 && input.honeymoon -> helpers.calculateRate(input.basalEstimate, input.profileCurrentBasal, 5.0, "Honeymoon corr.")
                else -> chosenRate
            }
        }

        if (chosenRate == null) {
            val predictedLow = input.predictedBg < 80 && input.mealData.slopeFromMaxDeviation <= 0
            val highIobStop = input.iob > input.maxIob && !input.allowMealHighIob
            when {
                predictedLow || highIobStop -> {
                    chosenRate = 0.0
                    overrideSafety = false
                    rT.reason.append(context.getString(R.string.safety_cut_tbr, input.maxIob))
                }
                input.iob > input.maxIob && input.allowMealHighIob -> {
                    chosenRate = max(input.profileCurrentBasal, input.currentTemp.rate)
                    rT.reason.append(context.getString(R.string.reason_meal_hold_profile_basal,
                                                       helpers.round(input.iob, 2), helpers.round(input.maxIob, 2)))
                }
            }
        }

        if (chosenRate == null) {
            when {
                input.bg < 80.0 -> {
                    chosenRate = 0.0
                    rT.reason.append(context.getString(R.string.bg_below_80))
                }
                input.bg in 80.0..90.0 &&
                    input.slopeFromMaxDeviation <= 0 && input.iob > 0.1 && !input.sportTime -> {
                    if (input.delta < -2.0) {
                        chosenRate = 0.0
                        rT.reason.append(context.getString(R.string.bg_80_90_fall))
                    } else {
                        chosenRate = input.profileCurrentBasal * 0.25
                        rT.reason.append("BG 80-90 falling slow: 25%")
                    }
                }
                input.bg in 80.0..90.0 &&
                    input.slopeFromMinDeviation >= 0.3 && input.slopeFromMaxDeviation >= 0 &&
                    input.combinedDelta in -1.0..2.0 && !input.sportTime &&
                    input.bgAcceleration > 0.0 -> {
                    chosenRate = input.profileCurrentBasal * 0.2
                    rT.reason.append(context.getString(R.string.bg_80_90_stable))
                }
                input.bg in 90.0..100.0 &&
                    input.slopeFromMinDeviation <= 0.3 && input.iob > 0.1 && !input.sportTime &&
                    input.bgAcceleration > 0.0 -> {
                    chosenRate = input.profileCurrentBasal * 0.5
                    rT.reason.append("BG 90-100 moderate: 50%")
                }
                input.bg in 90.0..100.0 &&
                    input.slopeFromMinDeviation >= 0.3 && input.combinedDelta in -1.0..2.0 && !input.sportTime &&
                    input.bgAcceleration > 0.0 -> {
                    chosenRate = input.profileCurrentBasal * 0.5
                    rT.reason.append(context.getString(R.string.bg_90_100_slight_gain))
                }
            }
        }

        if (chosenRate == null) {
            if (input.bg > 120 &&
                input.slopeFromMinDeviation in 0.4..20.0 &&
                input.combinedDelta > 1 && !input.sportTime &&
                input.bgAcceleration > 1.0
            ) {
                chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, input.combinedDelta)
                rT.reason.append(context.getString(R.string.slow_rise_proportional_adjustment))
            } else if (input.eventualBg > 110 && !input.sportTime && input.bg > 150 &&
                input.combinedDelta in -2.0..15.0 &&
                input.bgAcceleration > 0.0
            ) {
                chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, basalAdjustmentFactor)
                rT.reason.append(context.getString(R.string.eventual_bg_over_110_hyper_factor))
            }
        }

        if (chosenRate == null) {
            if ((input.timenow in 11..13 || input.timenow in 18..21) &&
                input.iob < 0.8 && input.recentSteps5Minutes < 100 &&
                input.combinedDelta > -1 && input.slopeFromMinDeviation > 0.3 &&
                input.bgAcceleration > 0.0
            ) {
                chosenRate = input.profileCurrentBasal * 1.5
                rT.reason.append(context.getString(R.string.calm_meal_and_timing))
            } else if (input.timenow > input.sixAmHour && input.recentSteps5Minutes > 100) {
                chosenRate = input.profileCurrentBasal * 0.5
                rT.reason.append("Morning activity: 50%")
            } else if (input.timenow <= input.sixAmHour && input.delta > 0 && input.bgAcceleration > 0.0) {
                chosenRate = input.profileCurrentBasal
                rT.reason.append(context.getString(R.string.morning_rise_profile_basal))
            }
        }

        if (chosenRate == null) {
            val trendSignals = listOfNotNull(
                input.delta,
                input.shortAvgDelta,
                input.longAvgDelta,
                input.combinedDelta,
                input.featuresCombinedDelta
            )
            val strongestRise = trendSignals.maxOrNull() ?: Double.NEGATIVE_INFINITY
            if (strongestRise >= 4.0 && input.bg > 120 && !input.sportTime) {
                val multiplier = when {
                    strongestRise >= 8.0 -> 1.8
                    strongestRise >= 6.0 -> 1.6
                    else -> 1.3
                }
                val candidate = helpers.calculateBasalRate(
                    finalBasalRate,
                    input.profileCurrentBasal,
                    multiplier
                )
                val capped = min(candidate, input.profileCurrentBasal * 2.0)
                chosenRate = capped
                rT.reason.append(
                    context.getString(
                        R.string.reason_strong_rise_basal,
                        helpers.round(strongestRise, 1),
                        helpers.round(multiplier, 2)
                    )
                )
            }
        }

        if (chosenRate == null) {
            val windows = listOf(
                input.snackTime to input.snackRuntimeMin,
                input.mealTime to input.mealRuntimeMin,
                input.bfastTime to input.bfastRuntimeMin,
                input.lunchTime to input.lunchRuntimeMin,
                input.dinnerTime to input.dinnerRuntimeMin,
                input.highCarbTime to input.highCarbRuntimeMin
            )
            for ((active, runtimeMin) in windows) {
                if (!active) continue
                if (runtimeMin in 0..30) {
                    chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, 10.0)
                    rT.reason.append(context.getString(R.string.meal_snack_under_30m_basal_10))
                    break
                } else if (runtimeMin in 31..60 && input.delta > 0) {
                    chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, input.delta)
                    rT.reason.append(context.getString(R.string.meal_snack_30_60m_rising_basal_delta))
                    break
                }
            }
        }

        if (chosenRate == null) {
            val isPlateauHigh =
                input.bg > 120 &&
                    abs(input.delta) <= 2.0 &&
                    abs(input.shortAvgDelta) <= 2.0 &&
                    abs(input.longAvgDelta) <= 2.0 &&
                    (input.glucoseStatus?.duraISFminutes ?: 0.0) >= 15.0 &&
                    abs(input.glucoseStatus?.bgAcceleration ?: 0.0) <= 0.2

            if (isPlateauHigh) {
                val err = (input.bg - 120.0).coerceAtLeast(0.0)
                val boostFrac = (err / input.variableSensitivity).coerceIn(0.15, 0.60)
                val candidate = input.profileCurrentBasal * (1.0 + boostFrac)
                val boosted = max(candidate, input.basalEstimate)
                chosenRate = boosted
                rT.reason.append(context.getString(R.string.aimi_plateau_high_boost))
            }
        }

        if (chosenRate == null) {
            when {
                input.eventualBg > 120 && input.delta > 3 -> {
                    chosenRate = helpers.calculateBasalRate(input.basalEstimate, input.profileCurrentBasal, basalAdjustmentFactor)
                    rT.reason.append(context.getString(R.string.eventual_bg_over_180_hyper_basalaimi))
                }
                input.bg > 150 && input.delta in -5.0..1.0 -> {
                    chosenRate = input.profileCurrentBasal * basalAdjustmentFactor
                    rT.reason.append(context.getString(R.string.bg_over_180_stable_basal_factor))
                }
            }
        }

        if (chosenRate == null && input.honeymoon) {
            when {
                input.bg in 140.0..169.0 && input.delta > 0 -> {
                    chosenRate = input.profileCurrentBasal
                    rT.reason.append(context.getString(R.string.honeymoon_bg_140_169_profile))
                }
                input.bg > 170 && input.delta > 0 -> {
                    chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, basalAdjustmentFactor)
                    rT.reason.append(context.getString(R.string.honeymoon_bg_over_170_adjustment))
                }
                input.combinedDelta > 2 && input.bg in 90.0..119.0 -> {
                    chosenRate = input.profileCurrentBasal
                    rT.reason.append(context.getString(R.string.honeymoon_delta_over_2_bg_90_119_profile))
                }
                input.combinedDelta > 0 && input.bg > 110 && input.eventualBg > 120 && input.bg < 160 -> {
                    chosenRate = input.profileCurrentBasal * basalAdjustmentFactor
                    rT.reason.append(context.getString(R.string.honeymoon_mixed_correction))
                }
                input.mealData.slopeFromMaxDeviation > 0 && input.mealData.slopeFromMinDeviation > 0 && input.bg > 110 && input.combinedDelta > 0 -> {
                    chosenRate = input.profileCurrentBasal * basalAdjustmentFactor
                    rT.reason.append(context.getString(R.string.honeymoon_plus_meal_detection))
                }
                input.mealData.slopeFromMaxDeviation in 0.0..0.2 && input.mealData.slopeFromMinDeviation in 0.0..0.5 &&
                    input.bg in 120.0..150.0 && input.delta > 0 -> {
                    chosenRate = input.profileCurrentBasal * basalAdjustmentFactor
                    rT.reason.append(context.getString(R.string.honeymoon_small_slope))
                }
                input.mealData.slopeFromMaxDeviation > 0 && input.mealData.slopeFromMinDeviation > 0 &&
                    input.bg in 100.0..120.0 && input.delta > 0 -> {
                    chosenRate = input.profileCurrentBasal * basalAdjustmentFactor
                    rT.reason.append(context.getString(R.string.honeymoon_meal_slope))
                }
            }
        }

        if (chosenRate == null && input.pregnancyEnable && input.delta > 0 && input.bg > 110 && !input.honeymoon) {
            chosenRate = helpers.calculateBasalRate(finalBasalRate, input.profileCurrentBasal, basalAdjustmentFactor)
            rT.reason.append(context.getString(R.string.pregnancy_delta_over_0_adjustment))
        }

        val finalRate = chosenRate ?: input.profileCurrentBasal
        return Decision(finalRate, 30, overrideSafety)
    }

    // ===== utilitaires existants inchangés =====
    fun interpolateBasalFactor(bg: Double): Double = interpolateBasal(bg)

    fun computeFinalBasalRate(bg: Double, tddRecent: Float, tddPrevious: Float, currentBasalRate: Float): Double =
        computeFinalBasal(bg, tddRecent, tddPrevious, currentBasalRate)

    fun smoothBasalRate(tddRecent: Float, tddPrevious: Float, currentBasalRate: Float): Float =
        calculateSmoothBasalRate(tddRecent, tddPrevious, currentBasalRate)

    private fun interpolateBasal(bg: Double): Double {
        val clampedBG = bg.coerceIn(80.0, 300.0)
        return when {
            clampedBG < 80 -> 0.5
            clampedBG < 120 -> 0.5 + (2.0 - 0.5) / (120.0 - 80.0) * (clampedBG - 80.0)
            clampedBG < 180 -> 2.0 + (5.0 - 2.0) / (180.0 - 120.0) * (clampedBG - 120.0)
            else -> 5.0
        }
    }

    private fun computeFinalBasal(bg: Double, tddRecent: Float, tddPrevious: Float, currentBasalRate: Float): Double {
        val smoothBasal = calculateSmoothBasalRate(tddRecent, tddPrevious, currentBasalRate)
        val basalAdjustmentFactor = interpolate(bg)
        val finalBasal = smoothBasal * basalAdjustmentFactor
        return finalBasal.coerceIn(0.0, 8.0)
    }

    private fun calculateSmoothBasalRate(tddRecent: Float, tddPrevious: Float, currentBasalRate: Float): Float {
        val weightRecent = 0.6f
        val weightPrevious = 1.0f - weightRecent
        val weightedTdd = (tddRecent * weightRecent) + (tddPrevious * weightPrevious)
        val adjustedBasalRate = currentBasalRate * (weightedTdd / tddRecent)
        return adjustedBasalRate.coerceIn(currentBasalRate * 0.5f, currentBasalRate * 2.0f)
    }

    private fun interpolate(xdata: Double): Double {
        val polyX = arrayOf(80.0, 90.0, 100.0, 110.0, 130.0, 160.0, 200.0, 220.0, 240.0, 260.0, 280.0, 300.0)
        val polyY = arrayOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 9.0, 10.0, 10.0, 10.0, 10.0, 10.0)
        val higherBasalRangeWeight = 1.5
        val lowerBasalRangeWeight = 0.8
        val polymax = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        val stepT = polyX[polymax]
        val sValold = polyY[polymax]
        var newVal = 1.0

        if (xdata < 80) {
            newVal = 0.5
        } else if (stepT < xdata) {
            val lowX = polyX[polymax - 1]
            val lowVal = polyY[polymax - 1]
            val topX = stepT
            val topVal = sValold
            newVal = lowVal + (xdata - lowX) * (topVal - lowVal) / (topX - lowX)
        } else {
            for (i in 1..polymax) {
                val stepC = polyX[i]
                val sValC = polyY[i]
                if (xdata < stepC) {
                    newVal = sVal + (xdata - step) * (sValC - sVal) / (stepC - step)
                    break
                }
                sVal = sValC
                step = stepC
            }
        }
        newVal = if (xdata > 100) newVal * higherBasalRangeWeight else newVal * lowerBasalRangeWeight
        return newVal.coerceIn(0.0, 10.0)
    }
}
