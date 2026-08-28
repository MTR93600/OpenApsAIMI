package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskConstants
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoConstants
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the two authoritative AIMI scenario curves from PKPD primitives + product context.
 *
 * Pure Kotlin — no side effects. See `docs/AIMI_SCENARIO_PROJECTION.md`.
 */
object ScenarioProjectionEngine {

    private const val TRAJECTORY_RISE_STEP_FACTOR = 0.12
    private const val SPIRAL_DAMPING = 0.94
    private const val ACTIVITY_RISE_CAP_FACTOR = 0.65
    private const val PHYSIO_DAMP_BLEND = 0.35
    /** Hybrid stays within this of BG → treat as collapsed / weak metabolic signal. */
    private const val HYBRID_COLLAPSED_MAX_DEV_MGDL = 3.5
    /** Floor must diverge at least this much from BG to justify insulin-slope restore. */
    private const val FLOOR_SLOPE_MIN_DEV_MGDL = 6.0
    private const val INSULIN_SLOPE_SEED_WEIGHT = 0.38
    private const val INSULIN_SLOPE_FINAL_WEIGHT_MIN = 0.25
    private const val INSULIN_SLOPE_FINAL_WEIGHT_MAX = 0.45
    /** When mealIntent is on, still restore but at reduced weight (false-positive meal must not fully flatten). */
    private const val MEAL_INTENT_RESTORE_DAMP = 0.40

