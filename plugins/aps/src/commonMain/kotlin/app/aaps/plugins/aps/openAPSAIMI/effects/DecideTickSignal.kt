package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair

/**
 * One call for each Android function from signal preparation through the hard brake, at that line.
 * Field reads that a stage can change (`bg`, `delta`, the averages, `targetBg`, `predictedBg`)
 * are ports at the line that uses them.
 */
internal interface AimiTickSignalCalls {
    fun signalPrep(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        combinedDelta: Float,
        tdd7P: Double,
        isExplicitAdvisorRun: Boolean,
        isConfirmedHighRiseLocal: Boolean,
        pkpdRuntime: PkPdRuntime?,
    ): AimiTickSignalStep

    fun trajectoryPrep(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        iobData: IobTotal,
        physioMultipliers: PhysioMultipliersMTR,
        insulinActionState: InsulinActionState,
        pkpdRuntime: PkPdRuntime?,
        tdd7Days: Double,
        tdd7P: Double,
        tdd24Hrs: Float,
        pbolusA: Double,
        pbolusAS: Double,
        reason: StringBuilder,
        isExplicitAdvisorRun: Boolean,
    ): AimiTickTrajectoryPrep

    fun wearableSnapshot(): HealthContextSnapshot
    fun bg(): Double
    fun delta(): Float
    fun predictedBg(): Float
    fun shortAvgDelta(): Float
    fun longAvgDelta(): Float
    fun targetBg(): Float

    fun advancedPredictions(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        sens: Double,
        predictedBg: Float,
        glucoseStatus: GlucoseStatusAIMI,
        minAgo: Double,
        isExplicitAdvisorRun: Boolean,
        physioMultipliers: PhysioMultipliersMTR,
        iobData: IobTotal,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        combinedDelta: Float,
    ): AimiTickPredPrep

    fun safetyHalt(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        combinedDelta: Float,
        iobData: IobTotal,
        glucoseStatus: GlucoseStatusAIMI,
        scenario: ScenarioProjectionPair,
        isExplicitAdvisorRun: Boolean,
    ): RT?

    fun hasRecentBolus45m(lastBolusTimeMs: Long): Boolean

    fun mealAdvisor(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        iobData: IobTotal,
        modesCondition: Boolean,
        isExplicitAdvisorRun: Boolean,
        lastBolusTimeMs: Long?,
        autodriveDisplay: String,
        hasRecentBolus45m: Boolean,
    ): RT?

    fun hardBrake(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        targetBgMgdl: Float,
    ): RT?
}

internal sealed class AimiTickSignalStep {
    class StaleAbort(val rT: RT) : AimiTickSignalStep()
    class Continue(val data: AimiTickSignalData) : AimiTickSignalStep()
}

