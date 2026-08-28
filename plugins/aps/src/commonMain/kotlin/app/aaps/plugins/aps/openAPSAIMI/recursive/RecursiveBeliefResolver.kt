package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSmbArbiter
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSmbAuthorityDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSmbAuthorityMode
import app.aaps.plugins.aps.openAPSAIMI.patient.InsulinIntent
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinLoadGovernor
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MR-7 clauses 5–6: TENSION, RESOLVE + priority rules P0–P4.
 */
object RecursiveBeliefResolver {

    data class Input(
        val ctx: RecursiveBeliefTickContext,
        val scales: List<BeliefScaleNode>,
        val authorityEnabled: Boolean,
    )

    fun resolve(input: Input): RecursiveBeliefSnapshot {
        val trace = mutableListOf<String>()
        trace += "OBSERVE:${input.scales.sumOf { it.leaves.size }}"

        val tensions = computeTensions(input.scales, trace)
        val paradoxes = RecursiveBeliefParadox.detect(input.ctx, input.scales, tensions)
        trace += "TENSION:${tensions.maxOfOrNull { it.magnitude } ?: 0.0}"

        val resolution = resolveChannels(input, paradoxes, tensions, trace)
        trace += "RESOLVE:${resolution.releaseAuthority}"
        val loadGovernorExport = resolution.loadGovernorExport

        return RecursiveBeliefSnapshot(
            scales = input.scales,
            tensions = tensions,
            paradoxes = paradoxes,
            resolutions = resolution,
            loadGovernor = loadGovernorExport,
            mr7Trace = trace,
            waveletBands = input.ctx.waveletBands,
        )
    }

    fun computeTensions(scales: List<BeliefScaleNode>): List<ScaleTension> =
        computeTensions(scales, mutableListOf())

    private fun computeTensions(scales: List<BeliefScaleNode>, trace: MutableList<String>): List<ScaleTension> {
        val sorted = scales.sortedBy { it.horizonMinutes }
        val out = mutableListOf<ScaleTension>()
        for (i in 1 until sorted.size) {
            val child = sorted[i - 1]
            val parent = sorted[i]
            val magnitude = abs(child.urgency - parent.urgency) *
                min(child.belief, parent.belief)
            val dominant = RecursiveBeliefParadox.dominantParadox(parent, child)
            out += ScaleTension(parent.horizonMinutes, child.horizonMinutes, magnitude, dominant)
            trace += "BELIEVE:${aimiFmt2(child.belief)}@${child.horizonMinutes}"
        }
        return out
    }

    // ── Private result types ────────────────────────────────────────────────────

    private data class MealHypothesis(
        val mealProb: Double,
        val nonMealProb: Double,
        val suppressMealInterpretation: Boolean,
        val mealWaveBoostAllowed: Boolean,
        val patternSoftCapAllowed: Boolean,
    )

    private data class AuthorityResult(
        val authority: ReleaseAuthority,
        val codes: List<String>,
    )

    private data class SmbDemandResult(
        val smbU: Double,
        val basalFirstChannel: BasalFirstChannel,
        val harmoniaSmb: HarmoniaSmbResolution?,
        val codes: List<String>,
    )

    private data class LoadGovernorBlock(
        val smbU: Double,
        val smbBeforeU: Double,
        val export: LoadGovernorExport,
        val tbrBoostNeeded: Boolean,
        val codes: List<String>,
    )

    private data class TerminalGuardResult(
        val authority: ReleaseAuthority,
        val smbU: Double,
        val harmoniaSmb: HarmoniaSmbResolution?,
        val codes: List<String>,
    )

    // ── Orchestrator (was ~400 lines; now delegates to named sub-functions) ────

