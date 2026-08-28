package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MR-7 clauses 1–4: OBSERVE, BELIEVE, PROJECT, DEVIATE per scale.
 */
object RecursiveBeliefEngine {

    private val HORIZONS = intArrayOf(15, 60, 180, 480)

    fun build(
        ctx: RecursiveBeliefTickContext,
        nowMs: Long,
        waveletEnabled: Boolean = false,
        includeShadowLeaves: Boolean = false,
    ): List<BeliefScaleNode> {
        val enriched = enrichCtx(ctx, waveletEnabled)
        val preliminary = HORIZONS.map { tau -> buildScaleRaw(tau, enriched, includeShadowLeaves) }
        val tensions = RecursiveBeliefResolver.computeTensions(preliminary)
        val cascaded = CredibilityCascade.apply(preliminary, tensions)
        val nodes = cascaded.map { recomputeNode(it, enriched) }
        nodes.forEach { node ->
            RecursiveBeliefMemory.remember(
                tauMin = node.horizonMinutes,
                belief = node.belief,
                terminalMgdl = node.terminalMgdl,
                urgency = node.urgency,
                nowMs = nowMs,
            )
        }
        return nodes
    }

    private fun enrichCtx(ctx: RecursiveBeliefTickContext, waveletEnabled: Boolean): RecursiveBeliefTickContext {
        if (!waveletEnabled || ctx.bgHistoryMgdl.isEmpty()) return ctx
        val bands = WaveletBelief.decompose(ctx.bgHistoryMgdl) ?: return ctx
        return ctx.copy(waveletBands = bands)
    }

    private fun buildScaleRaw(tauMin: Int, ctx: RecursiveBeliefTickContext, includeShadow: Boolean): BeliefScaleNode {
        val leaves = BeliefLeafRegistry.collect(tauMin, ctx, includeShadow)
        val belief = believe(leaves)
        val terminal = project(tauMin, ctx, belief)
        val urgency = deviate(tauMin, ctx, terminal, belief)
        return BeliefScaleNode(
            horizonMinutes = tauMin,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = leaves,
        )
    }

    private fun recomputeNode(node: BeliefScaleNode, ctx: RecursiveBeliefTickContext): BeliefScaleNode {
        val belief = believe(node.leaves)
        val terminal = project(node.horizonMinutes, ctx, belief)
        val urgency = deviate(node.horizonMinutes, ctx, terminal, belief)
        return node.copy(belief = belief, terminalMgdl = terminal, urgency = urgency)
    }

    /** MR-7 clause 2 — signals normalized to [0,1] before fusion */
    internal fun believe(leaves: List<BeliefLeafReading>): Double {
        var num = 0.0
        var den = 0.0
        for (leaf in leaves) {
            if (leaf.credibility < 0.15) continue
            val norm = normalizeSignal(leaf)
            num += leaf.weight * norm * leaf.credibility
            den += leaf.weight * leaf.credibility
        }
        if (den <= 0.0) return 0.0
        return (num / den).coerceIn(0.0, 1.0)
    }

    internal fun normalizeSignal(leaf: BeliefLeafReading): Double =
        when (leaf.id) {
            BeliefLeafId.PKPD_BEST,
            BeliefLeafId.PKPD_IOB,
            BeliefLeafId.PKPD_UAM,
            BeliefLeafId.PKPD_COB,
            BeliefLeafId.PKPD_HYBRID,
            BeliefLeafId.MPC_HORIZON,
            BeliefLeafId.SAFETY_TERMINALS,
            BeliefLeafId.RISK_ENVELOPE -> ((leaf.signal - 70.0) / 330.0).coerceIn(0.0, 1.0)
            BeliefLeafId.DELTA_NOW,
            BeliefLeafId.DELTA_SHORT,
            BeliefLeafId.DELTA_COMBINED -> (leaf.signal / 5.0).coerceIn(0.0, 1.0)
            BeliefLeafId.HTR_RELEASE,
            BeliefLeafId.MPC_IMPLIED -> (leaf.signal / 3.0).coerceIn(0.0, 1.0)
            BeliefLeafId.TRAJ_BRIDGE -> leaf.signal.coerceIn(0.0, 1.0)
            else -> leaf.signal.coerceIn(0.0, 1.0)
        }

    /** MR-7 clause 3 */
    internal fun project(tauMin: Int, ctx: RecursiveBeliefTickContext, belief: Double): Double {
        val target = ctx.targetBgMgdl
        return when (tauMin) {
            15    -> ctx.bgMgdl + ctx.deltaMgdlPer5 * 3.0
            60    -> BeliefLeafAdapterRegistry.pointAt(ctx.curves.hybrid, tauMin, ctx.bgMgdl)
                .let { max(it, ctx.scenario.scenarioBest.terminalMgdl * belief) }
            180   -> {
                // Use the IOB-only curve terminal as the "insulin-driven anchor" for the blend.
                // When the curve is unavailable (no active IOB) fall back to the full scenario
                // terminal rather than silently anchoring to current BG, which would over-inflate
                // urgency at this scale and risk a spurious correction.
                val iobTerminal = ctx.curves.iob.lastOrNull()
                if (iobTerminal != null) {
                    blend(iobTerminal, ctx.scenario.scenarioBest.terminalMgdl, belief)
                } else {
                    ctx.scenario.scenarioBest.terminalMgdl
                }
            }
            480   -> target + (ctx.physioPhase?.confidence ?: 0.5) * 20.0
            else  -> ctx.bgMgdl
        }
    }

    /** MR-7 clause 4 */
    internal fun deviate(tauMin: Int, ctx: RecursiveBeliefTickContext, terminal: Double, belief: Double): Double {
        val band = max(25.0, ctx.highBgPreferenceMgdl - ctx.targetBgMgdl)
        val dev = (terminal - ctx.targetBgMgdl) / band
        val deltaFactor = when (tauMin) {
            15    -> ctx.deltaMgdlPer5 / 3.0
            60    -> ctx.shortAvgDeltaMgdlPer5 / 2.5
            180   -> ctx.combinedDeltaMgdlPer5 / 4.0
            else  -> 0.0
        }
        val echo = RecursiveBeliefMemory.echo(tauMin)
        val echoDamp = if (echo > 0.85) 0.9 else 1.0
        val waveletBoost = if (ctx.behavioralRisk?.capsHtrRelease() == true ||
            ctx.physiologicalPatterns?.suppressWaveletBoost == true
        ) {
            0.0
        } else {
            ctx.waveletBands?.let { bands ->
                when (tauMin) {
                    15    -> bands.high / 10.0
                    60    -> bands.mid / 15.0
                    180   -> bands.low / 20.0
                    else  -> 0.0
                }
            } ?: 0.0
        }
        return (dev + 0.35 * deltaFactor + 0.25 * belief + 0.15 * waveletBoost) * echoDamp
    }

    private fun blend(a: Double, b: Double, belief: Double): Double =
        a * (1.0 - belief) + b * belief
}
