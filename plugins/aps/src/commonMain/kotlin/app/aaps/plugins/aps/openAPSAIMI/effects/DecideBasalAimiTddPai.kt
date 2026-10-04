package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3

/** Hour, pregnancy flag and the basal/ISF fields this stage writes. */
internal data class AimiBasalPaiStage(
    val timenowHour: Int,
    val sixAMHour: Int,
    val pregnancyEnable: Boolean,
)

/** Field reads and writes of `runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf`, each at its line. */
internal interface AimiBasalPaiState {
    fun basalAimi(): Float
    fun setBasalAimi(value: Float)
    fun ci(): Float
    fun setCi(value: Float)
    fun aimiLimit(): Float
    fun setAimiLimit(value: Float)
    fun adaptiveMult(): Double
    fun setVariableSensitivity(value: Float)
}

/** `basalDecisionEngine.smoothBasalRate`. */
internal fun interface AimiSmoothBasalRate {
    fun smooth(tddRecent: Float, tddPrevious: Float, currentBasalRate: Float): Float
}

/** `unifiedReactivityLearner.globalFactor`, read only on the hour branches. */
internal fun interface AimiUnifiedReactivityFactor {
    fun globalFactor(): Double
}

/**
 * `runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf`.
 *
 * TDD basal, carb limit, pregnancy TIR scale, acceleration boost, early basal and PAI ISF decide
 * here. Smoothing, the local hour and the unified reactivity factor stay ports, called at the same
 * lines. This function has no swallowed [Exception].
 */
