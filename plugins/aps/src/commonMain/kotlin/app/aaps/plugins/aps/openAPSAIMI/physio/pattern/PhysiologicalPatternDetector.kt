package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import kotlin.math.abs

/**
 * Detects active [PhysiologicalPatternId] entries from tick-local + MTR wearable context.
 */
object PhysiologicalPatternDetector {

    fun detect(input: PhysiologicalPatternInput): PhysiologicalPatternSnapshot {
        val raw = buildList {
            matchFromPhase(input)?.let { add(it) }
            matchSleepRecovery(input)?.let { addAll(it) }
            matchMealAbsorption(input)?.let { addAll(it) }
            matchActivity(input)?.let { addAll(it) }
            matchInsulinTrajectory(input)?.let { addAll(it) }
            matchContextIntents(input)?.let { addAll(it) }
            matchNgr(input)?.let { add(it) }
        }.filter { it.confidence >= 0.30 }

        val stabilized = PhysiologicalPatternHysteresis.stabilize(raw, input.nowMs)
        return PhysiologicalPatternPolicy.aggregate(stabilized, input.maxSmbHbU)
    }

    private fun matchFromPhase(input: PhysiologicalPatternInput): PhysiologicalPatternReading? {
        val phase = input.phaseOutput ?: return null
        val patternId = phase.phase.toPatternId() ?: return null
        return PhysiologicalPatternReading(
            id = patternId,
            confidence = phase.confidence,
            reason = phase.policy.reason,
        )
    }

    private fun matchSleepRecovery(input: PhysiologicalPatternInput): List<PhysiologicalPatternReading> {
        val ctx = input.physioContext ?: return emptyList()
        val out = mutableListOf<PhysiologicalPatternReading>()

        if (input.sleepDebtMinutes >= 60 || ctx.poorSleepDetected) {
            val conf = (0.55 + minOf(0.35, input.sleepDebtMinutes / 240.0)).coerceIn(0.0, 0.95)
            out += reading(PhysiologicalPatternId.SLEEP_DEBT, conf, "sleepDebt=${input.sleepDebtMinutes}m")
        }

        if (ctx.hrvDepressed || ctx.hrvDeviationZ <= -1.2) {
            val conf = (0.50 + abs(ctx.hrvDeviationZ.coerceAtMost(0.0)) * 0.15).coerceIn(0.0, 0.92)
            out += reading(PhysiologicalPatternId.HRV_DEPRESSED, conf, "hrvZ=${aimiFmt2(ctx.hrvDeviationZ)}")
        }

        when (ctx.state) {
            PhysioStateMTR.RECOVERY_NEEDED ->
                out += reading(PhysiologicalPatternId.RECOVERY_NEEDED, ctx.confidence.coerceIn(0.5, 0.95), "state=RECOVERY")
            PhysioStateMTR.STRESS_DETECTED ->
                out += reading(PhysiologicalPatternId.PSYCHOSOCIAL_STRESS, ctx.confidence.coerceIn(0.5, 0.92), "state=STRESS")
            PhysioStateMTR.INFECTION_RISK ->
                out += reading(PhysiologicalPatternId.INFECTION_RISK, ctx.confidence.coerceIn(0.55, 0.95), "state=INFECTION")
            else -> Unit
        }

        val morningRise = input.hourOfDay in 5..11 &&
            input.mealCobG < 1.0 &&
            input.deltaMgdlPer5 >= 1.5 &&
            (ctx.poorSleepDetected || ctx.hrvDepressed || input.sleepDebtMinutes >= 45)
        if (morningRise) {
            val conf = (0.62 + ctx.confidence * 0.25).coerceIn(0.0, 0.96)
            out += reading(
                PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
                conf,
                "morningRise sleepDebt=${input.sleepDebtMinutes} hrvZ=${aimiFmt2(ctx.hrvDeviationZ)}",
            )
        }

        return out
    }

    private fun matchMealAbsorption(input: PhysiologicalPatternInput): List<PhysiologicalPatternReading> {
        val out = mutableListOf<PhysiologicalPatternReading>()
        when (input.mealAbsorptionPhase) {
            MealAbsorptionPhase.FIRST_WAVE ->
                out += reading(PhysiologicalPatternId.MEAL_FIRST_WAVE, 0.80, "mealAbsorption=FIRST_WAVE")
            MealAbsorptionPhase.SECOND_WAVE ->
                out += reading(PhysiologicalPatternId.MEAL_SECOND_WAVE, 0.82, "mealAbsorption=SECOND_WAVE")
            MealAbsorptionPhase.LATE_FAT ->
                out += reading(PhysiologicalPatternId.LATE_FAT_PROTEIN, 0.78, "mealAbsorption=LATE_FAT")
            else -> Unit
        }
        return out
    }

