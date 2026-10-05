package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.AimiBgFeatures
import app.aaps.plugins.aps.openAPSAIMI.NGRConfig
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime

/**
 * One call for each Android function the prefix already called, at that line.
 * `AimiDecisionContext` stays on the Android side, so the outcome carries it as [TDecision].
 */
internal interface AimiTickPrefixCalls<TDecision> {
    fun earlyStages(ctx: AimiTickContext): AimiEarlyTickOutcome
    fun bootstrapPhysiology(ctx: AimiTickContext, tdd7Days: Double): Boolean
    fun writeConfirmedHighRise(value: Boolean)
    fun decisionContext(ctx: AimiTickContext): AimiDecisionRtBootstrap<TDecision>
    fun realtimePhysio(ctx: AimiTickContext, decisionCtx: TDecision): AimiPrefixIob
    fun loadGlucose(ctx: AimiTickContext, rT: RT): AimiPrefixGlucose
    fun t9Bootstrap(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        rT: RT,
        iobTotal: Double,
    ): AimiT9PhysioPkpdTubeBootstrap
    fun cachedPkpdRuntime(): PkPdRuntime?
    fun combinedDelta(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        useLegacyDynamics: Boolean,
        reasonAimi: StringBuilder,
    ): AimiPrefixCombined
    fun autodriveBootstrap(ctx: AimiTickContext): AimiPrefixAutodrive
    fun tickClock(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        rT: RT,
        combinedDelta: Float,
    ): AimiTickClockTirCarbGlucoseBootstrap
    fun therapyGate(ctx: AimiTickContext, profile: OapsProfileAimi, rT: RT): AimiPrefixStep<Boolean>
    fun recentGlucose(): List<Float>
    fun refreshPostHypo(
        combinedDelta: Float,
        recentBGs: List<Float>,
        shortAvgDeltaAdj: Float,
        slopeFromMinDeviation: Double,
        reason: StringBuilder,
    )
    fun auditorIsf(ctx: AimiTickContext)
    fun mealModes(ctx: AimiTickContext, profile: OapsProfileAimi, rT: RT): AimiPrefixStep<String>
    fun t3cBrittle(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        originalProfile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        shortAvgDeltaAdj: Float,
        physioMultipliers: PhysioMultipliersMTR,
        insulinActionState: InsulinActionState,
    ): RT?
}

internal data class AimiPrefixIob(
    val iobTotal: Double,
    val iobPeakMinutes: Double,
    val iobActivityIn30Min: Double,
    val insulinActionState: InsulinActionState,
)

internal sealed class AimiPrefixGlucose {
    class Abort(val rT: RT) : AimiPrefixGlucose()
    class Continue(
        val glucoseStatus: GlucoseStatusAIMI,
        val features: AimiBgFeatures?,
    ) : AimiPrefixGlucose()
}

internal data class AimiPrefixCombined(
    val combinedDelta: Float,
    val shortAvgDeltaAdj: Float,
    val tp: Double,
)

internal data class AimiPrefixAutodrive(
    val isG6Byoda: Boolean,
    val autodriveEnabled: Boolean,
    val autodriveDisplay: String,
)

internal sealed class AimiPrefixStep<T> {
    class Stop<T>(val rT: RT) : AimiPrefixStep<T>()
    class Go<T>(val value: T) : AimiPrefixStep<T>()
}

internal sealed class AimiTickPrefixOutcome<TDecision> {
    class ReturnEarly<TDecision>(val rT: RT) : AimiTickPrefixOutcome<TDecision>()
    class T3cReturn<TDecision>(
        val rT: RT,
        val decisionCtx: TDecision,
        val pkpdRuntime: PkPdRuntime?,
    ) : AimiTickPrefixOutcome<TDecision>()
    class Continue<TDecision>(
        val originalProfile: OapsProfileAimi,
        val isExplicitAdvisorRun: Boolean,
        val tdd7P: Double,
        val tdd7Days: Double,
        val isConfirmedHighRiseLocal: Boolean,
        val decisionCtx: TDecision,
        val rT: RT,
        val flatBGsDetected: Boolean,
        val iobTotal: Double,
        val iobPeakMinutes: Double,
        val iobActivityIn30Min: Double,
        val insulinActionState: InsulinActionState,
        val glucoseStatus: GlucoseStatusAIMI,
        val features: AimiBgFeatures?,
        val pumpAgeDays: Float,
        val physioMultipliers: PhysioMultipliersMTR,
        val pkpdRuntime: PkPdRuntime?,
        val reasonAimi: StringBuilder,
        val combinedDelta: Float,
        val shortAvgDeltaAdj: Float,
        val tp: Double,
        val isG6Byoda: Boolean,
        val autodrive: Boolean,
        val autodriveDisplay: String,
        val honeymoon: Boolean,
        val ngrConfig: NGRConfig,
        val tir1DAYIR: Double,
        val lastHourTIRAbove: Double?,
        val tirbasal3IR: Double?,
        val tirbasal3B: Double?,
        val tirbasal3A: Double?,
        val tirbasalhAP: Double?,
        val circadianMinute: Int,
        val circadianSecond: Int,
        val bgAcceleration: Float,
        val nightbis: Boolean,
        val activeModeName: String,
    ) : AimiTickPrefixOutcome<TDecision>()
}