internal fun decideBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf(
    glucoseStatus: GlucoseStatusAIMI,
    profile: OapsProfileAimi,
    profileCurrentBasal: Double,
    bg: Double,
    delta: Float,
    tdd7Days: Double,
    tdd7P: Double,
    paiBaseSensitivity: Double,
    honeymoon: Boolean,
    tirbasal3B: Double?,
    tirbasal3IR: Double?,
    tirbasal3A: Double?,
    tirbasalhAP: Double?,
    lastHourTIRAbove: Double?,
    iobPeakMinutes: Double,
    iobActivityIn30Min: Double,
    iobActivityNow: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    state: AimiBasalPaiState,
    smooth: AimiSmoothBasalRate,
    hour: AimiDecisionLocalHour,
    reactivity: AimiUnifiedReactivityFactor,
): AimiBasalPaiStage {
    if (tdd7Days.toFloat() != 0.0f) {
        state.setBasalAimi((tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat())
    } else {
        state.setBasalAimi(profileCurrentBasal.toFloat())
        consoleLog.add("TDDis 0 -> Baseline Basal: ${state.basalAimi()}U/h")
    }
    state.setBasalAimi(smooth.smooth(tdd7P.toFloat(), tdd7Days.toFloat(), state.basalAimi()))
    if (tdd7Days.toFloat() != 0.0f) {
        state.setCi((450 / tdd7Days).toFloat())
        @Suppress("UNUSED_VARIABLE")
        val learnedBasalForLimit = state.basalAimi() * state.adaptiveMult()
        state.setAimiLimit(
            (preferences.get(DoubleKey.OApsAIMICHO) / (450 / tdd7Days)).toFloat() * state.adaptiveMult().toFloat(),
        )
    }

    val choKey = preferences.get(DoubleKey.OApsAIMICHO)
    state.setAimiLimit(
        when {
            state.ci() != 0.0f && state.ci().isFinite() -> (choKey / state.ci()).toFloat()
            else -> (choKey / profile.carb_ratio).toFloat()
        },
    )

    if (state.adaptiveMult() != 1.0) {
        state.setAimiLimit(state.aimiLimit() * state.adaptiveMult().toFloat())
    }
    val timenowCaptured = hour.hour()
    val sixAMHourCaptured = 6

    val pregnancyEnableCaptured = preferences.get(BooleanKey.OApsAIMIpregnancy)

    if (tirbasal3B != null && pregnancyEnableCaptured && tirbasal3IR != null) {
        val useUnified = preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)

        state.setBasalAimi(
            when {
                tirbasalhAP != null && tirbasalhAP >= 5 -> (state.basalAimi() * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (state.basalAimi() * 1.8).toFloat()

                timenowCaptured < sixAMHourCaptured -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    val factor = if (useUnified) {
                        reactivity.globalFactor()
                    } else {
                        1.0
                    }
                    consoleLog.add("Reactivity (< 6AM): enabled=$useUnified, factor=${aimiFmt3(factor)}")
                    (state.basalAimi() * multiplier * factor).toFloat()
                }

                timenowCaptured > sixAMHourCaptured -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    val factor = if (useUnified) {
                        reactivity.globalFactor()
                    } else {
                        1.0
                    }
                    consoleLog.add("Reactivity (> 6AM): enabled=$useUnified, factor=${aimiFmt3(factor)}")
                    (state.basalAimi() * multiplier * factor).toFloat()
                }

                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0 -> (state.basalAimi() * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70 -> (state.basalAimi() * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5 -> (state.basalAimi() * 0.85).toFloat()
                else -> state.basalAimi()
            },
        )
    }

    state.setBasalAimi(
        if (honeymoon && state.basalAimi() > profileCurrentBasal * 2) {
            (profileCurrentBasal.toFloat() * 2)
        } else {
            state.basalAimi()
        },
    )

    state.setBasalAimi(if (state.basalAimi() < 0.0f) 0.0f else state.basalAimi())
    val deltaAcceleration = glucoseStatus.delta - glucoseStatus.shortAvgDelta
    if (deltaAcceleration > 1.5 && bg > 130) {
        val boostFactor = 1.2f
        state.setBasalAimi((state.basalAimi() * boostFactor).coerceAtMost(profile.max_basal.toFloat()))
        consoleLog.add("Basal boosté (+20%) pour accélération BG.")
    } else if (bg in 80.0..115.0 && glucoseStatus.delta > 1.0) {
        var earlyFactor = 1.0f
        if (deltaAcceleration > 0.5) {
            earlyFactor = 1.25f
            consoleLog.add("Early Basal: Accélération détectée en zone basse (+25%)")
        } else {
            earlyFactor = 1.15f
            consoleLog.add("Early Basal: Montée progressive (+15%)")
        }

        val safeCap = (profileCurrentBasal * 1.5).toFloat()
        state.setBasalAimi((state.basalAimi() * earlyFactor).coerceAtMost(safeCap))
    }
    var newVariableSensitivity = paiBaseSensitivity

    consoleLog.add("PAI Logic: Base ISF=${aimiFmt1(paiBaseSensitivity)}")

    if (delta > 1.5 && bg > 120) {
        val urgencyFactor = when {
            iobPeakMinutes > 45 || iobPeakMinutes < -30 -> {
                consoleLog.add("PAI: BG rising & IOB badly timed. AGGRESSIVE.")
                0.60
            }
            iobActivityIn30Min < iobActivityNow * 0.9 -> {
                consoleLog.add("PAI: BG rising & IOB activity will drop. PROACTIVE.")
                0.90
            }
            iobPeakMinutes in 0.0..45.0 -> {
                consoleLog.add("PAI: BG rising but IOB peak is coming. PATIENT.")
                1.0
            }
            else -> 1.0
        }
        newVariableSensitivity *= urgencyFactor
        if (urgencyFactor != 1.0) {
            consoleLog.add(
                "PAI: Urgency factor ${aimiFmt2(urgencyFactor)} applied. New ISF=${aimiFmt1(newVariableSensitivity)}",
            )
        }
    }

    if (delta in -1.0..1.5 && bg > 140) {
        if (iobActivityIn30Min < iobActivityNow * 0.8) {
            consoleLog.add("PAI: BG high/stable but IOB will fade. Anti-rebound.")
            newVariableSensitivity *= 0.95
        }
    }

    state.setVariableSensitivity(newVariableSensitivity.toFloat())

    return AimiBasalPaiStage(
        timenowHour = timenowCaptured,
        sixAMHour = sixAMHourCaptured,
        pregnancyEnable = pregnancyEnableCaptured,
    )
}
