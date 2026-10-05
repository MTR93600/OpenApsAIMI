package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.circadianSensitivityHourly
import app.aaps.plugins.aps.openAPSAIMI.ISF.HeartRateTrendIsf
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal data class AimiBasalSchedule(
    val pumpCaps: PumpCaps,
    val profileCurrentBasal: Double,
    val basal: Double,
    val targetBg: Double,
    val minBg: Double,
    val maxBg: Double,
    val sensitivityRatio: Double,
    val deliverAt: Long,
    val maxIobLimit: Double,
)

internal data class AimiActivityVitals(
    val tick: String,
    val minDelta: Double,
    val minAvgDelta: Double,
)

internal interface AimiBasalScheduleCalls {
    fun maxSmb(): Double
    fun hourOfDay(): Int
    fun pumpSteps(): Pair<Double, Double>
    fun validateBasal(rate: Double, caps: PumpCaps): Double
    fun maxIob(): Double
    fun recentSteps5(): Int
    fun recentSteps10(): Int
    fun recentSteps30(): Int
    fun recentSteps180(): Int
    fun setTargetBg(value: Float)
    fun targetBg(): Float
}

/**
 * `buildGlobalAimiBasalScheduleBootstrap`.
 *
 * Autosens 0.5 doubles a 1.00 U/h profile basal to 2.00 U/h on the else path.
 * This function has no swallowed [Exception].
 */