    private fun matchActivity(input: PhysiologicalPatternInput): List<PhysiologicalPatternReading> {
        val out = mutableListOf<PhysiologicalPatternReading>()
        val hrDelta = input.heartRateBpm - input.restingHeartRateBpm
        val mealRiseContext = input.mealDeliveryPriority ||
            input.mealAbsorptionPhase == MealAbsorptionPhase.FIRST_WAVE ||
            input.mealAbsorptionPhase == MealAbsorptionPhase.SECOND_WAVE ||
            input.mealAbsorptionPhase == MealAbsorptionPhase.INTER_WAVE ||
            input.mealAbsorptionPhase == MealAbsorptionPhase.PEAK_CORRECTION
        if (!mealRiseContext &&
            (input.sportTime || (input.stepsLast15m >= 120 && hrDelta >= 25))
        ) {
            out += reading(
                PhysiologicalPatternId.EXERCISE_ACUTE,
                0.85,
                "sport=${input.sportTime} steps=${input.stepsLast15m} hrΔ=$hrDelta",
            )
        }
        if (input.exerciseLockout) {
            out += reading(PhysiologicalPatternId.EXERCISE_LOCKOUT, 0.90, "exerciseLockout")
        }
        val ctx = input.physioContext
        if (ctx?.activityReduced == true && input.stepsLast15m < 30 && input.hourOfDay in 8..20) {
            out += reading(PhysiologicalPatternId.SEDENTARY_DAY, 0.55, "activityReduced vs baseline")
        }
        if (input.contextActivity && !input.sportTime) {
            out += reading(PhysiologicalPatternId.POST_EXERCISE_SENSITIVITY, 0.70, "contextActivity recent")
        }
        return out
    }

    private fun matchInsulinTrajectory(input: PhysiologicalPatternInput): List<PhysiologicalPatternReading> {
        val out = mutableListOf<PhysiologicalPatternReading>()
        if (input.stackingSurveillance) {
            out += reading(
                PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                0.88,
                "IOB=${aimiFmt2(input.iobU)} surveillance",
            )
        }
        if (input.postHypoOrdinal != null && input.postHypoOrdinal > 0) {
            val conf = (0.60 + input.postHypoOrdinal * 0.08).coerceAtMost(0.95)
            out += reading(PhysiologicalPatternId.POST_HYPO_REBOUND, conf, "postHypo=${input.postHypoOrdinal}")
        }
        if (input.compressionImpossibleRise) {
            out += reading(PhysiologicalPatternId.COMPRESSION_ARTIFACT, 0.92, "compressionGuard")
        }
        if (input.phaseOutput?.phase == PhysiologicalPhase.HYPER_INSTALLED) {
            out += reading(
                PhysiologicalPatternId.HYPER_INSTALLED,
                input.phaseOutput.confidence,
                "dwell=${input.dwellAboveHighBgMinutes}m",
            )
        }
        return out
    }

    private fun matchContextIntents(input: PhysiologicalPatternInput): List<PhysiologicalPatternReading> {
        val out = mutableListOf<PhysiologicalPatternReading>()
        if (input.contextIllness) {
            out += reading(PhysiologicalPatternId.CONTEXT_ILLNESS, 0.85, "contextIllness")
        }
        if (input.contextStress) {
            out += reading(PhysiologicalPatternId.CONTEXT_STRESS_INTENT, 0.80, "contextStress")
        }
        if (input.contextActivity) {
            out += reading(PhysiologicalPatternId.CONTEXT_ACTIVITY_INTENT, 0.75, "contextActivityIntent")
        }
        return out
    }

    private fun matchNgr(input: PhysiologicalPatternInput): PhysiologicalPatternReading? {
        if (input.hourOfDay !in 22..23 && input.hourOfDay !in 0..5) return null
        if (input.mealCobG >= 1.0) return null
        if (input.deltaMgdlPer5 < 0.8) return null
        return reading(
            PhysiologicalPatternId.NGR_NIGHT_GROWTH,
            0.65,
            "night Δ=${aimiFmt1(input.deltaMgdlPer5)}",
        )
    }

    private fun reading(id: PhysiologicalPatternId, confidence: Double, reason: String) =
        PhysiologicalPatternReading(id, confidence.coerceIn(0.0, 1.0), reason)
}
