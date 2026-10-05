package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT

internal sealed class AimiTherapyExerciseDecision {
    data class ReturnZeroBasal(val rT: RT) : AimiTherapyExerciseDecision()
    data class Continue(val nightbis: Boolean) : AimiTherapyExerciseDecision()
}

/**
 * Android reads of the therapy clocks and the exercise lockout.
 * Each method is the call that already existed at that line. `setTempBasal` stays Android.
 */
internal interface AimiTherapyExerciseCalls {
    fun hydrateClocks(): Boolean
    fun refreshActivity()
    fun sportTime(): Boolean
    fun aimiActivity(): Boolean
    fun setLockout(active: Boolean)
    fun refreshHyper(profile: OapsProfileAimi)
    fun lockout(): Boolean
    fun hyperOverride(): Boolean
    fun zeroMaxSmb()
    fun bg(): Double
    fun mealPriorityBypass(): Boolean
    fun t3cBrittle(): Boolean
    fun markExerciseSafety()
    fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float)
    fun delta(): Float
    fun setZeroTemp(profile: OapsProfileAimi, rT: RT, currentTemp: CurrentTemp): RT
}

internal fun decideTherapyExerciseLockout(
    profile: OapsProfileAimi,
    rT: RT,
    currentTemp: CurrentTemp,
    resumeBgMgdl: Double,
    consoleLog: MutableList<String>,
    calls: AimiTherapyExerciseCalls,
): AimiTherapyExerciseDecision {
    val nightbis = calls.hydrateClocks()
    calls.refreshActivity()
    val exerciseInsulinLockoutActive = calls.sportTime() || calls.aimiActivity()
    calls.setLockout(exerciseInsulinLockoutActive)
    calls.refreshHyper(profile)
    if (calls.lockout()) {
        calls.zeroMaxSmb()
        val basalHint = if (calls.hyperOverride()) {
            "basale renforcée (hyper+activité)"
        } else {
            "basale autorisée seulement si BG>${resumeBgMgdl.toInt()} (T3c PI ou flux standard)"
        }
        consoleLog.add(
            "🏃 EXERCISE_LOCKOUT[therapy]: SMB off (sportTime=${calls.sportTime()} aimiActivity=${calls.aimiActivity()}) | $basalHint"
        )
    }

    val mealPriorityBypass = calls.mealPriorityBypass()
    if (mealPriorityBypass && calls.lockout()) {
        consoleLog.add(
            "🍱 MEAL_PRIORITY: lockout exercice/activité contourné pour repas déclaré " +
                "(bg=${calls.bg().toInt()} > ${SEVERE_HYPO_MEAL_OVERRIDE_MGDL.toInt()}) — prebolus autorisé"
        )
    }

    val t3cBrittle = calls.t3cBrittle()
    if (t3cBrittle && calls.lockout() && !calls.hyperOverride() &&
        calls.bg() <= resumeBgMgdl && !mealPriorityBypass
    ) {
        calls.markExerciseSafety()
        rT.reason.append(
            "🏃 T3c + sport/contexte activité : basale & SMB arrêtés (BG≤${resumeBgMgdl.toInt()}).\n"
        )
        consoleLog.add("🏃 T3c EXERCISE: return 0 U/h basal (BG=${calls.bg().toInt()} ≤ ${resumeBgMgdl.toInt()})")
        rT.units = 0.0
        calls.logDecisionFinal("T3C_EXERCISE_LOCKOUT", rT, calls.bg(), calls.delta())
        return AimiTherapyExerciseDecision.ReturnZeroBasal(
            calls.setZeroTemp(profile, rT, currentTemp)
        )
    }
    if (!t3cBrittle && calls.lockout() && !calls.hyperOverride() &&
        calls.bg() <= resumeBgMgdl && !mealPriorityBypass
    ) {
        rT.reason.append(
            "🏃 Sport / contexte AIMI activité : basale & SMB arrêtés (BG≤${resumeBgMgdl.toInt()}).\n"
        )
        consoleLog.add("🏃 EXERCISE_LOCKOUT: flux standard interrompu → 0 U/h (BG=${calls.bg().toInt()})")
        rT.units = 0.0
        calls.logDecisionFinal("EXERCISE_LOCKOUT", rT, calls.bg(), calls.delta())
        return AimiTherapyExerciseDecision.ReturnZeroBasal(
            calls.setZeroTemp(profile, rT, currentTemp)
        )
    }
    return AimiTherapyExerciseDecision.Continue(nightbis)
}
