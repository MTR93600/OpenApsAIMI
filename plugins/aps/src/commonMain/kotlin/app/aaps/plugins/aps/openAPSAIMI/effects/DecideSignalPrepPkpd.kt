package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AsyncDataState
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopPhase
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopTelemetry
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.MealAggressionContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdBolusSample
import app.aaps.plugins.aps.openAPSAIMI.valueOrNull

/**
 * Android reads and writes of [decideSignalPreparationPkpdRuntime], each at the line that uses it.
 * Meal clocks, the late-fat predicate, the meal context, and basal-first stay Android.
 */
internal interface AimiSignalPrepPkpdCalls {
    fun mealTime(): Boolean
    fun mealRuntime(): Long
    fun lunchTime(): Boolean
    fun lunchRuntime(): Long
    fun bfastTime(): Boolean
    fun bfastRuntime(): Long
    fun dinnerTime(): Boolean
    fun dinnerRuntime(): Long
    fun sportTime(): Boolean
    fun snackTime(): Boolean
    fun snackRuntime(): Long
    fun highCarbTime(): Boolean
    fun highCarbRuntime(): Long
    fun sleepTime(): Boolean
    fun lowCarbTime(): Boolean
    fun recentBgs(): List<Float>
    fun nowMs(): Long
    fun bolusesSince(startMs: Long, ascending: Boolean): List<BS>
    fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder)
    fun studyExporter(): HormonitorStudyExporter?
    fun bg(): Double
    fun predictedBg(): Float
    fun delta(): Float
    fun shortAvgDelta(): Float
    fun longAvgDelta(): Float
    fun iob(): Float
    fun cob(): Float
    fun maxSmb(): Double
    fun targetBg(): Float
    fun lateFatProteinRise(
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
    ): Boolean
    fun setLateFatRiseFlag(value: Boolean)
    fun tdd24hState(): AsyncDataState<Double>
    fun noteStaleData(minAgo: Double)
    fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float)
    fun ensurePredictionFallback(rT: RT, bg: Double)
    fun markFinalLoopDecision(rT: RT)
    fun internalLastSmbMillis(): Long
    fun setLastBolusAgeMinutes(minutes: Double)
    fun pkpdMealContext(mealData: MealData, predictedBgMgdl: Double, targetBgMgdl: Double): MealAggressionContext
    fun recentPkpdBolusSamples(nowMillis: Long, fallbackWindowMin: Int): List<PkpdBolusSample>
    fun uamConfidence(): Double
    fun physioLatentState(): PhysioLatentState?
    fun lastRa(): Double
    fun causalPosterior(): CausalStatePosterior?
    fun eventMemory(): PatientEventMemory?
    /** Keeps the original error log and writes the visible decision line. */
    fun logPkpdRuntimeFailure(error: Exception)
    fun setCachedPkpdRuntime(runtime: PkPdRuntime)
    fun applyBasalFirst(
        bg: Double,
        delta: Float,
        combinedDelta: Float,
        mealData: MealData,
        autosens: AutosensResult,
        isMealAdvisorOneShot: Boolean,
        targetBg: Double,
        rT: RT,
        isConfirmedHighRise: Boolean,
    )
}

internal sealed class AimiSignalPrepPkpd {
    data class StaleAbort(val rT: RT) : AimiSignalPrepPkpd()
    data class Continue(val data: AimiSignalPrepPkpdContinue) : AimiSignalPrepPkpd()
}

internal data class AimiSignalPrepPkpdContinue(
    val modesCondition: Boolean,
    val pbolusAS: Double,
    val pbolusA: Double,
    val reason: StringBuilder,
    val recentBGs: List<Float>,
    val totalBolusLastHour: Double,
    val autosensRatio: Double,
    val iobData: IobTotal,
    val lastBolusTimeMs: Long?,
    val lateFatRiseFlag: Boolean,
    val tdd24Hrs: Float,
    val minAgo: Double,
    val windowSinceDoseInt: Int,
    val pkpdRuntime: PkPdRuntime?,
)

/**
 * `runSignalPreparationPkpdRuntimePhase`.
 *
 * With a learned runtime, fragile glucose (BG under 110 and falling, no meal) sets the SMB
 * ceiling to 0 U. A thrown [Exception] from the runtime keeps that ceiling and continues
 * with a null runtime, and the failure is written to the decision log.
 */
