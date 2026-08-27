package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import kotlin.math.max
import kotlin.math.min

/**
 * Elastic insulin-load surveillance for RBT/HTR SMB demand.
 *
 * Uses TDD + patient weight as a **physiological budget** (not a hard Max-IOB wall),
 * trajectory energy/coherence, delta deceleration, and insulin activity stage to produce
 * a continuous multiplier g ∈ [G_MIN, 1.0] plus an optional per-tick SMB cap.
 *
 * Pure logic — unit-testable, no Android deps.
 */
object InsulinLoadGovernor {

    /** Reference TDD (U/24h) aligned with `DetermineBasalaimiSMB2` tight-spiral scaling. */
    private const val PHYS_BUDGET_TDD_REFERENCE_U = 55.0

    /** IOB budget (U) at reference TDD — matches spiral IOB threshold legacy 8 U. */
    private const val PHYS_BUDGET_IOB_AT_REFERENCE_U = 8.0

    /** Reference weight (kg) — second term of physiological budget. */
    private const val PHYS_BUDGET_WEIGHT_REFERENCE_KG = 75.0

    private const val G_MIN = 0.35
    private const val G_MAX = 1.0
    private const val SMOOTH_PRIOR_WEIGHT = 0.4

    private const val SHARP_RISE_DELTA = 4.5
    private const val SHARP_RISE_SHORT_AVG = 4.5
    private const val DUAL_RISE_DELTA = 3.2
    private const val DUAL_RISE_SHORT_AVG = 3.0

    enum class Tier {
        /** Full correction authority (g ≈ 1). */
        FULL,
        /** Soft damping — SMB reduced, correction still active. */
        SOFT,
        /** Surveillance — meaningful SMB cap, prefer TBR bias. */
        SURVEILLANCE,
        /** Wait — minimal SMB; escape only on sharp rise / extreme projection. */
        WAIT,
    }

    data class Input(
        val iobU: Double,
        val tdd24hU: Double,
        val patientWeightKg: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val deltaPrevMgdlPer5: Double?,
        val bgDerivShort: Double?,
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val bestTerminalMgdl: Double,
        val minPredictedBgMgdl: Double?,
        val eventualBgMgdl: Double?,
        val trajectoryEnergy: Double?,
        val trajectoryCoherence: Double?,
        val insulinActivityStageOrdinal: Int?,
        val insulinActivityNow: Double?,
        val mealAbsorptionPhase: MealAbsorptionPhase,
        val mealDeliveryPriority: Boolean,
        val lastMultiplierG: Double = G_MAX,
    )

    data class Evaluation(
        val tier: Tier,
        val multiplierG: Double,
        val rawMultiplierG: Double,
        val smbTickCapU: Double,
        val physBudgetU: Double,
        val stackScore: Double,
        val riseScore: Double,
        val deltaDecelScore: Double,
        val reasonCodes: List<String>,
        val summary: String,
    ) {
        fun tuningReferenceAscii(): String =
            "Logic: InsulinLoadGovernor.kt. Budget=max(TDD×${PHYS_BUDGET_IOB_AT_REFERENCE_U / PHYS_BUDGET_TDD_REFERENCE_U}, " +
                "weight×${PHYS_BUDGET_IOB_AT_REFERENCE_U / PHYS_BUDGET_WEIGHT_REFERENCE_KG}). " +
                "g=EMA(rawG). Tiers FULL/SOFT/SURVEILLANCE/WAIT. JSONL: recursive_belief.load_governor."
    }

    /**
     * Physiological IOB budget (U) — same scaling family as tight-spiral cap, without hard Max-IOB.
     */
    fun physiologicalBudgetU(tdd24hU: Double, patientWeightKg: Double): Double {
        val fromTdd = if (tdd24hU.isFinite() && tdd24hU > 0.0) {
            tdd24hU * (PHYS_BUDGET_IOB_AT_REFERENCE_U / PHYS_BUDGET_TDD_REFERENCE_U)
        } else {
            PHYS_BUDGET_IOB_AT_REFERENCE_U
        }
        val fromWeight = if (patientWeightKg.isFinite() && patientWeightKg > 0.0) {
            patientWeightKg * (PHYS_BUDGET_IOB_AT_REFERENCE_U / PHYS_BUDGET_WEIGHT_REFERENCE_KG)
        } else {
            0.0
        }
        return max(fromTdd, fromWeight).coerceAtLeast(PHYS_BUDGET_IOB_AT_REFERENCE_U * 0.5)
    }

    /**
     * Damp the boost ABOVE 1.0x of an adaptive basal multiplier in proportion to remaining IOB headroom
     * against [budgetU]. Returns [multiplier] unchanged when it is not a boost (<= 1.0). For a boost the
     * result is in [1.0, multiplier]: full multiplier at zero IOB, neutralised to 1.0 at/above budget.
     * Strictly conservative — it can only reduce a boost, never raise one and never add insulin.
     */
    fun iobBudgetBrakedMultiplier(multiplier: Double, iobU: Double, budgetU: Double): Double {
        if (multiplier <= 1.0 || !multiplier.isFinite()) return multiplier
        val safeBudget = budgetU.coerceAtLeast(0.5)
        val headroomFrac = ((safeBudget - iobU) / safeBudget).coerceIn(0.0, 1.0)
        return 1.0 + (multiplier - 1.0) * headroomFrac
    }

