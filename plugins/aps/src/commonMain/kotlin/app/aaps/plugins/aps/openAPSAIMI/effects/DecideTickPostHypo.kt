package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves

/**
 * One call for each Android function from post-hypo classification through the drift terminator,
 * at that line. `eventualBG` and `bg` are read where the reference reads the fields.
 * The Autodrive preference is read twice, at the two RBT conditions.
 */
internal interface AimiTickPostHypoCalls {
    fun classify(
        ctx: AimiTickContext,
        recentBGs: List<Float>,
        reason: StringBuilder,
    ): AimiPostHypoClassified

    fun refreshPostHypo(
        ctx: AimiTickContext,
        recentBGs: List<Float>,
        reason: StringBuilder,
    )

    fun eventualBg(): Double
    fun bg(): Double
    fun targetBg(): Double

    fun publishDoseTerminal(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        pkpdEventualMgdl: Double,
        pkpdPredTerminalMgdl: Double,
        targetBgMgdl: Double,
    )

    fun tdd24hForExport(): Double?

    fun wireRbt(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        tdd24hU: Double,
        wearableSnapshot: HealthContextSnapshot,
    )

    fun autodriveV3(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        hypoThresholdMgdl: Double,
        pkpdRuntime: PkPdRuntime?,
    ): AutodriveV3BranchResult

    fun rbtResolvedThisTick(): Boolean

    fun applyPendingSpiral(rT: RT)

    fun compressionAndDrift(
        ctx: AimiTickContext,
        rT: RT,
        threshold: Double,
        postHypoState: PostHypoState,
        autosensRatio: Double,
        nightbis: Boolean,
        autodriveEnabledPref: Boolean,
        modesCondition: Boolean,
        hasRecentBolus45m: Boolean,
        totalBolusLastHour: Double,
        dynamicPbolusSmall: Double,
        reason: StringBuilder,
    ): RT?
}

internal data class AimiPostHypoClassified(
    val postHypoState: PostHypoState,
    val estimatedCarbs: Double,
    val estimatedCarbsTimeMs: Long,
)

internal sealed class AimiTickPostHypoOutcome {
    class ReturnEarly(val rT: RT) : AimiTickPostHypoOutcome()
    class Continue(
        val postHypoState: PostHypoState,
        val estimatedCarbs: Double,
        val estimatedCarbsTimeMs: Long,
        val skipLegacySmbBlender: Boolean,
    ) : AimiTickPostHypoOutcome()
}

/**
 * From post-hypo classification through the return of
 * [runPostHypoCompressionAndDriftTerminatorOrReturn].
 * `resolveAndWireRbtLiveTick` and `runAutodriveV3MultiVariableBranch` stay ports.
 */
internal fun decideDetermineBasalTickPostHypo(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    recentBGs: List<Float>,
    reason: StringBuilder,
    tdd24Hrs: Float,
    wearableSnapshot: HealthContextSnapshot,
    threshold: Double,
    autosensRatio: Double,
    nightbis: Boolean,
    autodriveEnabledPref: Boolean,
    modesCondition: Boolean,
    hasRecentBolus45m: Boolean,
    totalBolusLastHour: Double,
    dynamicPbolusSmall: Double,
    pkpdRuntime: PkPdRuntime?,
    preferences: Preferences,
    calls: AimiTickPostHypoCalls,
): AimiTickPostHypoOutcome {
    val classified = calls.classify(ctx, recentBGs, reason)
    calls.refreshPostHypo(ctx, recentBGs, reason)

    val earlyPkpdEventual =
        calls.eventualBg().takeIf { it.isFinite() && it > 1.0 }
            ?: rT.eventualBG?.takeIf { it.isFinite() && it > 1.0 }
            ?: calls.bg()
    val earlyPkpdMinPred = minPredictedAcrossCurves(rT.predBGs) ?: earlyPkpdEventual
    calls.publishDoseTerminal(
        ctx = ctx,
        profile = profile,
        rT = rT,
        pkpdEventualMgdl = earlyPkpdEventual,
        pkpdPredTerminalMgdl = earlyPkpdMinPred,
        targetBgMgdl = calls.targetBg(),
    )

    val tdd24hForRbt = calls.tdd24hForExport()
        ?: tdd24Hrs.takeIf { it > 0f }?.toDouble()
        ?: (profile.max_daily_basal * 24.0).coerceAtLeast(1.0)
    if (!preferences.get(BooleanKey.OApsAIMIautoDriveActive)) {
        calls.wireRbt(
            ctx = ctx,
            profile = profile,
            rT = rT,
            tdd24hU = tdd24hForRbt,
            wearableSnapshot = wearableSnapshot,
        )
    }

    val v3Branch = calls.autodriveV3(
        ctx = ctx,
        profile = profile,
        rT = rT,
        hypoThresholdMgdl = threshold,
        pkpdRuntime = pkpdRuntime,
    )
    if (preferences.get(BooleanKey.OApsAIMIautoDriveActive) && !calls.rbtResolvedThisTick()) {
        calls.wireRbt(
            ctx = ctx,
            profile = profile,
            rT = rT,
            tdd24hU = tdd24hForRbt,
            wearableSnapshot = wearableSnapshot,
        )
    }
    calls.applyPendingSpiral(rT)

    calls.compressionAndDrift(
        ctx = ctx,
        rT = rT,
        threshold = threshold,
        postHypoState = classified.postHypoState,
        autosensRatio = autosensRatio,
        nightbis = nightbis,
        autodriveEnabledPref = autodriveEnabledPref,
        modesCondition = modesCondition,
        hasRecentBolus45m = hasRecentBolus45m,
        totalBolusLastHour = totalBolusLastHour,
        dynamicPbolusSmall = dynamicPbolusSmall,
        reason = reason,
    )?.let { return AimiTickPostHypoOutcome.ReturnEarly(it) }

    return AimiTickPostHypoOutcome.Continue(
        postHypoState = classified.postHypoState,
        estimatedCarbs = classified.estimatedCarbs,
        estimatedCarbsTimeMs = classified.estimatedCarbsTimeMs,
        skipLegacySmbBlender = v3Branch.skipLegacySmbBlender,
    )
}
