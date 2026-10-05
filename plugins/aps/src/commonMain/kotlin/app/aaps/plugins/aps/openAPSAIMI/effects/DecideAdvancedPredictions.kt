package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import kotlin.math.max
import kotlin.math.min

/**
 * Reads of the advanced curve publish.
 * Virtual COB is [decideEstimateUndeclaredVirtualCob]. The iOS tick calls this function when the
 * preference is on, so the estimated grams are the `cobG` of the same curve Android publishes.
 */
internal interface AimiAdvancedPredictionCalls {
    fun nowMs(): Long
    fun virtualCob(
        bg: Double,
        delta: Float,
        sens: Double,
        profile: OapsProfileAimi,
        mealData: MealData,
        declaredOrAdvisorCob: Double,
    ): Double
    fun recordSoftFloor(curves: AdvancedPredictionCurves): PkpdSoftFloorTelemetry
    fun writeCurves(curves: AdvancedPredictionCurves)
    fun writePredictionSize(size: Int)
    fun writePredictionAvailable(available: Boolean)
    fun writeEventualSnapshot(value: Double)
    fun writePredictedBg(value: Float)
    fun logError(message: String)
}

internal fun decideApplyAdvancedPredictions(
    bg: Double,
    delta: Float,
    sens: Double,
    iobDataArray: Array<IobTotal>,
    mealData: MealData,
    profile: OapsProfileAimi,
    rT: RT,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiAdvancedPredictionCalls,
) {
    try {
        calls.logError("🔮 PREDICT INIT: BG=$bg Delta=$delta Sens=${aimiFmt1(sens)} IOB=${iobDataArray.firstOrNull()?.iob}")
        val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val isFreshAdvisor = (calls.nowMs() - advisorTime) < 60 * 60000
        val declaredOrAdvisorCob = if (mealData.mealCOB > 0) mealData.mealCOB else if (isFreshAdvisor) advisorCarbs else 0.0
        // Undeclared-meal virtual COB (TBR anticipation only; never SMB). Off by default, and
        // only added on top of declared/advisor COB when no explicit carbs are present.
        val virtualCob = calls.virtualCob(bg, delta, sens, profile, mealData, declaredOrAdvisorCob)
        val effectiveCOB = declaredOrAdvisorCob + virtualCob
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = bg, iobArray = iobDataArray, finalSensitivity = sens,
            cobG = effectiveCOB, profile = profile, delta = delta.toDouble(),
            endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
            hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
            stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
        )
        calls.writeCurves(curves)
        val softFloor = calls.recordSoftFloor(curves)
        fun sanitizeCurve(points: List<Double>): List<Int> =
            points.mapNotNull {
                if (it.isNaN()) null else AimiTickPolicyMath.round(min(401.0, max(39.0, it)), 0).toInt()
            }
        val iobInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeCurve(curves.iob), softFloor)
        val cobInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeCurve(curves.cob), softFloor)
        val uamInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeCurve(curves.uam), softFloor)
        val ztInts = AimiTickPolicyMath.applySoftFloorToPredSeries(sanitizeCurve(curves.zt), softFloor)
        val hybridInts = sanitizeCurve(curves.hybrid)
        val intsPredictions = hybridInts
        calls.writePredictionSize(intsPredictions.size)
        calls.writePredictionAvailable(intsPredictions.isNotEmpty())
        if (intsPredictions.isNotEmpty()) {
            val lastPred = intsPredictions.last().toDouble()
            val minPred = intsPredictions.minOrNull()?.toDouble() ?: bg
            val uamTerminal = uamInts.lastOrNull()?.toDouble()
            calls.writeEventualSnapshot(lastPred)
            rT.eventualBG = lastPred
            calls.writePredictedBg(lastPred.toFloat())
            rT.predBGs = Predictions().apply {
                IOB = iobInts
                COB = cobInts
                ZT = ztInts
                UAM = uamInts
            }
            calls.logError("🔮 PREDICT GRAPH: IOB=${iobInts.size} COB=${cobInts.size} UAM=${uamInts.size}")
            calls.logError("minGuardBG ${minPred.toInt()} IOBpredBG ${lastPred.toInt()} UAMterm=${uamTerminal?.toInt() ?: "n/a"}")
            if (uamInts.size < 6) calls.logError("⚠ WARNING: UAM Series too short (<6) for Graph!")
            consoleLog.add(
                "PRED_SET size=${intsPredictions.size} eventual=${lastPred.toInt()} min=${minPred.toInt()} " +
                    "uamT=${uamTerminal?.toInt() ?: "n/a"} source=AdvancedCurves",
            )
        } else {
            calls.logError("🔮 PREDICT WARNING: Empty prediction list returned. Using Fallback.")
            val fallbackList = listOf(bg.toInt(), bg.toInt(), bg.toInt())
            rT.predBGs = Predictions().apply {
                IOB = fallbackList; COB = fallbackList; ZT = fallbackList; UAM = fallbackList
            }
            rT.eventualBG = bg
            calls.writePredictedBg(bg.toFloat())
            consoleLog.add("PRED_SET size=3 eventual=${bg.toInt()} min=${bg.toInt()} source=FallbackBG")
        }
    } catch (e: Exception) {
        calls.logError("🔮 PREDICT ERROR: ${e.message}")
        e.printStackTrace()
        consoleLog.add("Advanced prediction failed (${e::class.simpleName}): ${e.message} — curves kept")
    }
    consoleLog.add("Prédiction avancée avec ISF final de ${aimiFmt1(sens)} (Avancé)")
}

/**
 * Profile carried into [decideApplyAdvancedPredictions] for the virtual-COB scenes.
 * [AdvancedPredictionEngine.predictCurves] reads [OapsProfileAimi.carb_ratio] only.
 * The other fields are the shell probe's unset values, except basal, dia, target and the LGS
 * threshold, which the shell stub sets.
 */
internal fun virtualCobCurveProfile(carbRatio: Double) = OapsProfileAimi(
    dia = 5.0,
    min_5m_carbimpact = 0.0,
    max_iob = 0.0,
    max_daily_basal = 1.0,
    max_basal = 3.0,
    min_bg = 0.0,
    max_bg = 0.0,
    target_bg = 100.0,
    carb_ratio = carbRatio,
    sens = 50.0,
    autosens_adjust_targets = false,
    max_daily_safety_multiplier = 0.0,
    current_basal_safety_multiplier = 0.0,
    high_temptarget_raises_sensitivity = false,
    low_temptarget_lowers_sensitivity = false,
    sensitivity_raises_target = false,
    resistance_lowers_target = false,
    adv_target_adjustments = false,
    exercise_mode = false,
    half_basal_exercise_target = 0,
    maxCOB = 0,
    skip_neutral_temps = false,
    remainingCarbsCap = 0,
    enableUAM = false,
    A52_risk_enable = false,
    SMBInterval = 0,
    enableSMB_with_COB = false,
    enableSMB_with_temptarget = false,
    allowSMB_with_high_temptarget = false,
    enableSMB_always = false,
    enableSMB_after_carbs = false,
    maxSMBBasalMinutes = 0,
    maxUAMSMBBasalMinutes = 0,
    bolus_increment = 0.0,
    carbsReqThreshold = 0,
    current_basal = 1.0,
    temptargetSet = false,
    autosens_max = 0.0,
    out_units = "",
    lgsThreshold = 70,
    variable_sens = 0.0,
    insulinDivisor = 0,
    TDD = 0.0,
    peakTime = 0.0,
    futureActivity = 0.0,
    sensorLagActivity = 0.0,
    historicActivity = 0.0,
    currentActivity = 0.0,
)
