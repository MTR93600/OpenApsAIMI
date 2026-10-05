package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair

/**
 * Android reads of the meal-absorption phase.
 * Each method is the field read that already existed at that line.
 * The late-fat predicate stays Android.
 */
internal interface AimiMealAbsorptionCalls {
    fun scenario(): ScenarioProjectionPair?
    fun bg(): Double
    fun predictedBg(): Double
    fun delta(): Double
    fun shortAvgDelta(): Double
    fun longAvgDelta(): Double
    fun iob(): Double
    fun cob(): Double
    fun maxSmb(): Double
    fun mealTime(): Boolean
    fun bfastTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun highCarbTime(): Boolean
    fun lateFat(
        bg: Double,
        predictedBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        iob: Double,
        cob: Double,
        maxSmb: Double,
        lastBolusTimeMs: Long?,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        nowMs: Long,
    ): Boolean
    fun targetBg(): Double
    fun deltaPrev(): Double?
    fun hourOfDay(): Int
    fun maxIob(): Double
    fun gapPrev(): Double?
    fun uamConfidence(): Double
    fun physiologicalPhase(): PhysiologicalPhase
    fun estimatedRa(): Double?
    fun writeOutput(output: MealAbsorptionPhaseEngine.Output)
}

internal fun decideRefreshMealAbsorptionPhase(
    combinedDelta: Float,
    stepsLast15m: Int,
    heartRateBpm: Int,
    restingHeartRateBpm: Int,
    mealContext: MealSafetyContext,
    lastBolusTimeMs: Long?,
    nowMs: Long,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiMealAbsorptionCalls,
): MealAbsorptionPhaseEngine.Output {
    val scenario = calls.scenario()
    val floorT = scenario?.clinicalFloor?.terminalMgdl ?: calls.bg()
    val bestT = scenario?.scenarioBest?.terminalMgdl ?: calls.bg()
    val lateFatRise = calls.lateFat(
        bg = calls.bg(),
        predictedBg = calls.predictedBg(),
        delta = calls.delta(),
        shortAvgDelta = calls.shortAvgDelta(),
        longAvgDelta = calls.longAvgDelta(),
        iob = calls.iob(),
        cob = calls.cob(),
        maxSmb = calls.maxSmb(),
        lastBolusTimeMs = lastBolusTimeMs,
        mealTime = calls.mealTime(),
        bfastTime = calls.bfastTime(),
        lunchTime = calls.lunchTime(),
        dinnerTime = calls.dinnerTime(),
        highCarbTime = calls.highCarbTime(),
        nowMs = nowMs,
    )
    val hoursSinceBolus = lastBolusTimeMs?.let { (nowMs - it) / 3_600_000.0 }
    val output = MealAbsorptionPhaseEngine.evaluate(
        MealAbsorptionPhaseEngine.Input(
            bgMgdl = calls.bg(),
            targetBgMgdl = calls.targetBg(),
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = calls.delta(),
            shortAvgDeltaMgdlPer5 = calls.shortAvgDelta(),
            combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
            deltaPrevMgdlPer5 = calls.deltaPrev(),
            mealCobG = calls.cob(),
            hourOfDay = calls.hourOfDay(),
            iobU = calls.iob(),
            maxIobU = calls.maxIob(),
            bestTerminalMgdl = bestT,
            floorTerminalMgdl = floorT,
            gapPrevMgdl = calls.gapPrev(),
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            stepsLast15m = stepsLast15m,
            uamConfidence = calls.uamConfidence(),
            mealIntent = mealContext.hasMealIntent,
            physiologicalPhase = calls.physiologicalPhase(),
            estimatedRa = calls.estimatedRa(),
            hoursSinceBolus = hoursSinceBolus,
            lateFatProteinRise = lateFatRise,
            nowMs = nowMs,
        ),
    )
    calls.writeOutput(output)
    if (output.phase.isActive) {
        consoleLog.add(
            "🍽️ MEAL_ABSORPTION: ${output.phase.name} B=${aimiFmt2(output.belief)} " +
                "pri=${output.mealDeliveryPriority} waves=${output.waveCount} (${output.reason})",
        )
    }
    return output
}