    fun build(input: ScenarioProjectionInput): ScenarioProjectionPair {
        val curves = input.curves
        val ctx = input.context
        val bg = input.bgNowMgdl
        val contributors = mutableListOf<ScenarioContributor>()
        val floorRaw = curves.iob.toMutableList()
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.PKPD_IOB_FLOOR,
                summary = "Clinical floor = PKPD insulin-only path",
            ),
        )

        val bestRaw = curves.hybrid.toMutableList()
        // When hybrid≈BG but insulin-only Floor still slopes, seed insulin motion into Scenario
        // and skip layers that would collapse it back onto BG (physio / soft target blend).
        val rawPreserveInsulinSlope =
            !ctx.mealIntent &&
                maxAbsDevFromBg(curves.hybrid, bg) <= HYBRID_COLLAPSED_MAX_DEV_MGDL &&
                maxAbsDevFromBg(curves.iob, bg) >= FLOOR_SLOPE_MIN_DEV_MGDL
        val preserveInsulinSlope = InsulinSlopePreserveHysteresis.stabilize(rawPreserveInsulinSlope)
        if (preserveInsulinSlope) {
            seedInsulinSlopeFromFloor(bestRaw, floorRaw, bg, contributors)
        }
        mergeMealAndUamSources(
            bestRaw,
            curves,
            ctx,
            contributors,
            preserveInsulinSlope = preserveInsulinSlope,
        )
        ctx.trajectoryAnalysis?.let { analysis ->
            if (ctx.trajectoryModulationActive || ctx.trajectoryRelevanceScore > 0.25) {
                applyTrajectoryLayer(
                    bestRaw,
                    bg,
                    input.deltaMgdlPer5,
                    analysis,
                    contributors,
                    preserveInsulinSlope = preserveInsulinSlope,
                )
            }
        }
        if (ctx.activityProtectionMode || ctx.contextActivityActive) {
            applyActivityLayer(bestRaw, bg, input.deltaMgdlPer5, contributors)
        }
        if (!preserveInsulinSlope && (ctx.physioReactivityFactor < 0.98 || ctx.physioSmbFactor < 0.98)) {
            applyPhysioLayer(
                bestRaw,
                bg,
                ctx.physioSmbFactor,
                ctx.physioReactivityFactor,
                contributors,
            )
        }
        if (ctx.suppressMealLikeUam || ctx.scenarioBestCapAboveBgMgdl != null) {
            applyPhysiologicalPhaseLayer(
                bestRaw,
                bg,
                ctx,
                contributors,
                preserveInsulinSlope = preserveInsulinSlope,
            )
        }
        if (!preserveInsulinSlope && ctx.contextSmbFactor < 0.98f) {
            applyContextSmbLayer(bestRaw, bg, ctx.contextSmbFactor, contributors)
        }
        if (!preserveInsulinSlope) {
            blendTowardTarget(bestRaw, bg, ctx.targetBgMgdl, ctx.trajectoryType, contributors)
        }
        // Restore before meal floor so absorption lift cannot mask a collapsed hybrid.
        restoreInsulinSlopeIfCollapsed(bestRaw, floorRaw, bg, ctx.mealIntent, contributors)
        // Gate path-min must reflect the curve BEFORE meal-absorption lift (dose-facing safety).
        var preLiftMin: Double? = null
        var preLiftHitFloor: Boolean? = null
        if (ctx.mealAbsorptionMemoryActive || ctx.mealAbsorptionPhase.isActive) {
            preLiftMin = bestRaw.filter { it.isFinite() }.minOrNull() ?: bg
            preLiftHitFloor = preLiftMin < AimiRiskConstants.NUMERIC_FLOOR_MGDL
            applyMealAbsorptionTerminalFloor(
                bestRaw,
                bg,
                ctx,
                contributors,
            )
        }
        // Final safety: if later layers still pinned Scenario to BG while Floor slopes, restore again.
        restoreInsulinSlopeIfCollapsed(bestRaw, floorRaw, bg, ctx.mealIntent, contributors)

        return ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(ScenarioProjectionKind.CLINICAL_FLOOR, floorRaw),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(
                kind = ScenarioProjectionKind.SCENARIO_BEST,
                raw = bestRaw,
                preLiftPathMinMgdl = preLiftMin,
                preLiftPathMinHitFloor = preLiftHitFloor,
            ),
            contributors = contributors,
            cobPointsMgdl = clampSeriesToInts(curves.cob),
            ztPointsMgdl = clampSeriesToInts(curves.zt),
            trajectoryType = ctx.trajectoryType?.name,
        )
    }

    private fun mergeMealAndUamSources(
        bestRaw: MutableList<Double>,
        curves: AdvancedPredictionCurves,
        ctx: ScenarioProjectionContext,
        contributors: MutableList<ScenarioContributor>,
        preserveInsulinSlope: Boolean = false,
    ) {
        val terminalBefore = curves.hybrid.lastOrNull() ?: bestRaw.last()
        for (i in bestRaw.indices) {
            var candidate = bestRaw[i]
            val uam = curves.uam.getOrElse(i) { candidate }
            val cob = curves.cob.getOrElse(i) { candidate }
            if (ctx.mealIntent) {
                candidate = max(candidate, max(uam, cob))
            } else if (
                !preserveInsulinSlope &&
                !ctx.suppressMealLikeUam &&
                uam > candidate + 5.0
            ) {
                // Skip when preserving Floor slope: flat UAM≈BG would erase the insulin seed.
                candidate = max(candidate, uam)
            }
            bestRaw[i] = candidate
        }
        val terminalLift = bestRaw.last() - terminalBefore
        if (ctx.mealIntent) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.MEAL_CONTEXT,
                    summary = "Meal intent — max(UAM, COB) over hybrid",
                    terminalDeltaMgdl = terminalLift,
                ),
            )
        }
        if (ctx.effectiveCobG > 0.0 && ctx.mealContext.mealAdvisorCarbsFresh) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.MEAL_ADVISOR_COB,
                    summary = "Advisor COB ${aimiFmt0(ctx.effectiveCobG)}g in COB curve",
                ),
            )
        }
        if (!ctx.mealIntent && !ctx.suppressMealLikeUam && terminalLift > 5.0) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.PKPD_UAM_MOMENTUM,
                    summary = "UAM momentum uplift vs hybrid",
                    terminalDeltaMgdl = terminalLift,
                ),
            )
        }
    }

    /**
     * During meal-absorption memory, keep the **terminal** from finishing below [bg]+floorAbove
     * via a terminal-anchored ramp. Never rewrites t=0 and never pins the whole horizon to a
     * constant (that fabricated flat UAM lines and poisoned dose-facing path-min gates).
     */
    private fun applyMealAbsorptionTerminalFloor(
        bestRaw: MutableList<Double>,
        bg: Double,
        ctx: ScenarioProjectionContext,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val floorAbove = ctx.mealAbsorptionBestTFloorAboveBgMgdl ?: return
        if (!floorAbove.isFinite() || floorAbove <= 0.0) return
        if (bestRaw.size < 2) return
        val floorTerminal = bg + floorAbove
        val before = bestRaw.last()
        if (before >= floorTerminal) return
        val lastIndex = (bestRaw.size - 1).coerceAtLeast(1)
        for (i in 1 until bestRaw.size) {
            val ramp = bg + floorAbove * i.toDouble() / lastIndex.toDouble()
            bestRaw[i] = max(bestRaw[i], ramp)
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.PHYSIOLOGICAL_PHASE,
                summary = "Meal absorption bestT floor +${floorAbove.toInt()} mg/dL " +
                    "phase=${ctx.mealAbsorptionPhase.name} (terminal ramp)",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun applyPhysiologicalPhaseLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        ctx: ScenarioProjectionContext,
        contributors: MutableList<ScenarioContributor>,
        preserveInsulinSlope: Boolean = false,
    ) {
        val before = bestRaw.last()
        val capAbove = ctx.scenarioBestCapAboveBgMgdl
        if (capAbove != null && capAbove.isFinite()) {
            val cap = bg + capAbove
            for (i in 1 until bestRaw.size) {
                if (bestRaw[i] > cap) {
                    bestRaw[i] = cap
                }
            }
        }
        // Do not collapse seeded insulin slope back onto BG during hormonal UAM suppress.
        if (ctx.suppressMealLikeUam && !preserveInsulinSlope) {
            val hormonalBlend = 0.88
            for (i in 1 until bestRaw.size) {
                bestRaw[i] = bg + (bestRaw[i] - bg) * hormonalBlend
            }
        }
        val delta = bestRaw.last() - before
        if (abs(delta) > 0.5 || ctx.suppressMealLikeUam) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.PHYSIOLOGICAL_PHASE,
                    summary = "Phase ${ctx.physiologicalPhase.name} cap=${ctx.scenarioBestCapAboveBgMgdl?.toInt() ?: "-"} " +
                        "uamOff=${ctx.suppressMealLikeUam}",
                    terminalDeltaMgdl = delta,
                ),
            )
        }
    }

    private fun applyTrajectoryLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        delta: Float,
        analysis: TrajectoryAnalysis,
        contributors: MutableList<ScenarioContributor>,
        preserveInsulinSlope: Boolean = false,
    ) {
        val beforeTerminal = bestRaw.last()
        when (analysis.classification) {
            TrajectoryType.OPEN_DIVERGING,
            TrajectoryType.SLOW_DRIFT,
            -> {
                val openness = analysis.metrics.openness.coerceIn(0.2, 1.0)
                val risePerStep = delta.toDouble() * TRAJECTORY_RISE_STEP_FACTOR * openness
                if (risePerStep > 0.0) {
                    for (i in 1 until bestRaw.size) {
                        val bias = risePerStep * (i.toDouble() / bestRaw.size.coerceAtLeast(1))
                        bestRaw[i] = (bestRaw[i] + bias).coerceAtMost(AimiRiskConstants.NUMERIC_CEILING_MGDL)
                    }
                    contributors.add(
                        ScenarioContributor(
                            id = ScenarioContributorId.TRAJECTORY_RISE,
                            summary = "${analysis.classification.name} openness=${aimiFmt2(openness)}",
                            terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                        ),
                    )
                }
            }
            TrajectoryType.TIGHT_SPIRAL -> {
                // Skip spiral collapse when we are preserving insulin-only slope from Floor.
                if (preserveInsulinSlope) return
                for (i in 1 until bestRaw.size) {
                    bestRaw[i] = bg + (bestRaw[i] - bg) * SPIRAL_DAMPING
                }
                contributors.add(
                    ScenarioContributor(
                        id = ScenarioContributorId.TRAJECTORY_SPIRAL_DAMP,
                        summary = "TIGHT_SPIRAL projection damping κ=${aimiFmt2(analysis.metrics.curvature)}",
                        terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                    ),
                )
            }
            TrajectoryType.CLOSING_CONVERGING,
            TrajectoryType.STABLE_ORBIT,
            -> {
                if (preserveInsulinSlope) return
                val targetPull = 0.04
                for (i in 1 until bestRaw.size) {
                    val targetBlend = bg + (bestRaw[i] - bg) * (1.0 - targetPull * i / bestRaw.size)
                    bestRaw[i] = (bestRaw[i] * 0.85 + targetBlend * 0.15)
                }
                contributors.add(
                    ScenarioContributor(
                        id = ScenarioContributorId.TRAJECTORY_CONVERGENCE,
                        summary = analysis.classification.name,
                        terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                    ),
                )
            }
            TrajectoryType.HOVERING,
            TrajectoryType.UNCERTAIN,
            -> Unit
        }
    }

    private fun maxAbsDevFromBg(series: List<Double>, bg: Double): Double {
        if (series.size < 2) return 0.0
        return series.drop(1).maxOf { abs(it - bg) }
    }

    private fun blendSeriesTowardFloor(
        bestRaw: MutableList<Double>,
        floorRaw: List<Double>,
        weight: Double,
    ) {
        val n = min(bestRaw.size, floorRaw.size)
        val w = weight.coerceIn(0.0, 1.0)
        for (i in 1 until n) {
            bestRaw[i] = bestRaw[i] * (1.0 - w) + floorRaw[i] * w
        }
    }

    private fun seedInsulinSlopeFromFloor(
        bestRaw: MutableList<Double>,
        floorRaw: List<Double>,
        bg: Double,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val beforeTerminal = bestRaw.last()
        blendSeriesTowardFloor(bestRaw, floorRaw, INSULIN_SLOPE_SEED_WEIGHT)
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.INSULIN_SLOPE_RESTORE,
                summary = "Seed insulin slope — hybrid≈BG floorDev=${aimiFmt1(maxAbsDevFromBg(floorRaw, bg))} " +
                    "w=${aimiFmt2(INSULIN_SLOPE_SEED_WEIGHT)}",
                terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
            ),
        )
    }

    private fun restoreInsulinSlopeIfCollapsed(
        bestRaw: MutableList<Double>,
        floorRaw: List<Double>,
        bg: Double,
        mealIntent: Boolean,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val bestMaxDev = maxAbsDevFromBg(bestRaw, bg)
        val floorMaxDev = maxAbsDevFromBg(floorRaw, bg)
        if (bestMaxDev > HYBRID_COLLAPSED_MAX_DEV_MGDL) return
        if (floorMaxDev < FLOOR_SLOPE_MIN_DEV_MGDL) return
        val beforeTerminal = bestRaw.last()
        var weight = ((floorMaxDev - FLOOR_SLOPE_MIN_DEV_MGDL) / 20.0)
            .coerceIn(INSULIN_SLOPE_FINAL_WEIGHT_MIN, INSULIN_SLOPE_FINAL_WEIGHT_MAX)
        // False-positive mealIntent must dampen, not fully disable, the anti-flat correction.
        if (mealIntent) {
            weight *= MEAL_INTENT_RESTORE_DAMP
        }
        if (weight < 0.05) return
        blendSeriesTowardFloor(bestRaw, floorRaw, weight)
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.INSULIN_SLOPE_RESTORE,
                summary = "Restore insulin slope — scenario collapsed near BG " +
                    "bestDev=${aimiFmt1(bestMaxDev)} floorDev=${aimiFmt1(floorMaxDev)} " +
                    "w=${aimiFmt2(weight)}" +
                    if (mealIntent) " mealDamp=${aimiFmt2(MEAL_INTENT_RESTORE_DAMP)}" else "",
                terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
            ),
        )
    }

    private fun applyActivityLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        delta: Float,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        if (delta > 0f) {
            for (i in 1 until bestRaw.size) {
                val capped = bg + (bestRaw[i] - bg) * ACTIVITY_RISE_CAP_FACTOR
                bestRaw[i] = min(bestRaw[i], capped)
            }
        } else {
            for (i in 1 until bestRaw.size) {
                bestRaw[i] = bestRaw[i] - 2.0 * (i.toDouble() / bestRaw.size)
            }
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.ACTIVITY_PROTECTION,
                summary = "Activity protection — capped rise / hypo cushion",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun applyPhysioLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        physioSmbFactor: Double,
        physioReactivityFactor: Double,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        val damp = min(physioSmbFactor, physioReactivityFactor).coerceIn(0.85, 1.0)
        val blend = 1.0 - (1.0 - damp) * PHYSIO_DAMP_BLEND
        for (i in 1 until bestRaw.size) {
            bestRaw[i] = bg + (bestRaw[i] - bg) * blend
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.PHYSIO_REACTIVITY,
                summary = "Physio damp smb=${aimiFmt2(physioSmbFactor)} react=${aimiFmt2(physioReactivityFactor)}",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun applyContextSmbLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        contextSmbFactor: Float,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        val blend = contextSmbFactor.toDouble().coerceIn(0.5, 1.1)
        for (i in 1 until bestRaw.size) {
            bestRaw[i] = bg + (bestRaw[i] - bg) * blend
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.CONTEXT_MODULE,
                summary = "Context module SMB×${aimiFmt2(contextSmbFactor.toDouble())}",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun blendTowardTarget(
        bestRaw: MutableList<Double>,
        bg: Double,
        targetBg: Double,
        trajectoryType: TrajectoryType?,
        contributors: MutableList<ScenarioContributor>,
    ) {
        if (trajectoryType == TrajectoryType.OPEN_DIVERGING || trajectoryType == TrajectoryType.SLOW_DRIFT) {
            return
        }
        val before = bestRaw.last()
        val horizonWeight = 0.08
        for (i in 1 until bestRaw.size) {
            val w = horizonWeight * i / bestRaw.size
            bestRaw[i] = bestRaw[i] * (1.0 - w) + targetBg * w
        }
        if (abs(bestRaw.last() - before) > 1.0) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.TARGET_BLEND,
                    summary = "Soft blend toward target ${targetBg.toInt()} mg/dL",
                    terminalDeltaMgdl = bestRaw.last() - before,
                ),
            )
        }
    }

    internal fun clampSeriesToInts(points: List<Double>): List<Int> =
        points.map { point ->
            point.coerceIn(AimiRiskConstants.NUMERIC_FLOOR_MGDL, AimiRiskConstants.NUMERIC_CEILING_MGDL).toInt()
        }

    internal fun isMealRiseConfirmed(
        bg: Double,
        delta: Float,
        ctx: ScenarioProjectionContext,
    ): Boolean =
        ctx.mealIntent ||
            (delta >= PredictiveHypoConstants.RISING_MODERATE_DELTA.toFloat() && bg >= 90.0)
}