internal fun decideBasalSchedule(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    contextTargetOverride: Double?,
    bg: Double,
    predictedBg: Float,
    combinedDelta: Float,
    minAgo: Double,
    systemTime: Long,
    bgTime: Long,
    flatBGsDetected: Boolean,
    honeymoon: Boolean,
    circadianMinute: Int,
    circadianSecond: Int,
    texts: TextResolver,
    consoleLog: MutableList<String>,
    calls: AimiBasalScheduleCalls,
): AimiBasalSchedule {
    rT.reason.append(texts.gs(ApsStrings.reason_maxsmb, calls.maxSmb()))
    var nowMinutes = calls.hourOfDay() + circadianMinute / 60.0 + circadianSecond / 3600.0
    nowMinutes = (round(nowMinutes * 100) / 100)
    val circadianSensitivity = circadianSensitivityHourly(nowMinutes)
    val deliverAt = ctx.currentTime

    val (basalStep, bolusStep) = calls.pumpSteps()
    val pumpCaps = PumpCaps(
        basalStep = if (basalStep > 0) basalStep else 0.05,
        bolusStep = if (bolusStep > 0) bolusStep else 0.05,
        minDurationMin = 30,
        maxBasal = profile.max_basal,
        maxSmb = 3.0,
    )
    val profileCurrentBasal = calls.validateBasal(profile.current_basal, pumpCaps)
    var basal: Double

    val noise = glucoseStatus.noise
    if (bg <= 10 || bg == 38.0 || noise >= 3) {
        rT.reason.append(texts.gs(ApsStrings.reason_cgm_calibrating))
    }
    if (minAgo > 12 || minAgo < -5) {
        rT.reason.append(texts.gs(ApsStrings.reason_bg_data_old, systemTime, minAgo, bgTime))
    } else if (bg > 60 && flatBGsDetected) {
        rT.reason.append(texts.gs(ApsStrings.reason_cgm_flat))
    }

    val maxIobLimit = calls.maxIob()
    var targetBgLocal = (profile.min_bg + profile.max_bg) / 2
    var minBgLocal = profile.min_bg
    var maxBgLocal = profile.max_bg

    if (contextTargetOverride != null) {
        val override = contextTargetOverride
        if (minBgLocal < override) minBgLocal = override
        if (maxBgLocal < override) maxBgLocal = override
    }

    var sensitivityRatioLocal = 0.0
    val highTemptargetRaisesSensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
    val normalTarget = if (honeymoon) 130 else 100
    val halfBasalTarget = profile.half_basal_exercise_target

    when {
        !profile.temptargetSet && calls.recentSteps5() >= 0 &&
            (calls.recentSteps30() >= 500 || calls.recentSteps180() > 1500) &&
            calls.recentSteps10() > 0 && predictedBg < 140 -> {
            calls.setTargetBg(130.0f)
        }

        !profile.temptargetSet && predictedBg >= 120 && combinedDelta > 3 -> {
            var baseTarget = if (honeymoon) 110.0 else 70.0
            if (calls.hourOfDay() in 0..11 || calls.hourOfDay() in 15..19 || calls.hourOfDay() >= 22) {
                baseTarget = if (honeymoon) 110.0 else 90.0
            }
            var hyperTarget = max(baseTarget, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
            hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
            hyperTarget = max(hyperTarget, baseTarget.toInt())

            calls.setTargetBg(hyperTarget.toFloat())
            targetBgLocal = hyperTarget.toDouble()
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
            sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
            sensitivityRatioLocal = AimiTickPolicyMath.round(sensitivityRatioLocal, 2)
            consoleLog.add(texts.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
        }

        !profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120 -> {
            val baseHypoTarget = if (honeymoon) 130.0 else 110.0
            val hypoTarget = baseHypoTarget * max(1.0, circadianSensitivity)
            calls.setTargetBg(min(hypoTarget.toFloat(), 166.0f))
            targetBgLocal = calls.targetBg().toDouble()
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
            sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
            sensitivityRatioLocal = AimiTickPolicyMath.round(sensitivityRatioLocal, 2)
            consoleLog.add(texts.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
        }

        else -> {
            val defaultTarget = profile.target_bg
            calls.setTargetBg(defaultTarget.toFloat())
            targetBgLocal = calls.targetBg().toDouble()
        }
    }
    if (highTemptargetRaisesSensitivity && profile.temptargetSet && targetBgLocal > normalTarget
        || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && targetBgLocal < normalTarget
    ) {
        val c = (halfBasalTarget - normalTarget).toDouble()
        sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
        sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
        sensitivityRatioLocal = AimiTickPolicyMath.round(sensitivityRatioLocal, 2)
        consoleLog.add(texts.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
    } else {
        sensitivityRatioLocal = ctx.autosensData.ratio
        consoleLog.add(texts.gs(ApsStrings.autosens_ratio_log, sensitivityRatioLocal))
    }
    basal = profile.current_basal / sensitivityRatioLocal
    // Endocrine amp applied once in setTempBasal / Harmonia production — not here (avoids double scale).
    basal = AimiTickPolicyMath.roundBasal(basal)
    if (basal != profileCurrentBasal) {
        consoleLog.add(texts.gs(ApsStrings.console_adjust_basal, profileCurrentBasal, basal))
    } else {
        consoleLog.add(texts.gs(ApsStrings.console_basal_unchanged, basal))
    }

    if (profile.temptargetSet) {
        consoleLog.add(texts.gs(ApsStrings.console_temp_target_set))
    } else {
        if (profile.sensitivity_raises_target && ctx.autosensData.ratio > 1 || profile.resistance_lowers_target && ctx.autosensData.ratio < 1) {
            minBgLocal = AimiTickPolicyMath.round((minBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
            maxBgLocal = AimiTickPolicyMath.round((maxBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
            var newTargetBg = AimiTickPolicyMath.round((targetBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
            newTargetBg = max(80.0, newTargetBg)
            if (targetBgLocal == newTargetBg) {
                consoleLog.add(texts.gs(ApsStrings.console_target_bg_unchanged, newTargetBg))
            } else {
                consoleLog.add(texts.gs(ApsStrings.console_target_bg_changed, targetBgLocal, newTargetBg))
            }
            targetBgLocal = newTargetBg
        }
    }

    return AimiBasalSchedule(
        pumpCaps = pumpCaps,
        profileCurrentBasal = profileCurrentBasal,
        basal = basal,
        targetBg = targetBgLocal,
        minBg = minBgLocal,
        maxBg = maxBgLocal,
        sensitivityRatio = sensitivityRatioLocal,
        deliverAt = deliverAt,
        maxIobLimit = maxIobLimit,
    )
}

internal interface AimiHeartRateIsfCalls {
    fun iob(): Float
    fun roundDisplay(value: Double): Int
    fun stepsCached(now: Long): List<SC>
    fun logSteps(samples: List<SC>)
    fun setRecentSteps(steps5: Int, steps10: Int, steps15: Int, steps30: Int, steps60: Int, steps180: Int)
    fun phoneSteps5(): Int
    fun phoneSteps10(): Int
    fun phoneSteps15(): Int
    fun phoneSteps30(): Int
    fun phoneSteps60(): Int
    fun phoneSteps180(): Int
    fun heartRatesCached(now: Long): List<HR>
    fun logHeartRates(samples: List<HR>)
    fun setAverageBpm(value: Double)
    fun averageBpm(): Double
    fun setAverageBpm10(value: Double)
    fun setAverageBpm60(value: Double)
    fun setAverageBpm180(value: Double)
    fun setBaselineReal(value: Boolean)
    fun logHeartRateFailure(error: Exception)
    fun recentSteps10(): Int
    fun averageBpm10(): Double
    fun averageBpm60(): Double
    fun baselineReal(): Boolean
    fun delta(): Float
    fun scaleVariableSensitivity(factor: Float)
}

/**
 * `runPostBasalBootstrapIobTickStepsAndHeartRate`.
 *
 * A real 10-minute heart rate of 110 bpm against a 60-minute average near 88 multiplies ISF by 0.90
 * (50 becomes 45). The heart-rate window used to swallow [Exception], substitute 80 bpm and mark the
 * baseline as not real. That fallback is unchanged, and the failure is now also a decision-log line.
 */
internal fun decideHeartRateIsf(
    glucoseStatus: GlucoseStatusAIMI,
    profile: OapsProfileAimi,
    iobData: IobTotal,
    bg: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    consoleError: MutableList<String>,
    calls: AimiHeartRateIsfCalls,
): AimiActivityVitals {
    if (abs(calls.iob() - iobData.iob.toFloat()) > 1.0) {
        consoleLog.add("⚠️ IOB Mismatch: Profiler=${calls.iob()} vs System=${iobData.iob}")
    }

    val tick: String = if (glucoseStatus.delta > -0.5) {
        "+" + calls.roundDisplay(glucoseStatus.delta)
    } else {
        calls.roundDisplay(glucoseStatus.delta).toString()
    }
    val minDelta = min(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
    val minAvgDelta = min(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta)

    consoleError.add("CR:${profile.carb_ratio}")

    val now = aimiWallClockMs()
    val timeMillis5 = now - 5 * 60 * 1000
    val timeMillis10 = now - 10 * 60 * 1000
    val timeMillis15 = now - 15 * 60 * 1000
    val timeMillis30 = now - 30 * 60 * 1000
    val timeMillis60 = now - 60 * 60 * 1000
    val timeMillis180 = now - 180 * 60 * 1000

    if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
        val allStepsCounts = calls.stepsCached(now)
        calls.logSteps(allStepsCounts)

        val valid5 = allStepsCounts.filter { it.timestamp >= timeMillis5 }.maxByOrNull { it.timestamp }
        val fallbackRecord = if (valid5 == null) {
            allStepsCounts.filter { it.timestamp >= (now - 30 * 60 * 1000) }.maxByOrNull { it.timestamp }
        } else null

        calls.setRecentSteps(
            steps5 = valid5?.steps5min ?: fallbackRecord?.steps5min ?: 0,
            steps10 = allStepsCounts.filter { it.timestamp >= timeMillis10 }.maxByOrNull { it.timestamp }?.steps10min ?: 0,
            steps15 = allStepsCounts.filter { it.timestamp >= timeMillis15 }.maxByOrNull { it.timestamp }?.steps15min ?: 0,
            steps30 = allStepsCounts.filter { it.timestamp >= timeMillis30 }.maxByOrNull { it.timestamp }?.steps30min ?: 0,
            steps60 = allStepsCounts.filter { it.timestamp >= timeMillis60 }.maxByOrNull { it.timestamp }?.steps60min ?: 0,
            steps180 = allStepsCounts.filter { it.timestamp >= timeMillis180 }.maxByOrNull { it.timestamp }?.steps180min ?: 0,
        )
    } else {
        calls.setRecentSteps(
            steps5 = calls.phoneSteps5(),
            steps10 = calls.phoneSteps10(),
            steps15 = calls.phoneSteps15(),
            steps30 = calls.phoneSteps30(),
            steps60 = calls.phoneSteps60(),
            steps180 = calls.phoneSteps180(),
        )
    }

    try {
        val allHeartRates = calls.heartRatesCached(now)
        calls.logHeartRates(allHeartRates)

        fun getRateForWindow(windowMillis: Long): List<HR> {
            val windowStart = now - windowMillis
            return allHeartRates.filter {
                val end = it.timestamp + it.duration
                end >= windowStart
            }
        }

        val hr5List = getRateForWindow(5 * 60 * 1000)
        calls.setAverageBpm(
            if (hr5List.isNotEmpty()) {
                hr5List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                val partialFallback = allHeartRates.filter { (it.timestamp + it.duration) >= (now - 30 * 60 * 1000) }
                val lastKnown = partialFallback.maxByOrNull { it.timestamp }
                if (lastKnown != null) lastKnown.beatsPerMinute else Double.NaN
            },
        )

        val hr10List = getRateForWindow(10 * 60 * 1000)
        calls.setAverageBpm10(
            if (hr10List.isNotEmpty()) hr10List.map { it.beatsPerMinute.toInt() }.average() else calls.averageBpm(),
        )

        val hr60List = getRateForWindow(60 * 60 * 1000)
        // The 80.0 below is a substitute, not a measurement. It stays because other readers
        // (ActivityManager's avgHrResting) depend on a non-zero number, but anything that
        // STRENGTHENS a dose must know the difference — see [HeartRateTrendIsf].
        calls.setBaselineReal(hr60List.isNotEmpty())
        calls.setAverageBpm60(
            if (hr60List.isNotEmpty()) hr60List.map { it.beatsPerMinute.toInt() }.average() else 80.0,
        )

        val hr180List = getRateForWindow(180 * 60 * 1000)
        calls.setAverageBpm180(
            if (hr180List.isNotEmpty()) hr180List.map { it.beatsPerMinute.toInt() }.average() else 80.0,
        )
    } catch (e: Exception) {
        calls.logHeartRateFailure(e)
        calls.setAverageBpm(80.0)
        calls.setAverageBpm10(80.0)
        calls.setAverageBpm60(80.0)
        calls.setAverageBpm180(80.0)
        calls.setBaselineReal(false)
    }
    val steps10 = calls.recentSteps10()
    val hr10 = calls.averageBpm10()
    val hr60 = calls.averageBpm60()
    val baselineReal = calls.baselineReal()
    val heartRateTrendMultiplier = HeartRateTrendIsf.multiplier(
        steps10m = steps10,
        avgBpm10 = hr10,
        avgBpm60 = hr60,
        baselineIsReal = baselineReal,
        bgMgdl = bg.toDouble(),
        deltaMgdl5m = calls.delta().toDouble(),
    )
    if (heartRateTrendMultiplier < 1.0) {
        calls.scaleVariableSensitivity(heartRateTrendMultiplier.toFloat())
        consoleLog.add(
            "💓 HR_TREND_ISF x${aimiFmt2(heartRateTrendMultiplier)} (hr10 ${round(hr10).toInt()} / hr60 ${round(hr60).toInt()}, steps10 $steps10)",
        )
    }

    return AimiActivityVitals(
        tick = tick,
        minDelta = minDelta,
        minAvgDelta = minAvgDelta,
    )
}