/**
 * From [runEarlyDetermineBasalStages] through the return of [runT3cBrittleBypassOrReturn].
 * The T3C export `runCatching` stays on the Android shell.
 */
internal fun <TDecision> decideDetermineBasalTickPrefix(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    calls: AimiTickPrefixCalls<TDecision>,
): AimiTickPrefixOutcome<TDecision> {
    val early = calls.earlyStages(ctx)
    val isConfirmedHighRiseLocal = calls.bootstrapPhysiology(ctx, early.tdd7Days)
    calls.writeConfirmedHighRise(isConfirmedHighRiseLocal)
    val decision = calls.decisionContext(ctx)
    val iob = calls.realtimePhysio(ctx, decision.decisionCtx)
    val glucoseStatus: GlucoseStatusAIMI
    val features: AimiBgFeatures?
    when (val glucose = calls.loadGlucose(ctx, decision.rT)) {
        is AimiPrefixGlucose.Abort -> return AimiTickPrefixOutcome.ReturnEarly(glucose.rT)
        is AimiPrefixGlucose.Continue -> {
            glucoseStatus = glucose.glucoseStatus
            features = glucose.features
        }
    }
    val t9 = calls.t9Bootstrap(ctx, glucoseStatus, decision.rT, iob.iobTotal)
    val pkpdRuntime = calls.cachedPkpdRuntime()
    val reasonAimi = StringBuilder()
    val useLegacyDynamics = pkpdRuntime == null
    val combined = calls.combinedDelta(ctx, glucoseStatus, useLegacyDynamics, reasonAimi)
    val autodrive = calls.autodriveBootstrap(ctx)
    val clock = calls.tickClock(ctx, glucoseStatus, decision.rT, combined.combinedDelta)
    val nightbis = when (val therapy = calls.therapyGate(ctx, profile, decision.rT)) {
        is AimiPrefixStep.Stop -> return AimiTickPrefixOutcome.ReturnEarly(therapy.rT)
        is AimiPrefixStep.Go -> therapy.value
    }
    calls.refreshPostHypo(
        combinedDelta = combined.combinedDelta,
        recentBGs = calls.recentGlucose(),
        shortAvgDeltaAdj = combined.shortAvgDeltaAdj,
        slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
        reason = StringBuilder(),
    )
    // Judges the auditor's ISF factor against this tick. It changes nothing here.
    calls.auditorIsf(ctx)
    val activeModeName = when (val mealModes = calls.mealModes(ctx, profile, decision.rT)) {
        is AimiPrefixStep.Stop -> return AimiTickPrefixOutcome.ReturnEarly(mealModes.rT)
        is AimiPrefixStep.Go -> mealModes.value
    }
    calls.t3cBrittle(
        ctx = ctx,
        profile = profile,
        rT = decision.rT,
        originalProfile = early.originalProfile,
        pkpdRuntime = pkpdRuntime,
        shortAvgDeltaAdj = combined.shortAvgDeltaAdj,
        physioMultipliers = t9.physioMultipliers,
        insulinActionState = iob.insulinActionState,
    )?.let { t3cResult ->
        return AimiTickPrefixOutcome.T3cReturn(t3cResult, decision.decisionCtx, pkpdRuntime)
    }
    return AimiTickPrefixOutcome.Continue(
        originalProfile = early.originalProfile,
        isExplicitAdvisorRun = early.isExplicitAdvisorRun,
        tdd7P = early.tdd7P,
        tdd7Days = early.tdd7Days,
        isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
        decisionCtx = decision.decisionCtx,
        rT = decision.rT,
        flatBGsDetected = decision.flatBGsDetected,
        iobTotal = iob.iobTotal,
        iobPeakMinutes = iob.iobPeakMinutes,
        iobActivityIn30Min = iob.iobActivityIn30Min,
        insulinActionState = iob.insulinActionState,
        glucoseStatus = glucoseStatus,
        features = features,
        pumpAgeDays = t9.pumpAgeDays,
        physioMultipliers = t9.physioMultipliers,
        pkpdRuntime = pkpdRuntime,
        reasonAimi = reasonAimi,
        combinedDelta = combined.combinedDelta,
        shortAvgDeltaAdj = combined.shortAvgDeltaAdj,
        tp = combined.tp,
        isG6Byoda = autodrive.isG6Byoda,
        autodrive = autodrive.autodriveEnabled,
        autodriveDisplay = autodrive.autodriveDisplay,
        honeymoon = clock.honeymoon,
        ngrConfig = clock.ngrConfig,
        tir1DAYIR = clock.tir1DAYIR,
        lastHourTIRAbove = clock.lastHourTIRAbove,
        tirbasal3IR = clock.tirbasal3IR,
        tirbasal3B = clock.tirbasal3B,
        tirbasal3A = clock.tirbasal3A,
        tirbasalhAP = clock.tirbasalhAP,
        circadianMinute = clock.circadianMinute,
        circadianSecond = clock.circadianSecond,
        bgAcceleration = clock.bgAcceleration,
        nightbis = nightbis,
        activeModeName = activeModeName,
    )
}