    fun evaluate(input: Input): Evaluation {
        val reasonCodes = mutableListOf<String>()
        val budget = physiologicalBudgetU(input.tdd24hU, input.patientWeightKg)
        val iobSafe = input.iobU.coerceAtLeast(0.0)
        val iobRatio = (iobSafe / budget.coerceAtLeast(0.5)).coerceIn(0.0, 2.5)

        val deltaDecelScore = computeDeltaDecelScore(
            delta = input.deltaMgdlPer5,
            deltaPrev = input.deltaPrevMgdlPer5,
            bgDerivShort = input.bgDerivShort,
        )
        if (deltaDecelScore > 0.25) reasonCodes += "DELTA_DECEL"

        val trajE = input.trajectoryEnergy?.takeIf { it.isFinite() } ?: 0.0
        val energyNorm = ((trajE - 2.0) / 8.0).coerceIn(0.0, 1.0)
        if (energyNorm > 0.5) reasonCodes += "TRAJ_ENERGY"

        val coherence = input.trajectoryCoherence?.takeIf { it.isFinite() } ?: 0.5
        val lowCoherence = ((0.35 - coherence) / 0.35).coerceIn(0.0, 1.0)

        val stage = input.insulinActivityStageOrdinal?.let { ord ->
            ActivityStage.entries.getOrNull(ord)
        }
        val activityNow = input.insulinActivityNow?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val stageStack = when (stage) {
            ActivityStage.PEAK -> 0.85
            ActivityStage.RISING -> 0.65
            ActivityStage.FALLING -> 0.35
            ActivityStage.TAIL, null -> 0.15
        }
        if (stageStack > 0.6 && activityNow > 0.15) reasonCodes += "INSULIN_ACTIVE"

        val ev = InsulinStackingStance.sanitizeEventualMgdlForStackingSignals(input.bgMgdl, input.eventualBgMgdl)
        val mn = input.minPredictedBgMgdl?.takeIf { it.isFinite() }
        val predDrop = listOfNotNull(
            ev?.let { if (it < input.bgMgdl - 6.0) 1.0 else 0.0 },
            mn?.let { if (it < input.bgMgdl - 10.0) 1.0 else 0.0 },
        ).maxOrNull() ?: 0.0
        if (predDrop > 0.0) reasonCodes += "PRED_DROP"

        val stackScore = (
            iobRatio.coerceAtMost(1.0) * 0.35 +
                energyNorm * 0.30 +
                deltaDecelScore * 0.20 +
                stageStack * 0.10 +
                lowCoherence * 0.05
            ).coerceIn(0.0, 1.0)

        val projectionLead = (input.bestTerminalMgdl - input.bgMgdl).coerceAtLeast(0.0)
        val projectionNorm = (projectionLead / 80.0).coerceIn(0.0, 1.0)
        val deltaAccel = computeDeltaAccelScore(input.deltaMgdlPer5, input.deltaPrevMgdlPer5)
        val mealRise = when (input.mealAbsorptionPhase) {
            MealAbsorptionPhase.FIRST_WAVE -> 0.35
            MealAbsorptionPhase.SECOND_WAVE -> if (input.deltaMgdlPer5 >= 1.2) 0.45 else 0.20
            MealAbsorptionPhase.INTER_WAVE -> 0.10
            else -> if (input.mealDeliveryPriority) 0.15 else 0.0
        }
        val sharpRise = if (input.deltaMgdlPer5 >= SHARP_RISE_DELTA || input.shortAvgDeltaMgdlPer5 >= SHARP_RISE_SHORT_AVG) {
            1.0
        } else if (input.deltaMgdlPer5 >= DUAL_RISE_DELTA && input.shortAvgDeltaMgdlPer5 >= DUAL_RISE_SHORT_AVG) {
            0.75
        } else {
            0.0
        }
        if (sharpRise > 0.0) reasonCodes += "SHARP_RISE"

        val riseScore = (
            deltaAccel * 0.30 +
                projectionNorm * 0.25 +
                mealRise +
                sharpRise * 0.20
            ).coerceIn(0.0, 1.0)

        var rawG = (1.0 - 0.65 * stackScore + 0.25 * riseScore).coerceIn(G_MIN, G_MAX)

        if (iobSafe < budget * 0.55 && sharpRise < 0.5) {
            rawG = max(rawG, 0.92)
            reasonCodes += "LOW_IOB_BUDGET"
        }
        // ⛔ L'échappement de montée s'arrête au budget physiologique.
        //
        // Sans `iobSafe < budget`, cette clause épinglait `rawG` à 0.88 — donc `smoothedG` à 0.928,
        // au-dessus du seuil `FULL` de 0.92 — pour aussi longtemps que la glycémie montait, quelle
        // que soit l'insuline déjà à bord. Le déjeuner du 09/08/2026 est resté en `FULL` jusqu'à
        // IOB 16,75 U contre un budget de 8,11, soit 2,07 fois le budget, et 14,09 U de SMB ont été
        // servis pendant que la vanne restait ouverte. C'est la sortie de `FULL` à 15:11 qui a mis
        // fin à l'épisode, pas une décision.
        //
        // `ESCAPE_PROJECTION` juste en dessous porte déjà cette garde. Son absence ici était une
        // omission, pas une intention : une montée justifie de continuer à corriger, elle ne
        // justifie pas de dépasser ce que le corps peut absorber.
        if (iobSafe < budget &&
            (sharpRise >= 0.75 || (input.bgMgdl > input.targetBgMgdl + 85 && input.deltaMgdlPer5 > 0.8))
        ) {
            rawG = max(rawG, 0.88)
            reasonCodes += "ESCAPE_RISE"
        }
        if (projectionLead > 80.0 && input.deltaMgdlPer5 > 1.0 && iobSafe < budget) {
            rawG = max(rawG, 0.90)
            reasonCodes += "ESCAPE_PROJECTION"
        }

        val waitSignal = trajE > 8.0 && iobSafe > budget && predDrop > 0.0 && sharpRise < 0.5
        if (waitSignal) {
            rawG = min(rawG, 0.42)
            reasonCodes += "WAIT_STACK"
        }

        val smoothedG = smoothMultiplier(rawG, input.lastMultiplierG.coerceIn(G_MIN, G_MAX))

        val tier = when {
            smoothedG >= 0.92 -> Tier.FULL
            smoothedG >= 0.72 -> Tier.SOFT
            smoothedG >= 0.50 -> Tier.SURVEILLANCE
            else -> Tier.WAIT
        }

        val smbTickCapU = when (tier) {
            Tier.FULL -> Double.MAX_VALUE
            Tier.SOFT -> max(1.2, input.tdd24hU * 0.035)
            Tier.SURVEILLANCE -> max(0.55, input.tdd24hU * 0.018)
            Tier.WAIT -> max(0.35, input.tdd24hU * 0.010)
        }

        val summary = buildString {
            append("LOAD_GOV ${tier.name} g=${aimiFmt2(smoothedG)} ")
            append("budget=${aimiFmt1(budget)}U iob=${aimiFmt1(iobSafe)}U ")
            append("stack=${aimiFmt2(stackScore)} rise=${aimiFmt2(riseScore)} ")
            append("decel=${aimiFmt2(deltaDecelScore)}")
            if (trajE > 0.0) append(" E=${aimiFmt1(trajE)}")
            if (tier != Tier.FULL) append(" cap=${aimiFmt2(smbTickCapU)}U")
        }

        return Evaluation(
            tier = tier,
            multiplierG = smoothedG,
            rawMultiplierG = rawG,
            smbTickCapU = smbTickCapU,
            physBudgetU = budget,
            stackScore = stackScore,
            riseScore = riseScore,
            deltaDecelScore = deltaDecelScore,
            reasonCodes = reasonCodes.distinct(),
            summary = summary,
        )
    }

