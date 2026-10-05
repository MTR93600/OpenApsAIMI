package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbIntervalPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hypo and SMB safety math lifted out of `DetermineBasalaimiSMB2`.
 *
 * Inputs are values. Logs and localized phrases are returned or passed in, so this file
 * does not read preferences, the wall clock, or Android resources. The tick still owns
 * those reads and writes the same strings it did before.
 */
internal object AimiHypoSmbSafety {

    const val EPS_FALL = 0.3
    const val EPS_ACC = 0.2
    const val HYPO_RELEASE_MARGIN = 5.0
    const val HYPO_RELEASE_HOLD_MIN = 5
    const val MAX_ZERO_BASAL_DURATION = 60

    fun interface PhraseBook {
        fun line(id: String, vararg args: Any?): String
    }

    fun safetyAdjustment(
        currentBG: Float,
        predictedBG: Float,
        bgHistory: List<Float>,
        combinedDelta: Float,
        iob: Float,
        maxIob: Float,
        tdd24Hrs: Float,
        tddPerHour: Float,
        tirInhypo: Float,
        targetBG: Float,
        zeroBasalDurationMinutes: Int,
        delta: Float,
        honeymoon: Boolean,
        phrase: PhraseBook,
    ): SafetyDecision {
        val dropPerHour = HypoTools.calculateDropPerHour(bgHistory, 30f)
        val maxAllowedDropPerHour = 65f
        val reasonBuilder = StringBuilder()
        var stopBasal = false
        var basalLS = false
        var isHypoRisk = false
        val factors = mutableListOf<Float>()
        val safetyFloor = 85.0f
        if (dropPerHour >= maxAllowedDropPerHour && delta < 0) {
            if (currentBG < safetyFloor) {
                stopBasal = true
                isHypoRisk = true
                factors.add(0.0f)
                reasonBuilder.append(phrase.line("bg_drop_high_critical", dropPerHour))
            } else if (currentBG < 110f) {
                stopBasal = false
                factors.add(0.5f)
                reasonBuilder.append(phrase.line("bg_drop_high_warning", dropPerHour))
            }
        }
        if (delta >= 20f && combinedDelta >= 15f && !honeymoon && currentBG > targetBG) {
            reasonBuilder.append(phrase.line("bg_rapid_rise", delta))
        } else {
            when {
                combinedDelta < 1f -> {
                    factors.add(0.6f)
                    reasonBuilder.append(phrase.line("bg_combined_delta_weak", combinedDelta))
                }
                combinedDelta < 2f -> {
                    factors.add(0.8f)
                    reasonBuilder.append(phrase.line("bg_combined_delta_moderate", combinedDelta))
                }
                else -> {
                    factors.add(AimiTickPolicyMath.computeDynamicBolusMultiplier(combinedDelta))
                    reasonBuilder.append(phrase.line("bg_combined_delta_high", combinedDelta))
                }
            }
            if (currentBG > 160f && combinedDelta < 1f) {
                factors.add(0.8f)
                reasonBuilder.append(phrase.line("bg_stable_high_delta_low"))
            }
            if (iob >= maxIob * 0.85f) {
                factors.add(0.85f)
                reasonBuilder.append(phrase.line("iob_high_reduction", iob))
            }
            val tddThreshold = tdd24Hrs / 24f
            if (tddPerHour > tddThreshold) {
                factors.add(0.8f)
                reasonBuilder.append(phrase.line("tdd_per_hour_high", tddPerHour))
            }
            if (tirInhypo >= 8f) {
                factors.add(0.5f)
                reasonBuilder.append(phrase.line("tir_high", tirInhypo))
            }
            val risingFast = delta >= 3f || combinedDelta >= 2f
            if (predictedBG < targetBG + 10 && !risingFast) {
                factors.add(0.5f)
                reasonBuilder.append(phrase.line("bg_near_target", predictedBG, targetBG))
            } else if (predictedBG < targetBG + 10 && risingFast) {
                reasonBuilder.append(phrase.line("bg_near_target_but_rising", predictedBG, targetBG, delta, combinedDelta))
            }
        }
        var bolusFactor = if (factors.isNotEmpty()) {
            factors.minOrNull()?.toDouble() ?: 1.0
        } else {
            1.0
        }
        if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION && !isHypoRisk) {
            stopBasal = false
            basalLS = true
            bolusFactor = 1.0
            reasonBuilder.append(phrase.line("zero_basal_forced", zeroBasalDurationMinutes))
        }
        return SafetyDecision(stopBasal, bolusFactor, reasonBuilder.toString(), basalLS, isHypoRisk)
    }

    fun adjustedDiaMinutes(
        baseDIAHours: Float,
        currentHour: Int,
        pumpAgeDays: Float,
        iob: Double,
        activityContext: ActivityContext,
        steps: Int?,
        heartRate: Int?,
        phrase: PhraseBook,
    ): DiaOutcome {
        val reasonBuilder = StringBuilder()
        var diaMinutes = baseDIAHours * 60f
        reasonBuilder.append(phrase.line("dia_base_info", baseDIAHours, diaMinutes))
        if (currentHour in 6..10) {
            diaMinutes *= 0.8f
            reasonBuilder.append(phrase.line("morning_adjustment"))
        } else if (currentHour in 22..23 || currentHour in 0..5) {
            diaMinutes *= 1.2f
            reasonBuilder.append(phrase.line("night_adjustment"))
        }
        when (activityContext.state) {
            ActivityState.INTENSE -> diaMinutes *= 0.85f
            ActivityState.MODERATE -> {
                diaMinutes *= 0.90f
                reasonBuilder.append(" • Moderate Activity ➝ x0.90\n")
            }
            ActivityState.LIGHT -> {
                diaMinutes *= 0.98f
                reasonBuilder.append(" • Light Activity ➝ x0.98\n")
            }
            else -> Unit
        }
        val s = steps ?: 0
        val h = heartRate ?: 0
        if (h > 95 && s < 100) {
            diaMinutes *= 1.2f
            reasonBuilder.append(phrase.line("reason_bio_sync_stress", h, s))
        } else if (s > 350) {
            if (activityContext.state != ActivityState.INTENSE) {
                diaMinutes *= 0.90f
                reasonBuilder.append(phrase.line("reason_bio_sync_flow", s, h, 0.90f))
            }
        }
        diaMinutes = AimiTickPolicyMath.adjustDIAForIOB(diaMinutes, iob.toFloat())
        if (pumpAgeDays >= 2f) {
            val extraDays = pumpAgeDays - 2f
            diaMinutes *= 1 + 0.1f * extraDays
            reasonBuilder.append(phrase.line("pump_age_adjustment", pumpAgeDays, extraDays * 10))
        }
        val finalDiaMinutes = diaMinutes.coerceIn(180f, 720f)
        reasonBuilder.append(phrase.line("final_dia_constrained", finalDiaMinutes))
        return DiaOutcome(finalDiaMinutes.toDouble(), reasonBuilder.toString())
    }

    fun stepHypoHysteresis(
        bg: Double,
        predictedBg: Double,
        eventualBg: Double,
        threshold: Double,
        deltaMgdlPer5min: Double,
        now: Long,
        state: HypoHysteresisState,
    ): HypoHysteresisStep {
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val minBg = minOf(safe(bg), safe(predictedBg), safe(eventualBg))
        val blockedNow = HypoGuard.isBelowHypoThreshold(bg, predictedBg, eventualBg, threshold, deltaMgdlPer5min)
        if (blockedNow) {
            return HypoHysteresisStep(true, HypoHysteresisState(lastHypoBlockAt = now, hypoClearCandidateSince = null))
        }
        if (state.lastHypoBlockAt == 0L) return HypoHysteresisStep(false, state)
        val above = minBg > threshold + HYPO_RELEASE_MARGIN
        if (above) {
            val since = state.hypoClearCandidateSince ?: now
            val heldMs = now - since
            return if (heldMs >= HYPO_RELEASE_HOLD_MIN * 60_000L) {
                HypoHysteresisStep(false, HypoHysteresisState())
            } else {
                HypoHysteresisStep(true, state.copy(hypoClearCandidateSince = since))
            }
        }
        return HypoHysteresisStep(true, state.copy(hypoClearCandidateSince = null))
    }

    fun smbInterval(input: SmbIntervalInput): SmbIntervalOutcome {
        val logs = mutableListOf<String>()
        if (input.delta > 15f) return SmbIntervalOutcome(1, logs)
        val modeInterval = when {
            input.snackTime -> input.intervals.snack
            input.mealTime -> input.intervals.meal
            input.bfastTime -> input.intervals.bfast
            input.lunchTime -> input.intervals.lunch
            input.dinnerTime -> input.intervals.dinner
            input.sleepTime -> input.intervals.sleep
            input.highCarbTime -> input.intervals.hc
            !input.honeymoon && input.bg > 120f -> input.intervals.highBG
            input.honeymoon && input.bg > 180f -> input.intervals.highBG
            else -> 3
        }.coerceAtLeast(1)
        var interval = modeInterval
        if ((input.recentSteps180 > 1500 && input.bg < 120f) || input.lowCarbTime) {
            interval = interval.coerceAtLeast(10)
        }
        if (input.recentSteps5 > 100 && input.recentSteps30 > 500 && input.lastSmbTime > 20) {
            interval = interval.coerceAtLeast(15)
        }
        if (input.bg < input.targetBg) interval = (interval * 2).coerceAtMost(20)
        if (input.honeymoon && input.bg < 170f && input.delta < 5f) interval = (interval * 2).coerceAtMost(20)
        if (input.night && input.currentHour == 23 && input.delta < 10f && input.iob < input.maxSmb) {
            interval = (interval * 0.8).toInt().coerceAtLeast(1)
        }
        val preClampInterval = interval.coerceIn(1, SmbIntervalPolicy.DEFAULT_MAX_INTERVAL_MIN)
        val finalInterval = SmbIntervalPolicy.applyLowBgFloorAndPkpdBoost(
            intervalAfterModes = interval,
            bgMgdl = input.bg,
            pkpdThrottleIntervalAdd = input.pkpdThrottleIntervalAdd,
        )
        if (input.bg < SmbIntervalPolicy.DEFAULT_LOW_BG_THRESHOLD_MGDL &&
            preClampInterval < SmbIntervalPolicy.DEFAULT_LOW_BG_INTERVAL_MIN
        ) {
            logs.add("LOW_BG_INTERVAL_BOOST bg=${input.bg.roundToInt()} interval=${finalInterval}m")
        }
        if (input.bg >= SmbIntervalPolicy.DEFAULT_LOW_BG_THRESHOLD_MGDL && input.pkpdThrottleIntervalAdd > 0) {
            logs.add("PKPD_INTERVAL_BOOST base=${preClampInterval}m +${input.pkpdThrottleIntervalAdd}m → ${finalInterval}m")
        }
        return SmbIntervalOutcome(finalInterval, logs)
    }

    fun sportSafety(input: SportSafetyInput): Boolean {
        if (input.recentSteps5 == 0 && !input.sportTime && !input.activityActive) return false
        val manualSport = input.sportTime || input.activityActive
        val recentBurst = input.recentSteps5 >= 400 && input.recentSteps10 >= 800
        val sustainedActivity = input.recentSteps30 >= 1200 || input.recentSteps60 >= 3000 || input.recentSteps180 >= 4500
        val baselineHr = if (input.averageHr10 > 0.0) input.averageHr10 else input.averageHr
        val elevatedHeartRate = baselineHr > 0 && input.averageHr > baselineHr * 1.15
        val shortActivityWithHr = (input.recentSteps5 >= 400 || input.recentSteps10 >= 600) && elevatedHeartRate
        val highTargetExercise = input.targetBg >= 140 && (shortActivityWithHr || sustainedActivity)
        return manualSport || recentBurst || sustainedActivity || highTargetExercise
    }

    fun specificAdjustment(input: SpecificAdjustmentInput): Float {
        if (input.ignoreSafetyRestrictions) return input.smbAmount
        val fallingDecelerating =
            input.delta < -EPS_FALL &&
                input.shortAvgDelta < -EPS_FALL &&
                input.longAvgDelta < -EPS_FALL &&
                input.shortAvgDelta > input.longAvgDelta + EPS_ACC
        if (fallingDecelerating && input.bg < input.targetBg + 10) {
            return (input.smbAmount * 0.5f).coerceAtLeast(0f)
        }
        if (input.bg < input.targetBg) return input.smbAmount / 2
        if (input.honeymoon && input.bg < 170 && input.delta < 5) return input.smbAmount / 2
        return input.smbAmount
    }

    fun finalizeSmbFloor(input: SmbFloorInput): Float {
        var result = input.smbToGive
        if (result < 0.0f) result = 0.0f
        if (input.iob <= 0.1 && input.bg > 120 && input.delta >= 2 && result == 0.0f) result = 0.1f
        if (input.lateFatRise && result == 0.0f && input.bg > 130 && input.delta >= 1.0f) result = 0.1f
        return result
    }

    fun mealAggression(input: MealAggressionInput): MealAggressionWeights {
        if (!input.mealContextActive) return MealAggressionWeights(false, 1.0, 0.0, false, 0.0)
        val overshoot = (input.predictedBg - input.targetBg).coerceAtLeast(0.0)
        val normalized = (overshoot / 80.0).coerceIn(0.0, 1.0)
        val boost = 1.0 + 0.05 + 0.05 * normalized
        val guardScale = if (overshoot > 10 && (input.bg - input.hypoThreshold) > 5.0) {
            (0.4 + 0.3 * normalized).coerceAtMost(0.85)
        } else {
            0.0
        }
        val bypassTail = overshoot > 20 && input.mealCob > 10.0
        return MealAggressionWeights(true, boost, guardScale, bypassTail, overshoot)
    }

    fun criticalConditions(input: CriticalInput): CriticalScan {
        val context = input.context
        val logs = mutableListOf<String>()
        val conditions = mutableListOf<String>()
        var hysteresis = input.hysteresis
        val fallback = (context.bg > context.targetBg + 30.0) &&
            (context.delta >= 2.0) &&
            (context.iob < context.maxIob * 0.8)
        val mealClockActive = context.mealTime || context.bfastTime || context.lunchTime ||
            context.dinnerTime || context.highCarbTime || context.snackTime
        val hyperDropExempt = HyperInstalledDroppingExemption.shouldBypass(
            HyperInstalledDroppingExemption.Input(
                enabled = input.hyperDropExemptEnabled,
                bgMgdl = context.bg,
                targetBgMgdl = context.targetBg,
                deltaMgdl5m = context.delta,
                hypoThresholdMgdl = context.hypoThreshold,
                mealContextActive = mealClockActive,
                cobG = context.cob,
            ),
        )
        val labels = input.labels
        fun addIfActive(
            active: Boolean,
            conditionLabel: String,
            bypassedByFallback: Boolean = false,
            bypassedByHyperDrop: Boolean = false,
            bypassTag: String,
        ) {
            if (!active) return
            if (fallback && bypassedByFallback) {
                logs.add("SMB_FALLBACK_BYPASS: $bypassTag")
                return
            }
            if (hyperDropExempt && bypassedByHyperDrop) {
                logs.add("SMB_HYPER_DROP_BYPASS: $bypassTag")
                return
            }
            conditions.add(conditionLabel)
        }
        val hypo = stepHypoHysteresis(
            bg = context.bg,
            predictedBg = context.predictedBg,
            eventualBg = context.eventualBG,
            threshold = context.hypoThreshold,
            deltaMgdlPer5min = context.delta,
            now = input.nowMs,
            state = hysteresis,
        )
        hysteresis = hypo.state
        addIfActive(hypo.blocked, labels.hypoGuard, bypassedByFallback = true, bypassTag = "hypoGuard")
        addIfActive(input.honeymoon && context.delta < 0 && context.bg < 170, labels.honeysmb, bypassTag = "honeysmb")
        addIfActive(
            context.delta <= -1 && !context.mealTime && !context.bfastTime && !context.lunchTime &&
                !context.dinnerTime && context.eventualBG < 120,
            labels.negDelta,
            bypassTag = "negdelta",
        )
        addIfActive(
            context.iob >= 2 * context.maxSMB && context.bg < 110 && context.delta < 10 &&
                !context.mealTime && !context.bfastTime && !context.lunchTime && !context.dinnerTime,
            labels.nosmb,
            bypassTag = "nosmb",
        )
        addIfActive(context.fastingTime, labels.fasting, bypassTag = "fasting")
        addIfActive(context.bg < 60, labels.belowMin, bypassTag = "belowMinThreshold")
        addIfActive(context.iscalibration, labels.newCalibration, bypassTag = "newCalibration")
        addIfActive(context.bg < context.targetBg && context.delta < 0, labels.belowTargetDropping, bypassTag = "belowTargetAndDropping")
        addIfActive(
            context.bg < context.targetBg && context.delta >= 0 && context.cob <= 0,
            labels.belowTargetStableNoCob,
            bypassTag = "belowTargetAndStableButNoCob",
        )
        addIfActive(context.delta < -2.0, labels.droppingFast, bypassedByHyperDrop = true, bypassTag = "droppingFast")
        addIfActive(context.bg > 180 && context.delta < -1.5, labels.droppingFastAtHigh, bypassedByHyperDrop = true, bypassTag = "droppingFastAtHigh")
        addIfActive(context.delta < -3.0, labels.droppingVeryFast, bypassedByHyperDrop = true, bypassTag = "droppingVeryFast")
        val nearTargetThreshold = context.targetBg + 40.0
        val isDeepHypoRisk = context.bg < 90.0 || context.predictedBg < 90.0
        val prediction = if (context.bg > nearTargetThreshold && !isDeepHypoRisk) {
            context.delta < -3.0
        } else {
            context.predictedBg < context.bg && context.delta < 0
        }
        addIfActive(prediction, labels.prediction, bypassedByFallback = true, bypassedByHyperDrop = true, bypassTag = "prediction")
        addIfActive(context.bg < 90, labels.bg90, bypassTag = "bg90")
        addIfActive(
            context.delta < 0 && context.longAvgDelta < 0 && context.shortAvgDelta < 0 &&
                (context.bg < context.targetBg || context.delta < -2.0),
            labels.acceleratingDown,
            bypassedByHyperDrop = true,
            bypassTag = "acceleratingDown",
        )
        return CriticalScan(conditions, logs, hysteresis)
    }

    fun adjustedDelayFactor(input: DelayFactorInput): Float {
        val highBgOverrideThreshold = input.normalBgThreshold + 40f
        val severeHighBgThreshold = input.normalBgThreshold + 80f
        var delayFactor = if (
            input.bg.isNaN() || input.averageHr.isNaN() || input.averageHr10.isNaN() || input.averageHr10 == 0f
        ) {
            1f
        } else {
            val increasedPhysicalActivity = input.recentSteps180 > 1500
            val sanitizedHr10 = if (input.averageHr10.isFinite() && input.averageHr10 > 0f) input.averageHr10 else Float.NaN
            val heartRateChange = if (sanitizedHr10.isNaN()) 1.0 else input.averageHr / sanitizedHr10
            val increasedHeartRateActivity = !sanitizedHr10.isNaN() && heartRateChange.toDouble() >= 1.2
            val insulinSensitivityDecreaseThreshold = 1.5 * input.normalBgThreshold
            val baseFactor = when {
                input.bg <= input.normalBgThreshold -> 1f
                input.bg <= insulinSensitivityDecreaseThreshold ->
                    1f - ((input.bg - input.normalBgThreshold) / (insulinSensitivityDecreaseThreshold - input.normalBgThreshold))
                else -> 0.5f
            }
            val shouldDampenForActivity = (increasedPhysicalActivity || increasedHeartRateActivity) && input.bg < highBgOverrideThreshold
            var adjusted = baseFactor.toFloat()
            if (shouldDampenForActivity) adjusted = (adjusted * 0.85f).coerceAtLeast(0.6f)
            if (input.bg >= highBgOverrideThreshold) adjusted = adjusted.coerceAtLeast(1f)
            if (input.bg >= severeHighBgThreshold) adjusted = adjusted.coerceAtLeast(1.1f)
            adjusted
        }
        if (input.currentHour in 18..23) delayFactor *= 1.2f
        else if (input.currentHour in 0..5) delayFactor *= 0.8f
        return delayFactor
    }

    fun insulinEffect(input: InsulinEffectInput): Float {
        var insulinEffect = input.iob * input.variableSensitivity / input.insulinDivisor
        if (input.cob > 0) insulinEffect *= 0.9f
        val highBgOverrideThreshold = input.normalBgThreshold + 40f
        val severeHighBgThreshold = input.normalBgThreshold + 80f
        val physicalActivityFactor = (1.0f - (input.recentSteps180Min / 10000f).coerceAtMost(0.4f)).coerceIn(0.7f, 1.0f)
        if (input.bg < highBgOverrideThreshold) insulinEffect *= physicalActivityFactor
        val adjustedDelayFactor = adjustedDelayFactor(
            DelayFactorInput(
                bg = input.bg,
                recentSteps180 = input.memberRecentSteps180,
                averageHr = input.averageHr,
                averageHr10 = input.averageHr10,
                currentHour = input.currentHour,
                normalBgThreshold = input.normalBgThreshold,
            ),
        )
        insulinEffect *= adjustedDelayFactor
        if (input.bg >= severeHighBgThreshold) insulinEffect *= 1.3f
        else if (input.bg > input.normalBgThreshold) insulinEffect *= 1.2f
        if (input.currentHour in 0..5) insulinEffect *= 0.8f
        input.phrase.line("insulin_effect", insulinEffect)
        return insulinEffect
    }

    fun trendIndicator(input: TrendInput): Int {
        val insulinEffect = insulinEffect(input.insulin)
        val activityImpact = (input.recentSteps5 - input.recentSteps10) * 0.05
        val trendValue = (input.delta * 0.5) + (input.shortAvgDelta * 0.25) + (input.longAvgDelta * 0.15) +
            (insulinEffect * 0.2) + (activityImpact * 0.1)
        return when {
            trendValue > 1.0 -> 1
            trendValue < -1.0 -> -1
            abs(trendValue) < 0.5 -> 0
            trendValue > 0.5 -> 2
            else -> -2
        }
    }

    fun dynamicPeak(input: DynamicPeakInput, phrase: PhraseBook): PeakOutcome {
        val reason = StringBuilder()
        val logs = mutableListOf<String>()
        var dynamicPeakTime = input.profilePeakTime
        val activityRatio = input.futureActivity / (input.currentActivity + 0.0001)
        reason.append(phrase.line("calc_dynamic_peaktime"))
        reason.append(phrase.line("profile_peak_time", input.profilePeakTime))
        reason.append(phrase.line("bg_delta", input.bg, input.delta))
        val hyperCorrectionFactor = when {
            input.bg <= 130 || input.delta <= 4 -> 1.0
            input.bg in 130.0..240.0 -> 0.6 - (input.bg - 130) * (0.6 - 0.3) / (240 - 130)
            else -> 0.3
        }
        dynamicPeakTime *= hyperCorrectionFactor
        reason.append(phrase.line("reason_hyper_correction", hyperCorrectionFactor))
        if (input.currentActivity > 0.1) {
            val acceleration = input.currentActivity * 20 + 5
            dynamicPeakTime -= acceleration
            reason.append(phrase.line("reason_iob_adjustment_inverted", acceleration))
        }
        val ratioFactor = when {
            activityRatio > 1.5 -> 0.8
            activityRatio < 0.5 -> 1.2
            else -> 1.0
        }
        dynamicPeakTime *= ratioFactor
        reason.append(phrase.line("reason_activity_ratio", AimiTickPolicyMath.round(activityRatio, 2), ratioFactor))
        val steps = input.stepCount ?: 0
        val hr = input.heartRate ?: 0
        val isStress = hr > 95 && steps < 100
        val isFlow = steps > 500 || (steps > 200 && hr > 100)
        if (isStress) {
            dynamicPeakTime *= 1.25
            reason.append(phrase.line("reason_bio_sync_stress", hr, steps))
            logs.add("Bio-Sync: STRESS DETECTED (HR $hr, Steps $steps) -> Peak slowed x1.25")
        } else if (isFlow) {
            val flowFactor = if (steps > 1500) 0.7 else 0.85
            dynamicPeakTime *= flowFactor
            reason.append(phrase.line("reason_bio_sync_flow", steps, hr, flowFactor))
        } else if (steps < 50 && hr < 65 && hr > 40) {
            dynamicPeakTime *= 1.1
            reason.append("Bio-Sync: Deep Rest (HR $hr) -> x1.1\n")
        }
        val intermediate = dynamicPeakTime
        if (dynamicPeakTime > 40) {
            if (input.sensorLagActivity > input.historicActivity) {
                dynamicPeakTime *= 0.85
                reason.append(phrase.line("reason_sensor_lag"))
            } else if (input.sensorLagActivity < input.historicActivity) {
                dynamicPeakTime *= 1.2
                reason.append(phrase.line("reason_sensor_lag_lower"))
            }
        }
        return PeakOutcome(dynamicPeakTime.coerceIn(35.0, 120.0), intermediate, logs, reason.toString())
    }

    data class DiaOutcome(val minutes: Double, val reason: String)
    data class HypoHysteresisState(val lastHypoBlockAt: Long = 0L, val hypoClearCandidateSince: Long? = null)
    data class HypoHysteresisStep(val blocked: Boolean, val state: HypoHysteresisState)
    data class SmbIntervals(
        val snack: Int,
        val meal: Int,
        val bfast: Int,
        val lunch: Int,
        val dinner: Int,
        val sleep: Int,
        val hc: Int,
        val highBG: Int,
    )
    data class SmbIntervalInput(
        val delta: Float,
        val bg: Float,
        val targetBg: Float,
        val iob: Double,
        val maxSmb: Double,
        val honeymoon: Boolean,
        val night: Boolean,
        val currentHour: Int,
        val snackTime: Boolean,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val sleepTime: Boolean,
        val highCarbTime: Boolean,
        val lowCarbTime: Boolean,
        val intervals: SmbIntervals,
        val pkpdThrottleIntervalAdd: Int,
        val recentSteps5: Int,
        val recentSteps30: Int,
        val recentSteps180: Int,
        val lastSmbTime: Int,
    )
    data class SmbIntervalOutcome(val minutes: Int, val logs: List<String>)
    data class SportSafetyInput(
        val recentSteps5: Int,
        val recentSteps10: Int,
        val recentSteps30: Int,
        val recentSteps60: Int,
        val recentSteps180: Int,
        val sportTime: Boolean,
        val activityActive: Boolean,
        val averageHr: Double,
        val averageHr10: Double,
        val targetBg: Float,
    )
    data class SpecificAdjustmentInput(
        val smbAmount: Float,
        val ignoreSafetyRestrictions: Boolean,
        val delta: Float,
        val shortAvgDelta: Float,
        val longAvgDelta: Float,
        val bg: Double,
        val targetBg: Float,
        val honeymoon: Boolean,
        val iob: Float,
        val maxSmb: Double,
        val currentHour: Int,
    )
    data class SmbFloorInput(
        val smbToGive: Float,
        val iob: Float,
        val bg: Double,
        val delta: Float,
        val lateFatRise: Boolean,
    )
    data class MealAggressionInput(
        val mealContextActive: Boolean,
        val predictedBg: Double,
        val targetBg: Float,
        val bg: Double,
        val hypoThreshold: Double,
        val mealCob: Double,
    )
    data class MealAggressionWeights(
        val active: Boolean,
        val boostFactor: Double,
        val guardScale: Double,
        val bypassTail: Boolean,
        val predictedOvershoot: Double,
    )
    data class SafetyContext(
        val delta: Double,
        val bg: Double,
        val iob: Double,
        val predictedBg: Double,
        val eventualBG: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val fastingTime: Boolean,
        val iscalibration: Boolean,
        val targetBg: Double,
        val maxSMB: Double,
        val maxIob: Double,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean,
        val snackTime: Boolean,
        val cob: Double,
        val hypoThreshold: Double,
    )
    data class CriticalLabels(
        val hypoGuard: String,
        val honeysmb: String,
        val negDelta: String,
        val nosmb: String,
        val fasting: String,
        val belowMin: String,
        val newCalibration: String,
        val belowTargetDropping: String,
        val belowTargetStableNoCob: String,
        val droppingFast: String,
        val droppingFastAtHigh: String,
        val droppingVeryFast: String,
        val prediction: String,
        val bg90: String,
        val acceleratingDown: String,
    )
    data class CriticalInput(
        val context: SafetyContext,
        val honeymoon: Boolean,
        val hyperDropExemptEnabled: Boolean,
        val labels: CriticalLabels,
        val hysteresis: HypoHysteresisState,
        val nowMs: Long,
    )
    data class CriticalScan(
        val conditions: List<String>,
        val logs: List<String>,
        val hysteresis: HypoHysteresisState,
    )
    data class DelayFactorInput(
        val bg: Float,
        val recentSteps180: Int,
        val averageHr: Float,
        val averageHr10: Float,
        val currentHour: Int,
        val normalBgThreshold: Float,
    )
    data class InsulinEffectInput(
        val bg: Float,
        val iob: Float,
        val variableSensitivity: Float,
        val cob: Float,
        val normalBgThreshold: Float,
        val recentSteps180Min: Int,
        val memberRecentSteps180: Int,
        val averageHr: Float,
        val averageHr10: Float,
        val insulinDivisor: Float,
        val currentHour: Int,
        val phrase: PhraseBook,
    )
    data class TrendInput(
        val delta: Float,
        val shortAvgDelta: Float,
        val longAvgDelta: Float,
        val insulin: InsulinEffectInput,
        val recentSteps5: Int,
        val recentSteps10: Int,
    )
    data class DynamicPeakInput(
        val currentActivity: Double,
        val futureActivity: Double,
        val sensorLagActivity: Double,
        val historicActivity: Double,
        val profilePeakTime: Double,
        val stepCount: Int?,
        val heartRate: Int?,
        val bg: Double,
        val delta: Double,
    )
    data class PeakOutcome(val finalPeak: Double, val intermediate: Double, val logs: List<String>, val reason: String)
}