    private fun resolveChannels(
        input: Input,
        paradoxes: List<BeliefParadox>,
        tensions: List<ScaleTension>,
        trace: MutableList<String>,
    ): DoseChannelResolution {
        val ctx = input.ctx
        val byTau = input.scales.associateBy { it.horizonMinutes }
        val u15 = byTau[15]?.urgency ?: 0.0
        val u60 = byTau[60]?.urgency ?: 0.0
        val u180 = byTau[180]?.urgency ?: 0.0
        val v3 = ctx.v3SmbU ?: 0.0
        val t3cBasalFirst = resolveT3cBasalFirst(ctx)
        val harmoniaBasalFirst = resolveHarmoniaBasalFirst(ctx)

        // P0 — Tier-1 hypo (non-negotiable)
        if (ctx.tier1Hypo) {
            val p0Channel = selectBasalFirstChannel(t3cBasalFirst, harmoniaBasalFirst, ReleaseAuthority.NONE)
            return DoseChannelResolution(
                smbDemandU = 0.0,
                tbrDemandFraction = 0.0,
                waitBias = 1.0,
                dominantScaleMinutes = 15,
                releaseAuthority = ReleaseAuthority.NONE,
                hypoGuardMode = HypoGuardMode.FULL,
                autodriveModeHint = AutodriveModeHint.SKIP,
                mealChannel = MealChannelHint.NORMAL,
                suppressTrajBasalShift = true,
                hypoMinPredIgnored = ctx.hypoMinPredIgnored,
                reasonCodes = listOf("P0"),
                basalFirstChannel = p0Channel,
                t3cBasalFirst = t3cBasalFirst,
                harmoniaBasalFirst = harmoniaBasalFirst,
                harmoniaSmb = resolveHarmoniaSmb(ctx, ReleaseAuthority.NONE, 0.0, p0Channel, null),
            )
        }

        // Meal hypothesis probabilities (pure)
        val mealHyp = computeMealHypothesis(ctx, paradoxes)

        // P2 + paradox authority cascade
        val authResult = computeAuthority(ctx, paradoxes, byTau, mealHyp)
        var releaseAuthority = authResult.authority
        val reasonCodes = authResult.codes.toMutableList()

        // P1 — hypo credibility
        val hypoGuardMode = when {
            ctx.hypoMinPredIgnored -> HypoGuardMode.IGNORE_MINPRED
            paradoxes.any { it.id == BeliefParadoxId.FLOOR_VS_REALITY } -> HypoGuardMode.PARTIAL
            else -> HypoGuardMode.FULL
        }
        if (ctx.hypoMinPredIgnored) reasonCodes += "P1"

        // SMB demand (raw caps + harmonia SMB + post-hypo arbiter)
        val smbResult = computeSmbDemand(ctx, t3cBasalFirst, harmoniaBasalFirst, releaseAuthority, mealHyp, paradoxes, u15, u60)
        var smbDemandU = smbResult.smbU
        var basalFirstChannel = smbResult.basalFirstChannel
        var harmoniaSmb = smbResult.harmoniaSmb
        reasonCodes += smbResult.codes

        // Load governor
        val lgResult = applyLoadGovernor(input, ctx, smbDemandU, mealHyp.suppressMealInterpretation, trace)
        smbDemandU = lgResult.smbU
        reasonCodes += lgResult.codes

        // IOB headroom clamp
        val iobHeadroom = max(0.0, ctx.maxIobU - ctx.iobU)
        smbDemandU = minOf(smbDemandU, ctx.maxSmbEffectiveU.coerceAtLeast(0.0), iobHeadroom)

        // P3 — macro prudence TBR
        var tbrFraction = 1.0
        if (u180 < 0.0 && ctx.iobU > ctx.tdd24hU * 0.15) {
            tbrFraction = 0.85
            reasonCodes += "P3"
        }
        if (lgResult.tbrBoostNeeded) {
            tbrFraction = max(tbrFraction, 1.08)
            reasonCodes += "LOAD_GOV_TBR"
        }

        // Channel interference — Traj-Bridge vs HTR (§6.4 discrete optimizer)
        var suppressTraj = ctx.htrResult?.suppressTrajBasalShift == true
        if (paradoxes.any { it.id == BeliefParadoxId.TRAJ_TBR_VS_HTR_SMB }) {
            val tensionSum = tensions.sumOf { it.magnitude }
            val optimized = ChannelInterferenceOptimizer.optimize(
                bgMgdl = ctx.bgMgdl,
                targetBgMgdl = ctx.targetBgMgdl,
                smbDemandU = smbDemandU,
                tbrDemandFraction = tbrFraction,
                tensionSum = tensionSum,
                maxSmbU = ctx.maxSmbEffectiveU.coerceAtLeast(0.0),
            )
            smbDemandU = optimized.smbU
            tbrFraction = optimized.tbrFraction
            if (releaseAuthority == ReleaseAuthority.HARD) suppressTraj = true
            reasonCodes += "INTERFERENCE"
            trace += "CHANNEL:${aimiFmt2(optimized.cost)}"
        }
        if (ctx.trajBridgePending && releaseAuthority == ReleaseAuthority.NONE) {
            tbrFraction = min(tbrFraction, 0.7)
        }

        // Meal channel hint
        val mealChannel = computeMealChannel(ctx, mealHyp.suppressMealInterpretation)

        // Exercise / sleep terminal guards (may zero out SMB and clear authority)
        val guardResult = applyTerminalGuards(ctx, releaseAuthority, smbDemandU, harmoniaSmb, v3)
        releaseAuthority = guardResult.authority
        smbDemandU = guardResult.smbU
        harmoniaSmb = guardResult.harmoniaSmb
        reasonCodes += guardResult.codes

        val dominantScale = when {
            releaseAuthority == ReleaseAuthority.HARD -> 15
            releaseAuthority == ReleaseAuthority.SOFT -> 60
            u180 < 0 -> 180
            else -> 60
        }
        val waitBias = if (ctx.stackingStance?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) 0.65 else 0.15

        return DoseChannelResolution(
            smbDemandU = smbDemandU,
            smbDemandBeforeLoadGovernorU = lgResult.smbBeforeU,
            tbrDemandFraction = tbrFraction,
            waitBias = waitBias.coerceIn(0.0, 1.0),
            dominantScaleMinutes = dominantScale,
            releaseAuthority = releaseAuthority,
            hypoGuardMode = hypoGuardMode,
            autodriveModeHint = AutodriveModeHint.V3,
            mealChannel = mealChannel,
            suppressTrajBasalShift = suppressTraj,
            hypoMinPredIgnored = ctx.hypoMinPredIgnored,
            reasonCodes = reasonCodes,
            basalFirstChannel = basalFirstChannel,
            t3cBasalFirst = t3cBasalFirst,
            harmoniaBasalFirst = harmoniaBasalFirst,
            harmoniaSmb = harmoniaSmb,
            loadGovernorExport = lgResult.export,
        )
    }

    // ── Sub-functions extracted from resolveChannels ──────────────────────────

    /** Computes the meal vs. non-meal hypothesis probabilities — pure, no side-effects. */
    private fun computeMealHypothesis(
        ctx: RecursiveBeliefTickContext,
        paradoxes: List<BeliefParadox>,
    ): MealHypothesis {
        val mealProb = max(
            max(
                ctx.extended.uamMealProb ?: ctx.extended.latentMealProb ?: 0.0,
                ctx.extended.causalMealConfidence ?: 0.0,
            ),
            (ctx.extended.uamLateFatProb ?: 0.0) * 0.88,
        )
        val nonMealProb = max(
            max(
                ctx.extended.uamEndogenousProb ?: ctx.extended.latentEndogenousGlucoseDrive ?: 0.0,
                ctx.extended.causalProtectiveConfidence ?: 0.0,
            ),
            max(
                ctx.extended.uamStressProb ?: 0.0,
                ctx.extended.uamPostHypoProb ?: 0.0,
            ),
        )
        val suppressMeal =
            ctx.extended.uamSuppressMealInterpretation ||
                (nonMealProb >= mealProb + 0.08 && nonMealProb >= 0.60)
        val mealWaveBoost = !suppressMeal && mealProb >= nonMealProb && mealProb >= 0.45
        val patternSoftCap = shouldSoftenPatternHyperCap(ctx, mealProb, mealWaveBoost, suppressMeal)
        return MealHypothesis(mealProb, nonMealProb, suppressMeal, mealWaveBoost, patternSoftCap)
    }

