package app.aaps.plugins.aps.openAPSAIMI.smb

import android.content.Context
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLogRow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.safety.HighBgOverride
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbDampingUsecase
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbQuantizer
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object SmbInstructionExecutor {

    data class Input(
        val context: Context,
        val preferences: Preferences,
        val csvFile: File,
        val rT: RT,
        val consoleLog: MutableList<String>,
        val consoleError: MutableList<String>,
        val combinedDelta: Double,
        val shortAvgDelta: Float,
        val longAvgDelta: Float,
        val profile: OapsProfileAimi,
        val glucoseStatus: GlucoseStatusAIMI,
        val bg: Double,
        val delta: Double,
        val iob: Float,
        val basalaimi: Float,
        val initialBasal: Double,
        val honeymoon: Boolean,
        val hourOfDay: Int,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean,
        val snackTime: Boolean,
        val sleepTime: Boolean,
        val recentSteps5Minutes: Int,
        val recentSteps10Minutes: Int,
        val recentSteps30Minutes: Int,
        val recentSteps60Minutes: Int,
        val recentSteps180Minutes: Int,
        val averageBeatsPerMinute: Double,
        val averageBeatsPerMinute60: Double,
        val pumpAgeDays: Int,
        val sens: Double,
        val tp: Int,
        val variableSensitivity: Float,
        val targetBg: Double,
        val predictedBg: Float,
        val eventualBg: Double,
        val maxSmb: Double,
        val maxIob: Double,
        val predictedSmb: Float,
        val modelValue: Float,
        val mealData: MealData,
        val pkpdRuntime: PkPdRuntime?,
        val sportTime: Boolean,
        val lateFatRiseFlag: Boolean,
        val highCarbRunTime: Long?,
        val threshold: Double?,
        val dateUtil: DateUtil,
        val currentTime: Long,
        val windowSinceDoseInt: Int,
        val currentInterval: Int?,
        val insulinStep: Float,
        val highBgOverrideUsed: Boolean,
        val profileCurrentBasal: Double,
        val cob: Float
    )

    data class Hooks(
        val refineSmb: (Float, Float, Float, Float, OapsProfileAimi) -> Float,
        val adjustFactors: (Float, Float, Float) -> Triple<Float, Float, Float>,
        val calculateAdjustedDia: (Float, Int, Int, Float, Float, Float, Double) -> Double,
        val costFunction: (Double, Double, Double, Int, Double, Double) -> Double,
        val applySafety: (MealData, Float, Double?, StringBuilder?, PkPdRuntime?, Boolean, Boolean) -> Float,
        val runtimeToMinutes: (Long?) -> Int,
        val computeHypoThreshold: (Double, Int?) -> Double,
        val isBelowHypo: (Double, Double, Double, Double, Double) -> Boolean,
        val logDataMl: (Float, Float) -> Unit,
        val logData: (Float, Float) -> Unit,
        val roundBasal: (Double) -> Double,
        val roundDouble: (Double, Int) -> Double
    )

    data class Result(
        val predictedSmb: Float,
        val basal: Double,
        val finalSmb: Float,
        val highBgOverrideUsed: Boolean,
        val newSmbInterval: Int?
    )

    fun execute(input: Input, hooks: Hooks): Result {
        var predictedSmb = input.predictedSmb
        var basal = input.initialBasal

        val trainingEnabled = input.preferences.get(BooleanKey.OApsAIMIMLtraining)
        if (trainingEnabled && input.csvFile.exists()) {
            val allLines = input.csvFile.readLines()
            val minutesToConsider = 1000.0
            val linesToConsider = (minutesToConsider / 5).toInt()
            if (allLines.size > linesToConsider) {
                val refined = hooks.refineSmb(
                    input.combinedDelta.toFloat(),
                    input.shortAvgDelta,
                    input.longAvgDelta,
                    predictedSmb,
                    input.profile
                )
                input.rT.reason.appendLine(
                    input.context.getString(
                        R.string.reason_ai_file,
                        if (input.csvFile.exists()) "✔" else "✘",
                        "%.2f".format(refined.takeIf { it.isFinite() } ?: 0f)
                    )
                )
                predictedSmb = refined
                val maxIobPref = input.preferences.get(DoubleKey.ApsSmbMaxIob)
                if (input.bg > 170 && input.delta > 4 && input.iob < maxIobPref) {
                    input.rT.reason.appendLine(
                        input.context.getString(
                            R.string.reason_boost_hyper,
                            input.bg.toInt(),
                            input.delta
                        )
                    )
                    predictedSmb *= 1.7f
                } else if (input.bg > 150 && input.delta > 3 && input.iob < maxIobPref) {
                    input.rT.reason.appendLine(
                        input.context.getString(
                            R.string.reason_boost_hyper_2,
                            input.bg.toInt(),
                            input.delta
                        )
                    )
                    predictedSmb *= 1.5f
                }

                basal = when {
                    input.honeymoon && input.bg < 170 -> input.basalaimi * 1.0
                    else -> input.basalaimi.toDouble()
                }
                basal = hooks.roundBasal(basal)
            }
        } else {
            input.rT.reason.appendLine(input.context.getString(R.string.reason_ml_training))
        }

        var smbToGive = if (input.bg > 130 && input.delta > 2 && predictedSmb == 0.0f) {
            input.modelValue
        } else {
            predictedSmb
        }
        if (input.honeymoon && input.bg < 170) {
            smbToGive *= 0.8f
        }

        // ❌ TIME-BASED REACTIVITY REMOVED (replaced by UnifiedReactivityLearner in DetermineBasalAIMI2)
        // Previously: morningFactor, afternoonFactor, eveningFactor, hyperFactor
        // Now: UnifiedReactivityLearner.globalFactor is applied BEFORE this executor

        // ✅ MEAL-CONTEXT FACTORS (complementary to UnifiedLearner)
        // These adjust SMB for specific meal types (breakfast vs dinner etc.)
        val highcarbfactor = input.preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0
        val mealfactor = input.preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0
        val bfastfactor = input.preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0
        val lunchfactor = input.preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0
        val dinnerfactor = input.preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0
        val snackfactor = input.preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0
        val sleepfactor = input.preferences.get(DoubleKey.OApsAIMIsleepFactor) / 100.0

        fun Float.atLeast(min: Float) = if (this < min) min else this

        val base = smbToGive

        // Apply meal-context factors (NO time-based factors)
        smbToGive = when {
            input.honeymoon && input.bg > 160 && input.delta > 4 && input.iob < 0.7 && (input.hourOfDay == 23 || input.hourOfDay in 0..10) ->
                base.atLeast(0.15f)

            !input.honeymoon && input.bg > 120 && input.delta > 8 && input.iob < 1.0 && base < 0.05f ->
                input.profileCurrentBasal.toFloat()

            // ❌ hyperfactor removed - handled by UnifiedLearner
            input.highCarbTime -> base * highcarbfactor.toFloat()
            input.mealTime -> base * mealfactor.toFloat()
            input.bfastTime -> base * bfastfactor.toFloat()
            input.lunchTime -> base * lunchfactor.toFloat()
            input.dinnerTime -> base * dinnerfactor.toFloat()
            input.snackTime -> base * snackfactor.toFloat()
            input.sleepTime -> base * sleepfactor.toFloat()
            // ❌ time-based factors (hour 0-11, 12-18, 19-23) removed
            else -> base  // No time-based adjustment
        }.coerceAtLeast(0f)

        val factors = when {
            input.highCarbTime -> highcarbfactor
            input.mealTime -> mealfactor
            input.bfastTime -> bfastfactor
            input.lunchTime -> lunchfactor
            input.dinnerTime -> dinnerfactor
            input.snackTime -> snackfactor
            input.sleepTime -> sleepfactor
            // ❌ time-based factors removed
            else -> 1.0  // Neutral when no meal mode
        }

        val currentHour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val adjustedDIAInMinutes = hooks.calculateAdjustedDia(
            input.profile.dia.toFloat(),
            currentHour,
            input.recentSteps5Minutes,
            input.averageBeatsPerMinute.toFloat(),
            input.averageBeatsPerMinute60.toFloat(),
            input.pumpAgeDays.toFloat(),
            input.iob.toDouble()
        )
        input.consoleLog.add(
            input.context.getString(R.string.console_dia_adjusted, adjustedDIAInMinutes)
        )
        val actCurr = input.profile.sensorLagActivity
        val actFuture = input.profile.futureActivity
        val td = adjustedDIAInMinutes
        val deltaGross = hooks.roundDouble(
            (input.glucoseStatus.delta + actCurr * input.sens).coerceIn(0.0, 35.0),
            1
        )
        val actTarget = deltaGross / input.sens * factors.toFloat()
        var actMissing: Double
        var deltaScore = 0.5

        if (input.glucoseStatus.delta <= 4.0) {
            actMissing = hooks.roundDouble(
                (actCurr * smbToGive - max(actFuture, 0.0)) / 5,
                4
            )
            deltaScore = ((input.bg - input.targetBg) / 100.0).coerceIn(0.0, 1.0)
        } else {
            actMissing = hooks.roundDouble(
                (actTarget - max(actFuture, 0.0)) / 5,
                4
            )
        }

        val tpD = input.tp.toDouble()
        val tdD = td.coerceAtLeast(tpD * 2.1)
        val tau = tpD * (1 - tpD / tdD) / (1 - 2 * tpD / tdD)
        val a = 2 * tau / tdD
        val s = 1 / (1 - a + (1 + a) * kotlin.math.exp(-tdD / tau))
        var aimiInsReq = actMissing / (s / (tau * tau) * tpD * (1 - tpD / tdD) * kotlin.math.exp(-tpD / tau))

        aimiInsReq = if (aimiInsReq < smbToGive) aimiInsReq else smbToGive.toDouble()
        // 🔒 SAFETY: Cap strict ici aussi pour éviter qu'une heuristique interne ne dépasse le maxSMB
        aimiInsReq = kotlin.math.min(aimiInsReq, input.maxSmb)
        val finalInsulinDose = hooks.roundDouble(aimiInsReq, 2)

        val doseMin = 0.0
        val doseMax = input.maxSmb
        val horizon = 30
        val insulinSensitivity = input.variableSensitivity.toDouble()

        var optimalDose = doseMin
        var bestCost = Double.MAX_VALUE
        val nSteps = 20

        for (i in 0..nSteps) {
            val candidate = doseMin + i * (doseMax - doseMin) / nSteps
            val cost = hooks.costFunction(
                basal,
                input.bg,
                input.targetBg,
                horizon,
                insulinSensitivity,
                candidate
            )
            if (cost < bestCost) {
                bestCost = cost
                optimalDose = candidate
            }
        }

        val baseKp = 0.2
        val kp = baseKp * (0.5 + deltaScore)
        val error = input.bg - input.targetBg
        val correction = kp * error

        val optimalBasalMpc = (optimalDose + correction).coerceIn(doseMin, doseMax)
        // --- Mix MPC / PI : MPC plus dominant pour BG > cible ---
        val alphaRaw = 0.5 + 0.5 * deltaScore  // 0.3 → 0.5 de base
        val alpha = alphaRaw.coerceIn(0.3, 0.9)

        //val alpha = 0.4 + 0.5 * deltaScore
        input.rT.reason.appendLine(
            input.context.getString(
                R.string.reason_mpc_pi,
                optimalBasalMpc,
                alpha * 100,
                finalInsulinDose,
                (1 - alpha) * 100
            )
        )
        run {
            val mpcUsed = kotlin.math.max(0.0, alpha * optimalBasalMpc)
            val piUsed  = kotlin.math.max(0.0, (1 - alpha) * finalInsulinDose)
            val denom = mpcUsed + piUsed
            val mpcShare = if (denom > 1e-6) 100.0 * mpcUsed / denom else 0.0
            input.rT.reason.append(" | MPC utile: %.0f%% (alpha=%.0f%%)".format(mpcShare, 100 * alpha))
        }
        var smbDecision = (alpha * optimalBasalMpc + (1 - alpha) * finalInsulinDose).toFloat()

        val suspectedLateFatMeal = input.highCarbTime && hooks.runtimeToMinutes(input.highCarbRunTime) > 90
        smbDecision = hooks.applySafety(
            input.mealData,
            smbDecision,
            input.threshold,
            input.rT.reason,
            input.pkpdRuntime,
            input.sportTime,
            suspectedLateFatMeal
        )
        input.rT.reason.appendLine(
            input.context.getString(R.string.smb_final, "%.2f".format(smbDecision))
        )
        val hypoGuard = input.threshold ?: hooks.computeHypoThreshold(
            input.profile.min_bg,
            input.profile.lgsThreshold
        )

        val mealModeRun =
            input.mealTime || input.bfastTime || input.lunchTime || input.dinnerTime || input.highCarbTime
        val hyperPlateauActive = (input.bg >= 130.0 && input.predictedBg.toDouble() >= 130.0) &&
            (input.delta < 1.0 && input.combinedDelta < 3.0) &&
            (input.iob < input.maxSmb)

        val highBgRiseActive =
            ((input.bg >= 120.0 && (input.delta >= 1.5 || input.combinedDelta >= 2.0)) || hyperPlateauActive) &&
                (input.iob < input.maxSmb) &&
                !hooks.isBelowHypo(input.bg, input.predictedBg.toDouble(), input.eventualBg, hypoGuard, input.delta)
        val dampingOut = SmbDampingUsecase.run(
            input.pkpdRuntime,
            SmbDampingUsecase.Input(
                smbDecision = smbDecision.toDouble(),
                exercise = input.sportTime,
                suspectedLateFatMeal = input.lateFatRiseFlag,
                mealModeRun = mealModeRun,
                highBgRiseActive = highBgRiseActive
            )
        )
        var smbAfterDamping = dampingOut.smbAfterDamping
        val audit = dampingOut.audit
        val dampedRaw = smbAfterDamping
        input.pkpdRuntime?.let { runtime ->
                        if (input.bg >= 120.0 &&
                                input.delta >= 0.0 &&
                                runtime.tailFraction < 0.30 &&
                                runtime.fusedIsf <= runtime.profileIsf
                            ) {
                                val boostFactor = 1.20
                                val boosted = smbAfterDamping * boostFactor
                                input.rT.reason.append(
                                        "\nHighBG PKPD boost: tail=%.0f%%, scale=%.2f → SMB ×%.2f (%.2f→%.2f)".format(
                                                runtime.tailFraction * 100.0,
                                                runtime.pkpdScale,
                                                boostFactor,
                                                smbAfterDamping,
                                                boosted
                                                )
                                        )
                                smbAfterDamping = boosted
                            }
                    }
        var highBgOverrideFlag = false
        var highBgOverrideUsed = input.highBgOverrideUsed
        var newInterval = input.currentInterval

        HighBgOverride.apply(
            bg = input.bg,
            delta = input.delta,
            predictedBg = input.predictedBg.toDouble(),
            eventualBg = input.eventualBg,
            hypoGuard = hypoGuard,
            iob = input.iob.toDouble(),
            maxSmb = input.maxSmb,
            currentDose = smbAfterDamping,
            pumpStep = input.insulinStep.toDouble()
        ).also { res ->
            smbAfterDamping = res.dose
            if (res.overrideUsed) {
                highBgOverrideFlag = true
                highBgOverrideUsed = true
                newInterval = res.newInterval ?: newInterval
            }
        }

        val finalSmb = SmbQuantizer.quantizeToPumpStep(
            smbAfterDamping.toFloat(),
            input.insulinStep
        )

        val quantized = finalSmb.toDouble()

        val activity = input.pkpdRuntime?.activity
        val activityPct = (activity?.relativeActivity ?: 0.0) * 100.0
        val anticipationPct = (activity?.anticipationWeight ?: 0.0) * 100.0
        val freshnessPct = (activity?.let { (1.0 - it.postWindowFraction).coerceIn(0.0, 1.0) } ?: 0.0) * 100.0
        val activityStage = activity?.stage?.name ?: "n/a"
        input.rT.reason.append(
            "\nPKPD: DIA=%s min, Peak=%s min, Tail=%.0f%%, Activity=%.0f%% (%s, anticip=%.0f%%, fresh=%.0f%%), ISF(fused)=%s (profile=%s, TDD=%s, scale=%.2f)".format(
                input.pkpdRuntime?.params?.diaHrs?.let { "%.0f".format(it * 60.0) } ?: "n/a",
                input.pkpdRuntime?.params?.peakMin?.let { "%.0f".format(it) } ?: "n/a",
                (input.pkpdRuntime?.tailFraction ?: 0.0) * 100.0,
                activityPct,
                activityStage,
                anticipationPct,
                freshnessPct,
                input.pkpdRuntime?.fusedIsf?.let { "%.0f".format(it) } ?: "n/a",
                input.pkpdRuntime?.profileIsf?.let { "%.0f".format(it) } ?: "n/a",
                input.pkpdRuntime?.tddIsf?.let { "%.0f".format(it) } ?: "n/a",
                input.pkpdRuntime?.pkpdScale ?: Double.NaN
            )
        )

        val bypassTag = if (audit?.mealBypass == true) " [BYPASS]" else ""
        val highBgTag = if (highBgOverrideFlag) " (HighBG override)" else ""
        if (audit != null) {
            input.rT.reason.append(
                "\nSMB: proposed=%.2f → damped=%.2f [tail%s×%.2f (relief=%.0f%%, %s), ex%s×%.2f, late%s×%.2f] → quantized=%.2f%s%s".format(
                    smbDecision,
                    dampedRaw,
                    if (audit.tailApplied) "✔" else "✘", audit.tailMult,
                    audit.activityRelief * 100.0,
                    audit.activityStage.name,
                    if (audit.exerciseApplied) "✔" else "✘", audit.exerciseMult,
                    if (audit.lateFatApplied) "✔" else "✘", audit.lateFatMult,
                    quantized,
                    highBgTag,
                    bypassTag,
                    smbAfterDamping,     // après override
                    quantized,
                    highBgTag
                )
            )
        } else {
            input.rT.reason.append(
                "\nSMB: proposed=%.2f → damped=%.2f → quantized=%.2f%s%s".format(
                    smbDecision,
                    dampedRaw,
                    quantized,
                    highBgTag,
                    bypassTag,
                    smbAfterDamping,     // après override
                    quantized,
                    highBgTag
                )
            )
        }

        hooks.logDataMl(predictedSmb, finalSmb)
        hooks.logData(predictedSmb, finalSmb)

        input.pkpdRuntime?.let { runtime ->
            val dateStr = input.dateUtil.dateAndTimeString(input.currentTime)
            val epochMin = TimeUnit.MILLISECONDS.toMinutes(input.currentTime)
            val tailMultLog = audit?.tailMult
            val exMultLog = audit?.exerciseMult
            val lateMultLog = audit?.lateFatMult

            PkPdCsvLogger.append(
                PkPdLogRow(
                    dateStr = dateStr,
                    epochMin = epochMin,
                    bg = input.bg,
                    delta5 = input.delta,
                    iobU = input.iob.toDouble(),
                    carbsActiveG = input.cob.toDouble(),
                    windowMin = input.windowSinceDoseInt,
                    diaH = runtime.params.diaHrs,
                    peakMin = runtime.params.peakMin,
                    fusedIsf = runtime.fusedIsf,
                    tddIsf = runtime.tddIsf,
                    profileIsf = runtime.profileIsf,
                    tailFrac = runtime.tailFraction,
                    smbProposedU = smbDecision.toDouble(),
                    smbFinalU = quantized,
                    tailMult = tailMultLog,
                    exerciseMult = exMultLog,
                    lateFatMult = lateMultLog,
                    highBgOverride = highBgOverrideFlag,
                    lateFatRise = input.lateFatRiseFlag,
                    quantStepU = input.insulinStep.toDouble(),
                    activityStage = activityStage,
                    activityRelief = audit?.activityRelief,
                    activityFraction = activity?.relativeActivity,
                    anticipation = activity?.anticipationWeight
                )
            )
        }

        return Result(
            predictedSmb = predictedSmb,
            basal = basal,
            finalSmb = finalSmb,
            highBgOverrideUsed = highBgOverrideUsed,
            newSmbInterval = newInterval
        )
    }
}