    internal fun computeDeltaDecelScore(
        delta: Double,
        deltaPrev: Double?,
        bgDerivShort: Double?,
    ): Double {
        if (!delta.isFinite() || delta <= 0.0) return 0.0
        var score = 0.0
        val prev = deltaPrev?.takeIf { it.isFinite() && it > 0.0 }
        if (prev != null && delta < prev) {
            score = max(score, ((prev - delta) / prev).coerceIn(0.0, 1.0))
        }
        val deriv = bgDerivShort?.takeIf { it.isFinite() }
        if (deriv != null && deriv < 0.0 && delta > 0.5) {
            score = max(score, min(1.0, (-deriv / 4.0).coerceAtLeast(0.0)))
        }
        return score.coerceIn(0.0, 1.0)
    }

    internal fun computeDeltaAccelScore(delta: Double, deltaPrev: Double?): Double {
        if (!delta.isFinite()) return 0.0
        val prev = deltaPrev?.takeIf { it.isFinite() }
        return when {
            prev == null -> (delta / 5.0).coerceIn(0.0, 1.0)
            delta >= prev && delta > 0.0 -> ((delta - prev) / max(prev, 1.0) + 0.35).coerceIn(0.0, 1.0)
            delta > 0.0 -> (delta / max(prev, 1.0) * 0.5).coerceIn(0.0, 0.6)
            else -> 0.0
        }
    }

    internal fun smoothMultiplier(rawG: Double, lastG: Double): Double {
        val last = lastG.coerceIn(G_MIN, G_MAX)
        val raw = rawG.coerceIn(G_MIN, G_MAX)
        return (last * SMOOTH_PRIOR_WEIGHT + raw * (1.0 - SMOOTH_PRIOR_WEIGHT)).coerceIn(G_MIN, G_MAX)
    }
}