    /**
     * P2 short-scale dominance + full paradox authority cascade (§15.3).
     * Returns the effective [ReleaseAuthority] and the accumulated reason codes.
     */
    private fun computeAuthority(
        ctx: RecursiveBeliefTickContext,
        paradoxes: List<BeliefParadox>,
        byTau: Map<Int, BeliefScaleNode>,
        mealHyp: MealHypothesis,
    ): AuthorityResult {
        val u15 = byTau[15]?.urgency ?: 0.0
        val u60 = byTau[60]?.urgency ?: 0.0
        val belief15 = byTau[15]?.belief ?: 0.0
        val codes = mutableListOf<String>()

        var authority = ReleaseAuthority.NONE
        val hyperVsClearance = paradoxes.any { it.id == BeliefParadoxId.HYPER_VS_CLEARANCE && !it.suppressed }
        if (hyperVsClearance && belief15 >= 0.6 && u15 > 0.8) {
            authority = ReleaseAuthority.HARD; codes += "P2"
        } else if (u60 > 1.0 && u15 > 0.5) {
            authority = ReleaseAuthority.SOFT; codes += "P2_SOFT"
        }

        if (paradoxes.any { it.id == BeliefParadoxId.NGR_VS_HYPER }) {
            authority = if (authority == ReleaseAuthority.HARD) ReleaseAuthority.SOFT else ReleaseAuthority.NONE
            codes += "NGR_DAMP"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.WCYCLE_VS_STABLE }) {
            authority = ReleaseAuthority.NONE; codes += "WCYCLE_STABLE"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.WCYCLE_VS_HYPO }) {
            authority = ReleaseAuthority.NONE; codes += "WCYCLE_HYPO"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.THYROID_VS_AGGRESS }) {
            if (authority == ReleaseAuthority.HARD) authority = ReleaseAuthority.SOFT
            codes += "THYROID_GUARD"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.ENDOG_VS_CORRECTION }) {
            if (ctx.behavioralRisk?.capsHtrRelease() == true ||
                (ctx.physiologicalPatterns?.suppressHyperRelease == true && !mealHyp.patternSoftCapAllowed)
            ) {
                authority = ReleaseAuthority.NONE; codes += "ENDOG_CAP"
            }
        }
        if (ctx.behavioralRisk?.capsHtrRelease() == true) {
            authority = ReleaseAuthority.NONE; codes += "PHYSIO_RISK_CAP"
        } else if (ctx.physiologicalPatterns?.suppressHyperRelease == true) {
            if (mealHyp.patternSoftCapAllowed) {
                if (authority == ReleaseAuthority.HARD) authority = ReleaseAuthority.SOFT
                if (authority != ReleaseAuthority.NONE) codes += "PHYSIO_PATTERN_SOFT_CAP"
            } else {
                authority = ReleaseAuthority.NONE; codes += "PHYSIO_RISK_CAP"
            }
        }
        if (mealHyp.suppressMealInterpretation && authority != ReleaseAuthority.NONE) {
            authority = if (authority == ReleaseAuthority.HARD) ReleaseAuthority.SOFT else ReleaseAuthority.NONE
            codes += "UAM_ALT_${ctx.extended.uamHypothesisDominant ?: ctx.extended.causalDominantState ?: "NON_MEAL"}"
        }

        return AuthorityResult(authority, codes)
    }

    /**
     * Computes the raw SMB demand with all cap rules, harmonia SMB resolution,
     * and the post-hypo arbiter. Returns the final demand, updated channel, and codes.
     */
    private fun computeSmbDemand(
        ctx: RecursiveBeliefTickContext,
        t3cBasalFirst: T3cBasalFirstResolution?,
        harmoniaBasalFirst: HarmoniaBasalFirstResolution?,
        authority: ReleaseAuthority,
        mealHyp: MealHypothesis,
        paradoxes: List<BeliefParadox>,
        u15: Double,
        u60: Double,
    ): SmbDemandResult {
        val codes = mutableListOf<String>()
        var smbU = when {
            ctx.replaceHtrRelease && authority != ReleaseAuthority.NONE ->
                RecursiveBeliefReleaseCalculator.smbFloor(ctx, authority, u15, u60)
            else -> ctx.htrResult?.smbFloorU ?: 0.0
        }
        if (smbU <= 0.0 && authority != ReleaseAuthority.NONE) {
            smbU = smbFromUrgency(ctx, u15, u60)
        }
        val v3 = ctx.v3SmbU ?: 0.0
        if (authority != ReleaseAuthority.NONE) {
            // Soft meal proposals must not mute V3 demand before Harmonia arbitration.
            val patternHardCap = ctx.physiologicalPatterns?.hardBindingCapU()
            val v3Lift = listOfNotNull(
                ctx.behavioralRisk?.takeIf { it.capsHtrRelease() }?.smbFloorCapU,
                patternHardCap,
            ).minOrNull()?.let { min(v3, it) } ?: v3
            smbU = max(smbU, v3Lift)
        }
        if (paradoxes.any { it.id == BeliefParadoxId.AUDITOR_VS_RELEASE }) {
            smbU = min(smbU, 0.5); codes += "SENTINEL_CAP"
        }
        if (ctx.stackingStance?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            smbU = min(smbU, ctx.stackingStance.smbAbsoluteCapU); codes += "STACK_CAP"
        }
        if (ctx.physiologicalPatterns?.suppressMealInterpretation == true &&
            ctx.mealAbsorption?.mealDeliveryPriority == true
        ) codes += "PATTERN_MEAL_SUPPRESS"
        if (mealHyp.suppressMealInterpretation && ctx.mealAbsorption?.mealDeliveryPriority == true) {
            codes += "UAM_MEAL_SUPPRESS"
        }
        ctx.behavioralRisk?.takeIf { it.capsHtrRelease() }?.let { risk ->
            smbU = min(smbU, risk.smbFloorCapU); codes += "PHYSIO_SMB_CAP"
        }
        ctx.physiologicalPatterns?.hardBindingCapU()?.let { cap ->
            smbU = min(smbU, cap); codes += "PATTERN_SMB_CAP_HARD"
        }
        ctx.physiologicalPatterns?.softProposedCapU()?.let {
            codes += "PATTERN_SMB_SOFT_PROPOSAL"
        }
        if (ctx.mealAbsorption?.phase == MealAbsorptionPhase.SECOND_WAVE && ctx.deltaMgdlPer5 > 0 &&
            mealHyp.mealWaveBoostAllowed && ctx.behavioralRisk?.capsHtrRelease() != true
        ) {
            smbU = max(smbU, 1.5); codes += "SECOND_WAVE"
        }
        if (ctx.mealAbsorption?.phase == MealAbsorptionPhase.FIRST_WAVE && ctx.deltaMgdlPer5 >= 2.5 &&
            mealHyp.mealWaveBoostAllowed && ctx.behavioralRisk?.capsHtrRelease() != true
        ) {
            smbU = max(smbU, 1.2); codes += "FIRST_WAVE"
        }
        // HARD catalog caps re-bind after wave floors so first/second-wave boosts cannot mute them.
        ctx.physiologicalPatterns?.hardBindingCapU()?.let { cap ->
            if (smbU > cap + 1e-6) {
                smbU = cap
                codes += "PATTERN_SMB_CAP_HARD"
            }
        }

        var basalFirstChannel = selectBasalFirstChannel(t3cBasalFirst, harmoniaBasalFirst, authority)
        // Harmonia SMB arbiter after MPC/HTR demand + soft proposals, before finalize clamps.
        val authorityDecision = arbitrateHarmoniaSmbAuthority(
            ctx = ctx,
            demandBeforeU = smbU,
            mpcDemandU = v3,
            releaseAuthority = authority,
            basalFirstChannel = basalFirstChannel,
        )
        if (authorityDecision.mode == HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE ||
            authorityDecision.mode == HarmoniaSmbAuthorityMode.REDUCE
        ) {
            smbU = authorityDecision.smbU
            codes += "HARMONIA_SMB_${authorityDecision.mode.name}"
            codes += authorityDecision.reasons
        } else if (authorityDecision.reasons.isNotEmpty()) {
            codes += "HARMONIA_SMB_ACCEPT"
        }
        var harmoniaSmb = resolveHarmoniaSmb(ctx, authority, smbU, basalFirstChannel, authorityDecision)
        harmoniaSmb?.takeIf { it.eligible }?.let { resolved ->
            // Do not let the legacy 0.30×max meal-support target undo a LIFT already decided.
            if (authorityDecision.mode != HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE) {
                smbU = resolved.demandAfterU
            }
            basalFirstChannel = selectBasalFirstChannel(t3cBasalFirst, harmoniaBasalFirst, authority)
            codes += if (resolved.reducesRbtDemand) "HARMONIA_SMB_REDUCE" else "HARMONIA_SMB_SUPPORT"
        }
        // Terminal HARD bind after Harmonia legacy modulation (soft proposals never enter here).
        ctx.physiologicalPatterns?.hardBindingCapU()?.let { cap ->
            if (smbU > cap + 1e-6) {
                smbU = cap
                codes += "PATTERN_SMB_CAP_HARD"
            }
        }
        if (shouldSuppressRbtSmbDemand(ctx.extended, t3cBasalFirst, basalFirstChannel)) {
            smbU = 0.0; codes += "POST_HYPO_SMB_ARBITER"
        }
        return SmbDemandResult(smbU, basalFirstChannel, harmoniaSmb, codes)
    }

    /**
     * Applies [InsulinLoadGovernor] to the current SMB demand.
     * Writes one LOADGOV entry to [trace]; returns the governed demand, the pre-governor
     * value, the full export, a TBR-boost flag, and accumulated codes.
     */
    private fun applyLoadGovernor(
        input: Input,
        ctx: RecursiveBeliefTickContext,
        smbU: Double,
        suppressMeal: Boolean,
        trace: MutableList<String>,
    ): LoadGovernorBlock {
        val codes = mutableListOf<String>()
        val mealDeliveryPriority = ctx.mealAbsorption?.mealDeliveryPriority == true && !suppressMeal
        val eval = InsulinLoadGovernor.evaluate(
            InsulinLoadGovernor.Input(
                iobU = ctx.iobU,
                tdd24hU = ctx.tdd24hU,
                patientWeightKg = ctx.patientWeightKg,
                deltaMgdlPer5 = ctx.deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = ctx.shortAvgDeltaMgdlPer5,
                deltaPrevMgdlPer5 = ctx.deltaPrevMgdlPer5,
                bgDerivShort = ctx.bgDerivShort,
                bgMgdl = ctx.bgMgdl,
                targetBgMgdl = ctx.targetBgMgdl,
                bestTerminalMgdl = ctx.scenario.scenarioBest.terminalMgdl,
                minPredictedBgMgdl = ctx.minPredictedBgMgdl,
                eventualBgMgdl = ctx.eventualBgMgdl,
                trajectoryEnergy = ctx.trajectoryAnalysis?.metrics?.energyBalance,
                trajectoryCoherence = ctx.trajectoryAnalysis?.metrics?.coherence,
                insulinActivityStageOrdinal = ctx.insulinActivityStageOrdinal,
                insulinActivityNow = ctx.insulinActivityNow,
                mealAbsorptionPhase = ctx.mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
                mealDeliveryPriority = mealDeliveryPriority,
                lastMultiplierG = ctx.lastLoadGovernorMultiplierG,
            ),
        )
        val applied = input.authorityEnabled && eval.multiplierG < 0.999
        var governed = smbU
        if (applied) {
            governed *= eval.multiplierG
            if (eval.smbTickCapU.isFinite()) governed = min(governed, eval.smbTickCapU)
            codes += "LOAD_GOV_${eval.tier.name}"
        }
        trace += "LOADGOV:${aimiFmt2(eval.multiplierG)}"
        val export = LoadGovernorExport(
            tier = eval.tier.name,
            multiplierG = eval.multiplierG,
            rawMultiplierG = eval.rawMultiplierG,
            smbTickCapU = eval.smbTickCapU,
            physBudgetU = eval.physBudgetU,
            stackScore = eval.stackScore,
            riseScore = eval.riseScore,
            deltaDecelScore = eval.deltaDecelScore,
            smbDemandBeforeU = smbU,
            smbDemandAfterU = if (applied) governed else smbU * eval.multiplierG,
            applied = applied,
            reasonCodes = eval.reasonCodes,
            summary = eval.summary,
        )
        val tbrBoostNeeded = applied &&
            (eval.tier == InsulinLoadGovernor.Tier.SURVEILLANCE || eval.tier == InsulinLoadGovernor.Tier.WAIT)
        return LoadGovernorBlock(governed, smbU, export, tbrBoostNeeded, codes)
    }

    /** Pure mapping from suppression state to [MealChannelHint]. */
    private fun computeMealChannel(
        ctx: RecursiveBeliefTickContext,
        suppressMealInterpretation: Boolean,
    ): MealChannelHint = when {
        suppressMealInterpretation -> MealChannelHint.SUPPRESS
        ctx.mealAbsorption?.mealDeliveryPriority == true -> MealChannelHint.PRIORITY
        ctx.behavioralRisk?.suppressMealLikeScenario == true -> MealChannelHint.SUPPRESS
        else -> MealChannelHint.NORMAL
    }

    /**
     * Terminal safety guards: exercise lockout and sleep guard.
     * Both unconditionally zero out SMB authority and route to V3 basal mode.
     */
    private fun applyTerminalGuards(
        ctx: RecursiveBeliefTickContext,
        authority: ReleaseAuthority,
        smbU: Double,
        harmoniaSmb: HarmoniaSmbResolution?,
        v3: Double,
    ): TerminalGuardResult {
        if (ctx.exerciseLockout) {
            return TerminalGuardResult(
                authority = ReleaseAuthority.NONE,
                smbU = v3,
                harmoniaSmb = harmoniaSmb?.copy(
                    eligible = false,
                    dominantBlocker = "OFF_EXERCISE",
                    demandAfterU = v3,
                    appliedToRbtDemand = false,
                    reasonCodes = harmoniaSmb.reasonCodes + "HARMONIA_SMB_BLOCK_OFF_EXERCISE",
                ),
                codes = listOf("OFF_EXERCISE"),
            )
        }
        if (SleepLiveDetector.sleepGuardActive(ctx.isNight, ctx.asleepLiveConfidence)) {
            val blocker = when {
                ctx.isNight && !SleepLiveDetector.isAsleep(ctx.asleepLiveConfidence) -> "OFF_NIGHT"
                ctx.isNight -> "OFF_NIGHT_ASLEEP"
                else -> "OFF_ASLEEP_LIVE"
            }
            return TerminalGuardResult(
                authority = ReleaseAuthority.NONE,
                smbU = v3,
                harmoniaSmb = harmoniaSmb?.copy(
                    eligible = false,
                    dominantBlocker = blocker,
                    demandAfterU = v3,
                    appliedToRbtDemand = false,
                    reasonCodes = harmoniaSmb.reasonCodes + "HARMONIA_SMB_BLOCK_$blocker",
                ),
                codes = listOf(blocker),
            )
        }
        return TerminalGuardResult(authority, smbU, harmoniaSmb, emptyList())
    }

    private fun resolveT3cBasalFirst(ctx: RecursiveBeliefTickContext): T3cBasalFirstResolution? {
        val ext = ctx.extended
        val active = ext.t3cActive
        val demand = ext.t3cBasalDemandRateUph ?: 0.0
        val cap = (ext.t3cBasalMaxRateUph ?: demand).coerceAtLeast(0.0)
        val bounded = demand.coerceIn(0.0, cap.coerceAtLeast(demand))
        val mealConflict = ext.t3cMealConflict
        val postHypoBlock = ext.t3cPostHypoBlock
        val exerciseBlock = ext.t3cExerciseBlock
        val hardSafetyBlock = ext.t3cHardSafetyBlock || ctx.tier1Hypo
        if (!active && demand <= 0.0 && !mealConflict && !postHypoBlock && !exerciseBlock && !hardSafetyBlock) {
            return null
        }

        val dominantBlocker = when {
            hardSafetyBlock -> ext.t3cBlockReason ?: "HARD_SAFETY"
            postHypoBlock -> ext.t3cBlockReason ?: "POST_HYPO"
            mealConflict -> ext.t3cBlockReason ?: "MEAL_CONFLICT"
            exerciseBlock -> ext.t3cBlockReason ?: "EXERCISE_LOCKOUT"
            active && bounded <= 0.0 -> ext.t3cBlockReason ?: "NO_BASAL_DEMAND"
            !active -> "INACTIVE"
            else -> null
        }
        val eligible = active &&
            !hardSafetyBlock &&
            !postHypoBlock &&
            !mealConflict &&
            !exerciseBlock &&
            bounded > 0.0
        val reasonCodes = buildList {
            add(if (active) "T3C_ACTIVE" else "T3C_INACTIVE")
            if (hardSafetyBlock) add("T3C_HARD_SAFETY_BLOCK")
            if (postHypoBlock) add("T3C_POST_HYPO_BLOCK")
            if (mealConflict) add("T3C_MEAL_CONFLICT")
            if (exerciseBlock) add("T3C_EXERCISE_BLOCK")
            if (ext.t3cGovernanceBasalFloorUph != null) add("T3C_GOVERNANCE_FLOOR")
            if (active && bounded <= 0.0) add("T3C_NO_BASAL_DEMAND")
            if (eligible) add("T3C_BASAL_FIRST_READY")
        }
        return T3cBasalFirstResolution(
            active = active,
            eligible = eligible,
            basalDemandRateUph = demand,
            boundedRateUph = bounded,
            maxBasalCapUph = cap,
            anticipationStrength = ext.t3cAnticipationStrength ?: 0.0,
            mealConflict = mealConflict,
            postHypoBlock = postHypoBlock,
            exerciseBlock = exerciseBlock,
            hardSafetyBlock = hardSafetyBlock,
            dominantBlocker = dominantBlocker,
            governanceBasalFloorUph = ext.t3cGovernanceBasalFloorUph,
            governanceAggressivenessFloor = ext.t3cGovernanceAggressivenessFloor,
            reasonCodes = reasonCodes,
        )
    }

    private fun resolveHarmoniaBasalFirst(ctx: RecursiveBeliefTickContext): HarmoniaBasalFirstResolution? {
        val ext = ctx.extended
        val active = ext.harmoniaActive
        val demand = ext.harmoniaBasalDemandRateUph ?: 0.0
        val cap = (ext.harmoniaBasalMaxRateUph ?: demand).coerceAtLeast(0.0)
        val bounded = demand.coerceIn(0.0, cap.coerceAtLeast(demand))
        val postHypoBlock = ext.harmoniaPostHypoBlock
        val exerciseBlock = ext.harmoniaExerciseBlock
        val mealConflict = ext.harmoniaMealConflict
        val hardSafetyBlock = ext.harmoniaHardSafetyBlock || ctx.tier1Hypo
        val productionAction = ext.harmoniaAction in setOf(
            "BASAL_FIRST",
            "MEAL_SUPPORT",
            "PROTECTIVE_REDUCTION",
            "STABILIZE", // aligned with the production basal-first branch (DetermineBasalAIMI2 §7106 / §3387)
        )
        if (!active && demand <= 0.0 && !postHypoBlock && !exerciseBlock && !mealConflict && !hardSafetyBlock) {
            return null
        }

        val dominantBlocker = when {
            hardSafetyBlock -> ext.harmoniaBlockReason ?: "HARD_SAFETY"
            postHypoBlock -> ext.harmoniaBlockReason ?: "POST_HYPO"
            exerciseBlock -> ext.harmoniaBlockReason ?: "EXERCISE_LOCKOUT"
            mealConflict -> ext.harmoniaBlockReason ?: "MEAL_CONFLICT"
            active && !ext.harmoniaDecisionEligible -> ext.harmoniaBlockReason ?: "SIMULATION_INELIGIBLE"
            active && !productionAction -> ext.harmoniaBlockReason ?: "NO_PRODUCTION_ACTION"
            active && bounded <= 0.0 -> ext.harmoniaBlockReason ?: "NO_BASAL_DEMAND"
            !active -> "INACTIVE"
            else -> null
        }
        val eligible = active &&
            ext.harmoniaDecisionEligible &&
            productionAction &&
            !hardSafetyBlock &&
            !postHypoBlock &&
            !exerciseBlock &&
            !mealConflict &&
            bounded > 0.0
        val reasonCodes = buildList {
            add(if (active) "HARMONIA_ACTIVE" else "HARMONIA_INACTIVE")
            ext.harmoniaAction?.let { add("HARMONIA_ACTION_$it") }
            if (hardSafetyBlock) add("HARMONIA_HARD_SAFETY_BLOCK")
            if (postHypoBlock) add("HARMONIA_POST_HYPO_BLOCK")
            if (exerciseBlock) add("HARMONIA_EXERCISE_BLOCK")
            if (mealConflict) add("HARMONIA_MEAL_CONFLICT")
            if (active && !ext.harmoniaDecisionEligible) add("HARMONIA_SIMULATION_INELIGIBLE")
            if (active && !productionAction) add("HARMONIA_NO_PRODUCTION_ACTION")
            if (active && bounded <= 0.0) add("HARMONIA_NO_BASAL_DEMAND")
            if (eligible) add("HARMONIA_BASAL_FIRST_READY")
        }
        return HarmoniaBasalFirstResolution(
            active = active,
            eligible = eligible,
            sourceAction = ext.harmoniaAction,
            branch = ext.harmoniaBranch,
            basalDemandRateUph = demand,
            boundedRateUph = bounded,
            maxBasalCapUph = cap,
            mealConflict = mealConflict,
            postHypoBlock = postHypoBlock,
            exerciseBlock = exerciseBlock,
            hardSafetyBlock = hardSafetyBlock,
            dominantBlocker = dominantBlocker,
            reasonCodes = reasonCodes,
        )
    }

    private fun selectBasalFirstChannel(
        t3cBasalFirst: T3cBasalFirstResolution?,
        harmoniaBasalFirst: HarmoniaBasalFirstResolution?,
        releaseAuthority: ReleaseAuthority,
    ): BasalFirstChannel =
        when {
            t3cBasalFirst?.eligible == true -> BasalFirstChannel.T3C_BASAL_FIRST
            // Classic mutex: Harmonia owns basal only when RBT is not releasing SMB.
            harmoniaBasalFirst?.eligible == true && releaseAuthority == ReleaseAuthority.NONE ->
                BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST
            // Soft meal-rise exception (H4 / DIGESTION): SOFT authority often exists for SMB, but
            // PATTERN/LOAD caps crush delivery while Harmonia was eligible — historically logged as
            // rbt_no_harmonia_channel. Allow basal-first MEAL_SUPPORT so TBR can still act.
            harmoniaBasalFirst?.eligible == true &&
                allowsHarmoniaBasalDuringSoftMealSupport(harmoniaBasalFirst, releaseAuthority) ->
                BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST
            else -> BasalFirstChannel.NONE
        }

    /**
     * When RBT is SOFT and Harmonia chose [MEAL_SUPPORT] on a [DIGESTION_ACTIVE] trunk, keep the
     * Harmonia basal-first channel instead of forcing NONE (which surfaces as `rbt_no_harmonia_channel`).
     * HARD stays exclusive to SMB; non-digestion MEAL_SUPPORT keeps the classic SMB-modulator path.
     */
    internal fun allowsHarmoniaBasalDuringSoftMealSupport(
        harmoniaBasalFirst: HarmoniaBasalFirstResolution?,
        releaseAuthority: ReleaseAuthority,
    ): Boolean {
        if (releaseAuthority != ReleaseAuthority.SOFT) return false
        if (harmoniaBasalFirst?.eligible != true) return false
        if (harmoniaBasalFirst.sourceAction != "MEAL_SUPPORT") return false
        return harmoniaBasalFirst.branch == "DIGESTION_ACTIVE"
    }

    private fun arbitrateHarmoniaSmbAuthority(
        ctx: RecursiveBeliefTickContext,
        demandBeforeU: Double,
        mpcDemandU: Double,
        releaseAuthority: ReleaseAuthority,
        basalFirstChannel: BasalFirstChannel,
    ): HarmoniaSmbAuthorityDecision {
        val ext = ctx.extended
        val patterns = ctx.physiologicalPatterns
        val softCap = patterns?.softProposedCapU()
        val hardCap = patterns?.hardBindingCapU()
        val catalogCapU = softCap ?: hardCap ?: patterns?.smbCapU
        val catalogKind = when {
            softCap != null -> PatternCapKind.SOFT
            hardCap != null -> PatternCapKind.HARD
            else -> patterns?.smbCapKind
        }
        val iobHeadroom = (ctx.maxIobU - ctx.iobU).coerceAtLeast(0.0)
        val envelope = minOf(ctx.maxSmbEffectiveU.coerceAtLeast(0.0), iobHeadroom)
        val intent = runCatching { InsulinIntent.valueOf(ext.insulinIntent ?: InsulinIntent.NONE.name) }
            .getOrDefault(InsulinIntent.NONE)
        val protectiveBlock =
            ext.harmoniaPostHypoBlock ||
                ext.harmoniaExerciseBlock ||
                ext.harmoniaHardSafetyBlock ||
                ctx.tier1Hypo ||
                ext.postHypoDeliverySuppressSmb
        // A basal-first owner normally closes the SMB arbitration channel. The one exception is the
        // soft meal-rise channel added in [allowsHarmoniaBasalDuringSoftMealSupport]: it grants
        // Harmonia the basal channel *while RBT still holds SMB authority* (it requires SOFT, and the
        // classic mutex requires NONE). It was added so TBR could still act on a meal — not to revoke
        // the SMB lift. Measured on 2026-08-09/10: this was the dominant blocker,
        // `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST` on 110–144 ticks per day and on every
        // tick of both lunch rises, with `effective_authority` SOFT throughout.
        val basalFirstClosesSmbChannel =
            basalFirstChannel == BasalFirstChannel.T3C_BASAL_FIRST ||
                (
                    basalFirstChannel == BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST &&
                        releaseAuthority != ReleaseAuthority.SOFT
                    )
        val channelOpen =
            releaseAuthority != ReleaseAuthority.NONE &&
                !basalFirstClosesSmbChannel &&
                !protectiveBlock
        if (!channelOpen) {
            return HarmoniaSmbArbiter.decide(
                demandBeforeU = demandBeforeU,
                mpcDemandU = mpcDemandU,
                catalogProposedCapU = catalogCapU,
                catalogCapKind = catalogKind,
                envelopeMaxU = envelope,
                insulinIntent = intent,
                harmoniaAction = ext.harmoniaAction,
                riseConfirmed = false,
                mealCertaintySupports = false,
                protectiveBlock = protectiveBlock,
            )
        }
        return HarmoniaSmbArbiter.decide(
            demandBeforeU = demandBeforeU,
            mpcDemandU = mpcDemandU,
            catalogProposedCapU = catalogCapU,
            catalogCapKind = catalogKind,
            envelopeMaxU = envelope,
            insulinIntent = intent,
            harmoniaAction = ext.harmoniaAction,
            riseConfirmed = ext.riseConfirmed || ctx.deltaMgdlPer5 >= 1.2,
            mealCertaintySupports = ext.mealCertaintySupports,
            protectiveBlock = false,
            barrierPermittedU = ctx.barrierPermittedU,
        )
    }

    private fun resolveHarmoniaSmb(
        ctx: RecursiveBeliefTickContext,
        releaseAuthority: ReleaseAuthority,
        currentDemandU: Double,
        basalFirstChannel: BasalFirstChannel,
        authorityDecision: HarmoniaSmbAuthorityDecision?,
    ): HarmoniaSmbResolution? {
        val ext = ctx.extended
        val active = ext.harmoniaActive || authorityDecision?.addsSmbAuthority == true
        val action = ext.harmoniaAction
        val simulated = when (authorityDecision?.mode) {
            HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE -> authorityDecision.smbU
            else -> ext.harmoniaSmbDemandU ?: 0.0
        }
        val iobHeadroom = (ctx.maxIobU - ctx.iobU).coerceAtLeast(0.0)
        val cap = minOf(
            ext.harmoniaSmbMaxU ?: ctx.maxSmbEffectiveU,
            ctx.maxSmbEffectiveU,
            iobHeadroom,
        ).coerceAtLeast(0.0)
        val bounded = simulated.coerceIn(0.0, cap)
        val postHypoBlock = ext.harmoniaPostHypoBlock
        val exerciseBlock = ext.harmoniaExerciseBlock
        val mealConflict = ext.harmoniaMealConflict
        val hardSafetyBlock = ext.harmoniaHardSafetyBlock || ctx.tier1Hypo
        val mealSupportAction = action == "MEAL_SUPPORT" ||
            authorityDecision?.mode == HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE
        val protectiveReductionAction = action == "PROTECTIVE_REDUCTION" ||
            authorityDecision?.mode == HarmoniaSmbAuthorityMode.REDUCE
        val smbAction = mealSupportAction || protectiveReductionAction
        if (!active && simulated <= 0.0 && !postHypoBlock && !exerciseBlock && !mealConflict && !hardSafetyBlock &&
            authorityDecision == null
        ) {
            return null
        }

        val dominantBlocker = when {
            hardSafetyBlock -> ext.harmoniaBlockReason ?: "HARD_SAFETY"
            postHypoBlock -> ext.harmoniaBlockReason ?: "POST_HYPO"
            exerciseBlock -> ext.harmoniaBlockReason ?: "EXERCISE_LOCKOUT"
            mealConflict -> ext.harmoniaBlockReason ?: "MEAL_CONFLICT"
            // Order matters, and this pair used to be the other way round. `eligible` below already
            // fails when the RBT authority is NONE, so on those ticks the basal-first owner is not
            // the term that blocks anything. Naming it first reported a blocker that was not the
            // binding one: measured on the 2026-08-15 package, 56 of 78
            // `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST` ticks had authority NONE.
            // This is a label only. It does not change `eligible`, the demand, or the dose.
            releaseAuthority == ReleaseAuthority.NONE -> "NO_RBT_SMB_AUTHORITY"
            basalFirstChannel != BasalFirstChannel.NONE -> "BASAL_FIRST_OWNER_${basalFirstChannel.name}"
            active && !ext.harmoniaDecisionEligible && authorityDecision?.addsSmbAuthority != true ->
                ext.harmoniaBlockReason ?: "SIMULATION_INELIGIBLE"
            active && !smbAction -> "NO_SMB_ACTION"
            mealSupportAction && bounded <= 0.0 -> "NO_SMB_DEMAND"
            mealSupportAction && cap <= 0.0 -> "SMB_CAP_ZERO"
            !active -> "INACTIVE"
            else -> null
        }
        val eligible = (
            (active && ext.harmoniaDecisionEligible && smbAction) ||
                authorityDecision?.addsSmbAuthority == true
            ) &&
            releaseAuthority != ReleaseAuthority.NONE &&
            basalFirstChannel == BasalFirstChannel.NONE &&
            !hardSafetyBlock &&
            !postHypoBlock &&
            !exerciseBlock &&
            !mealConflict &&
            (!mealSupportAction || bounded > 0.0 || authorityDecision?.addsSmbAuthority == true)
        val targetDemand = when {
            !eligible -> currentDemandU
            authorityDecision?.mode == HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE ->
                max(currentDemandU, authorityDecision.smbU.coerceAtMost(cap))
            protectiveReductionAction -> min(currentDemandU, bounded)
            else -> max(currentDemandU, bounded)
        }
        val applied = eligible && abs(targetDemand - currentDemandU) > 1e-6
        val reasonCodes = buildList {
            add(if (active) "HARMONIA_ACTIVE" else "HARMONIA_INACTIVE")
            action?.let { add("HARMONIA_ACTION_$it") }
            authorityDecision?.mode?.let { add("HARMONIA_SMB_AUTHORITY_$it") }
            if (authorityDecision?.addsSmbAuthority == true) add("ADDS_SMB_AUTHORITY")
            if (hardSafetyBlock) add("HARMONIA_SMB_HARD_SAFETY_BLOCK")
            if (postHypoBlock) add("HARMONIA_SMB_POST_HYPO_BLOCK")
            if (exerciseBlock) add("HARMONIA_SMB_EXERCISE_BLOCK")
            if (mealConflict) add("HARMONIA_SMB_MEAL_CONFLICT")
            if (basalFirstChannel != BasalFirstChannel.NONE) add("HARMONIA_SMB_BASAL_FIRST_OWNER")
            if (releaseAuthority == ReleaseAuthority.NONE) add("HARMONIA_SMB_NO_RBT_AUTHORITY")
            if (active && !ext.harmoniaDecisionEligible && authorityDecision?.addsSmbAuthority != true) {
                add("HARMONIA_SMB_SIMULATION_INELIGIBLE")
            }
            if (active && !smbAction) add("HARMONIA_SMB_NO_ACTION")
            if (mealSupportAction && bounded <= 0.0) add("HARMONIA_SMB_NO_DEMAND")
            if (eligible && mealSupportAction) add("HARMONIA_SMB_SUPPORT_READY")
            if (eligible && protectiveReductionAction) add("HARMONIA_SMB_REDUCTION_READY")
            if (applied) add("HARMONIA_SMB_APPLIED_TO_RBT")
        }
        return HarmoniaSmbResolution(
            active = active,
            eligible = eligible,
            sourceAction = action,
            branch = ext.harmoniaBranch,
            targetSmbU = simulated,
            boundedSmbU = bounded,
            maxSmbCapU = cap,
            demandBeforeU = currentDemandU,
            demandAfterU = targetDemand,
            mealConflict = mealConflict,
            postHypoBlock = postHypoBlock,
            exerciseBlock = exerciseBlock,
            hardSafetyBlock = hardSafetyBlock,
            dominantBlocker = dominantBlocker,
            reasonCodes = reasonCodes,
            appliedToRbtDemand = applied || authorityDecision?.addsSmbAuthority == true,
            reducesRbtDemand = eligible && targetDemand < currentDemandU,
            authorityMode = authorityDecision?.mode?.name,
            addsSmbAuthority = authorityDecision?.addsSmbAuthority == true,
            insulinIntent = authorityDecision?.insulinIntent?.name ?: ext.insulinIntent,
            authorityDecision = authorityDecision,
        )
    }

    private fun smbFromUrgency(ctx: RecursiveBeliefTickContext, u15: Double, u60: Double): Double {
        val base = max(0.5, ctx.tdd24hU * 0.025)
        val factor = max(u15, u60 * 0.85).coerceIn(0.0, 2.5)
        return base * factor
    }

    private fun shouldSoftenPatternHyperCap(
        ctx: RecursiveBeliefTickContext,
        mealHypothesisProb: Double,
        mealWaveBoostAllowed: Boolean,
        suppressMealInterpretation: Boolean,
    ): Boolean {
        val patterns = ctx.physiologicalPatterns ?: return false
        if (!patterns.suppressHyperRelease || ctx.behavioralRisk?.capsHtrRelease() == true) return false
        if (!mealWaveBoostAllowed || suppressMealInterpretation) return false
        if (ctx.contextActivityActive || ctx.exerciseLockout ||
            SleepLiveDetector.sleepGuardActive(ctx.isNight, ctx.asleepLiveConfidence)
        ) return false
        if ((ctx.extended.uamPostHypoProb ?: 0.0) >= 0.40) return false
        if ((ctx.extended.patientModeProtectionBias ?: 0.0) >= 0.70 &&
            (ctx.extended.patientModeMealBias ?: 0.0) <= 0.55
        ) {
            return false
        }

        val dominantMealLike = when (patterns.dominant) {
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
            PhysiologicalPatternId.HYPER_INSTALLED,
            -> true
            else -> false
        }
        if (!dominantMealLike || patterns.dominantConfidence < 0.55) return false

        val mealSupport = max(
            mealHypothesisProb,
            max(
                ctx.mealAbsorption?.belief ?: 0.0,
                max(
                    ctx.extended.causalMealConfidence ?: 0.0,
                    ctx.extended.patientModeMealBias ?: 0.0,
                ),
            ),
        )
        return mealSupport >= 0.72 && ctx.mealAbsorption?.mealDeliveryPriority == true
    }

    /**
     * Symmetric mutex with [BasalFirstChannel.T3C_BASAL_FIRST]: when post-hypo rebound guard is active,
     * RBT must not keep a parallel SMB demand (HTR floor / meal-wave boosts).
     */
    internal fun shouldSuppressRbtSmbDemand(
        ext: RbtExtendedSignals,
        t3cBasalFirst: T3cBasalFirstResolution?,
        basalFirstChannel: BasalFirstChannel,
    ): Boolean {
        if (ext.postHypoDeliverySuppressSmb) return true
        if (!ext.t3cActive || !ext.t3cPostHypoBlock) return false
        return basalFirstChannel == BasalFirstChannel.T3C_BASAL_FIRST ||
            t3cBasalFirst?.active == true
    }
}