internal fun decideSignalPreparationPkpdRuntime(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    combinedDelta: Float,
    tdd7P: Double,
    isExplicitAdvisorRun: Boolean,
    isConfirmedHighRiseLocal: Boolean,
    pkpdRuntimeIn: PkPdRuntime?,
    pkpdIntegration: PkPdIntegration,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiSignalPrepPkpdCalls,
): AimiSignalPrepPkpd {
    val modesCondition = (!calls.mealTime() || calls.mealRuntime() > 30) &&
        (!calls.lunchTime() || calls.lunchRuntime() > 30) &&
        (!calls.bfastTime() || calls.bfastRuntime() > 30) &&
        (!calls.dinnerTime() || calls.dinnerRuntime() > 30) &&
        !calls.sportTime() &&
        (!calls.snackTime() || calls.snackRuntime() > 30) &&
        (!calls.highCarbTime() || calls.highCarbRuntime() > 30) &&
        !calls.sleepTime() &&
        !calls.lowCarbTime()
    val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
    val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
    val reason = StringBuilder()
    val recentBGs = calls.recentBgs()

    val oneHourAgo = calls.nowMs() - (60 * 60 * 1000L)
    val bolusesHistory = calls.bolusesSince(oneHourAgo, true)
    val totalBolusLastHour = bolusesHistory.sumOf { it.amount }

    calls.calculateBgTrend(recentBGs, reason)

    val autosensRatio = if (ctx.autosensData.ratio != 1.0) ctx.autosensData.ratio else 1.0

    val systemTime = ctx.currentTime
    val iobArray = ctx.iobDataArray
    val iobData = iobArray[0]
    AimiLoopTelemetry.enterPhase(AimiLoopPhase.SIGNAL_PREPARATION, calls.studyExporter())

    val lastBolusTimeMs: Long? = iobData.lastBolusTime.takeIf { it > 0L }

    val lateFatRiseFlag = calls.lateFatProteinRise(
        bg = calls.bg(),
        predictedBg = calls.predictedBg().toDouble(),
        delta = calls.delta().toDouble(),
        shortAvgDelta = calls.shortAvgDelta().toDouble(),
        longAvgDelta = calls.longAvgDelta().toDouble(),
        iob = calls.iob().toDouble(),
        cob = calls.cob().toDouble(),
        maxSmb = calls.maxSmb(),
        lastBolusTimeMs = lastBolusTimeMs,
        mealTime = calls.mealTime(),
        bfastTime = calls.bfastTime(),
        lunchTime = calls.lunchTime(),
        dinnerTime = calls.dinnerTime(),
        highCarbTime = calls.highCarbTime(),
    )
    calls.setLateFatRiseFlag(lateFatRiseFlag)
    val tdd24hStateForPkpd = calls.tdd24hState()
    logPkpdCache(consoleLog, "TDD24H_PKPD", tdd24hStateForPkpd)
    var tdd24Hrs = tdd24hStateForPkpd.valueOrNull()?.toFloat() ?: 0.0f
    if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()
    val bgTime = glucoseStatus.date
    val minAgo = AimiTickPolicyMath.round((systemTime - bgTime) / 60.0 / 1000.0, 1)

    if (minAgo > 12.0) {
        reason.append("⚠️ Data Stale (${minAgo.toInt()}m) -> Logic Paused\n")
        calls.noteStaleData(minAgo)
        calls.logDecisionFinal("STALE_DATA", rT, calls.bg(), calls.delta())
        return AimiSignalPrepPkpd.StaleAbort(
            rT.also {
                calls.ensurePredictionFallback(it, calls.bg())
                calls.markFinalLoopDecision(it)
            }
        )
    }
    val windowSinceDoseMin = if (iobData.lastBolusTime > 0 || calls.internalLastSmbMillis() > 0) {
        val effectiveLastBolusTime = kotlin.math.max(iobData.lastBolusTime, calls.internalLastSmbMillis())
        ((systemTime - effectiveLastBolusTime) / 60000.0).coerceAtLeast(0.0)
    } else 0.0
    val windowSinceDoseInt = windowSinceDoseMin.toInt()
    calls.setLastBolusAgeMinutes(windowSinceDoseMin)
    val carbsActiveG = ctx.mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val pkpdMealContext = calls.pkpdMealContext(
        mealData = ctx.mealData,
        predictedBgMgdl = calls.predictedBg().toDouble(),
        targetBgMgdl = calls.targetBg().toDouble(),
    )
    pkpdIntegration.setRecentBolusSamples(
        calls.recentPkpdBolusSamples(
            nowMillis = ctx.currentTime,
            fallbackWindowMin = windowSinceDoseInt,
        )
    )
    val pkpdRuntimeTemp = try {
        pkpdIntegration.computeRuntime(
            epochMillis = ctx.currentTime,
            bg = calls.bg(),
            deltaMgDlPer5 = calls.delta().toDouble(),
            iobU = calls.iob().toDouble(),
            carbsActiveG = carbsActiveG,
            windowMin = windowSinceDoseInt,
            exerciseFlag = calls.sportTime(),
            profileIsf = profile.sens,
            tdd24h = tdd24Hrs.toDouble(),
            mealContext = pkpdMealContext,
            consoleLog = consoleLog,
            combinedDelta = combinedDelta.toDouble(),
            uamConfidence = calls.uamConfidence(),
            patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
            physioLatentState = calls.physioLatentState(),
            estimatedRaMgdlPerMin = calls.lastRa().takeIf { it.isFinite() && it > 0.0 },
            causalStatePosterior = calls.causalPosterior(),
            patientEventMemory = calls.eventMemory(),
            allowLearning = true,
        )
    } catch (e: Exception) {
        calls.logPkpdRuntimeFailure(e)
        null
    }

    var pkpdRuntime = pkpdRuntimeIn
    if (pkpdRuntimeTemp != null) {
        pkpdRuntime = pkpdRuntimeTemp
        if (preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath)) {
            calls.setCachedPkpdRuntime(pkpdRuntimeTemp)
        }

        consoleLog.add("📊 PKPD_LEARNER:")
        consoleLog.add("  │ DIA (learned): ${aimiFmt2(pkpdRuntime.params.diaHrs)}h")
        consoleLog.add("  │ Peak (learned): ${aimiFmt0(pkpdRuntime.params.peakMin)}min")
        consoleLog.add("  │ fusedISF: ${aimiFmt1(pkpdRuntime.fusedIsf)} mg/dL/U")

        calls.applyBasalFirst(
            bg = calls.bg(),
            delta = calls.delta().toFloat(),
            combinedDelta = combinedDelta.toFloat(),
            mealData = ctx.mealData,
            autosens = ctx.autosensData,
            isMealAdvisorOneShot = isExplicitAdvisorRun,
            targetBg = calls.targetBg().toDouble(),
            rT = rT,
            isConfirmedHighRise = isConfirmedHighRiseLocal,
        )
        consoleLog.add("  └ adaptiveMode: ${if (pkpdRuntime.params.diaHrs != 4.0 || pkpdRuntime.params.peakMin != 75.0) "ACTIVE" else "DEFAULT"}")
    }

    return AimiSignalPrepPkpd.Continue(
        AimiSignalPrepPkpdContinue(
            modesCondition = modesCondition,
            pbolusAS = pbolusAS,
            pbolusA = pbolusA,
            reason = reason,
            recentBGs = recentBGs,
            totalBolusLastHour = totalBolusLastHour,
            autosensRatio = autosensRatio,
            iobData = iobData,
            lastBolusTimeMs = lastBolusTimeMs,
            lateFatRiseFlag = lateFatRiseFlag,
            tdd24Hrs = tdd24Hrs,
            minAgo = minAgo,
            windowSinceDoseInt = windowSinceDoseInt,
            pkpdRuntime = pkpdRuntime,
        )
    )
}

private fun logPkpdCache(consoleLog: MutableList<String>, tag: String, state: AsyncDataState<*>) {
    val msg = when (state) {
        is AsyncDataState.Fresh<*> -> "CACHE $tag=FRESH ageMs=${state.ageMs}"
        is AsyncDataState.Stale<*> -> "CACHE $tag=STALE ageMs=${state.ageMs}"
        is AsyncDataState.Missing -> "CACHE $tag=MISSING reason=${state.reason}"
    }
    consoleLog.add("📦 $msg")
}