internal data class AimiTickSignalData(
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

internal data class AimiTickTrajectoryPrep(
    val sens: Double,
    val baseSensitivity: Double,
    val contextTargetOverride: Double?,
    val dynamicPbolusSmall: Double,
)

internal data class AimiTickPredPrep(
    val minBg: Double,
    val threshold: Double,
    val scenario: ScenarioProjectionPair,
)

internal sealed class AimiTickSignalOutcome {
    class ReturnEarly(val rT: RT) : AimiTickSignalOutcome()
    class Continue(
        val modesCondition: Boolean,
        val reason: StringBuilder,
        val recentBGs: List<Float>,
        val totalBolusLastHour: Double,
        val autosensRatio: Double,
        val iobData: IobTotal,
        val lateFatRiseFlag: Boolean,
        val tdd24Hrs: Float,
        val minAgo: Double,
        val windowSinceDoseInt: Int,
        val pkpdRuntime: PkPdRuntime?,
        val systemTime: Long,
        val bgTime: Long,
        val sens: Double,
        val baseSensitivity: Double,
        val contextTargetOverride: Double?,
        val dynamicPbolusSmall: Double,
        val wearableSnapshot: HealthContextSnapshot,
        val minBg: Double,
        val threshold: Double,
        val hasRecentBolus45m: Boolean,
    ) : AimiTickSignalOutcome()
}

/**
 * From [runSignalPreparationPkpdRuntimePhase] through the return of [runHardBrakeLyraOrReturn].
 * A wearable snapshot failure is [OptionalSignal.Failed], a console line, and an empty [HealthContextSnapshot].
 */
internal fun decideDetermineBasalTickSignal(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    combinedDelta: Float,
    tdd7P: Double,
    tdd7Days: Double,
    isExplicitAdvisorRun: Boolean,
    isConfirmedHighRiseLocal: Boolean,
    pkpdRuntimeIn: PkPdRuntime?,
    physioMultipliers: PhysioMultipliersMTR,
    insulinActionState: InsulinActionState,
    autodriveDisplay: String,
    consoleLog: MutableList<String>,
    calls: AimiTickSignalCalls,
): AimiTickSignalOutcome {
    val signal = when (
        val outcome = calls.signalPrep(
            ctx = ctx,
            profile = profile,
            rT = rT,
            glucoseStatus = glucoseStatus,
            combinedDelta = combinedDelta,
            tdd7P = tdd7P,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
            pkpdRuntime = pkpdRuntimeIn,
        )
    ) {
        is AimiTickSignalStep.StaleAbort -> return AimiTickSignalOutcome.ReturnEarly(outcome.rT)
        is AimiTickSignalStep.Continue -> outcome.data
    }
    val systemTime = ctx.currentTime
    val bgTime = glucoseStatus.date
    val trajectory = calls.trajectoryPrep(
        ctx = ctx,
        profile = profile,
        rT = rT,
        iobData = signal.iobData,
        physioMultipliers = physioMultipliers,
        insulinActionState = insulinActionState,
        pkpdRuntime = signal.pkpdRuntime,
        tdd7Days = tdd7Days,
        tdd7P = tdd7P,
        tdd24Hrs = signal.tdd24Hrs,
        pbolusA = signal.pbolusA,
        pbolusAS = signal.pbolusAS,
        reason = signal.reason,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
    )
    val wearableSignal = readRbtOptional(
        source = "wearableSnapshot",
        consoleLog = consoleLog,
        failureLine = { errorType, message ->
            "WEARABLE snapshot failed ($errorType): ${message.orEmpty()} — snapshot empty"
        },
    ) { calls.wearableSnapshot() }
    val wearableSnapshot = wearableSignal.valueOrNull() ?: HealthContextSnapshot()
    val predictions = calls.advancedPredictions(
        ctx = ctx,
        profile = profile,
        rT = rT,
        bg = calls.bg(),
        delta = calls.delta(),
        sens = trajectory.sens,
        predictedBg = calls.predictedBg(),
        glucoseStatus = glucoseStatus,
        minAgo = signal.minAgo,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        physioMultipliers = physioMultipliers,
        iobData = signal.iobData,
        stepsLast15m = wearableSnapshot.stepsLast15m,
        heartRateBpm = wearableSnapshot.hrNow,
        restingHeartRateBpm = wearableSnapshot.rhrResting,
        combinedDelta = combinedDelta,
    )
    calls.safetyHalt(
        ctx = ctx,
        profile = profile,
        rT = rT,
        bg = calls.bg(),
        delta = calls.delta(),
        combinedDelta = combinedDelta,
        iobData = signal.iobData,
        glucoseStatus = glucoseStatus,
        scenario = predictions.scenario,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
    )?.let { return AimiTickSignalOutcome.ReturnEarly(it) }
    val hasRecentBolus45m = calls.hasRecentBolus45m(signal.lastBolusTimeMs ?: 0L)
    calls.mealAdvisor(
        ctx = ctx,
        profile = profile,
        rT = rT,
        bg = calls.bg(),
        delta = calls.delta(),
        iobData = signal.iobData,
        modesCondition = signal.modesCondition,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        lastBolusTimeMs = signal.lastBolusTimeMs,
        autodriveDisplay = autodriveDisplay,
        hasRecentBolus45m = hasRecentBolus45m,
    )?.let { return AimiTickSignalOutcome.ReturnEarly(it) }
    calls.hardBrake(
        ctx = ctx,
        profile = profile,
        rT = rT,
        bg = calls.bg(),
        delta = calls.delta(),
        shortAvgDelta = calls.shortAvgDelta(),
        longAvgDelta = calls.longAvgDelta(),
        targetBgMgdl = calls.targetBg(),
    )?.let { return AimiTickSignalOutcome.ReturnEarly(it) }
    return AimiTickSignalOutcome.Continue(
        modesCondition = signal.modesCondition,
        reason = signal.reason,
        recentBGs = signal.recentBGs,
        totalBolusLastHour = signal.totalBolusLastHour,
        autosensRatio = signal.autosensRatio,
        iobData = signal.iobData,
        lateFatRiseFlag = signal.lateFatRiseFlag,
        tdd24Hrs = signal.tdd24Hrs,
        minAgo = signal.minAgo,
        windowSinceDoseInt = signal.windowSinceDoseInt,
        pkpdRuntime = signal.pkpdRuntime,
        systemTime = systemTime,
        bgTime = bgTime,
        sens = trajectory.sens,
        baseSensitivity = trajectory.baseSensitivity,
        contextTargetOverride = trajectory.contextTargetOverride,
        dynamicPbolusSmall = trajectory.dynamicPbolusSmall,
        wearableSnapshot = wearableSnapshot,
        minBg = predictions.minBg,
        threshold = predictions.threshold,
        hasRecentBolus45m = hasRecentBolus45m,
    )
}
