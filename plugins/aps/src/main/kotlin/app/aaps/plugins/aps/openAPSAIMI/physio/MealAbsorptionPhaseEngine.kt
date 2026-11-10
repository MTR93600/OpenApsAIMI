package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import kotlin.math.max
import kotlin.math.min

/**
 * Unified meal-absorption belief and phase machine.
 * Feeds IOB surveillance, HTR, meal priority, and basal overlays from one state.
 */
object MealAbsorptionPhaseEngine {

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val deltaPrevMgdlPer5: Double?,
        val mealCobG: Double,
        val hourOfDay: Int,
        val iobU: Double,
        val maxIobU: Double,
        val bestTerminalMgdl: Double,
        val floorTerminalMgdl: Double,
        val gapPrevMgdl: Double?,
        val heartRateBpm: Int,
        val restingHeartRateBpm: Int,
        val stepsLast15m: Int,
        /**
         * A meal the person declared (meal mode, advisor carbs, explicit trigger, FCL/anticip note).
         * Not the loop's own SMBs, not the previous phase: see `MealSafetyContext.declaredMealIntent`.
         */
        val mealIntent: Boolean,
        val physiologicalPhase: PhysiologicalPhase,
        val estimatedRa: Double? = null,
        val hoursSinceBolus: Double? = null,
        val lateFatProteinRise: Boolean = false,
        /** Glucose-history confirmation of the rise; null when unknown. Only NONE changes a decision. */
        val riseLevel: RiseConfirmation.Level? = null,
        val nowMs: Long,
    )

    data class Output(
        val phase: MealAbsorptionPhase,
        val belief: Double,
        val reason: String,
        val deltaMgdlPer5: Double,
        val gapMgdl: Double,
        val bestTerminalMgdl: Double,
        val memoryActive: Boolean,
        val waveCount: Int,
        val mealDeliveryPriority: Boolean,
        val chronoPrior: Double,
        val kineticScore: Double,
        val trajectoryScore: Double,
        val physioScore: Double,
        /** Meal evidence of the raw classification, whatever branch was chosen. Feeds [MealAbsorptionMemory]. */
        val evidence: MealAbsorptionMemory.Evidence = MealAbsorptionMemory.Evidence.NONE,
        /** The raw NONE must not be turned back into the previous phase by the hysteresis. */
        val suppressHold: Boolean = false,
        /** How the belief was built, for the export. `null` only on outputs not made by [classifyRaw]. */
        val beliefParts: BeliefParts? = null,
        /** Phase before the hysteresis (a held tick shows NONE here). */
        val rawPhase: String? = null,
    )

    /**
     * The belief in parts: the weighted base, then what each bonus really added after the 1.0 clamp, so
     * `base + intent + cob + physioMeal + ra == belief`. Export only.
     */
    data class BeliefParts(
        val base: Double,
        val intent: Double,
        val cob: Double,
        val physioMeal: Double,
        val ra: Double,
        val fastRise: Boolean,
        val moderateRise: Boolean,
    )

    private const val FAST_RISE_DELTA = 2.5
    private const val FAST_RISE_SHORT = 2.2
    private const val FAST_RISE_COMBINED = 3.2
    private const val SECOND_WAVE_DELTA = 1.2
    private const val INTER_WAVE_DELTA_MAX = 2.25

    /** Under this glucose a moderate rise in an open episode is not "inter-wave". */
    internal const val INTER_WAVE_MIN_BG_MGDL = 100.0

    /** Entered carbs that count as meal evidence for [MealAbsorptionMemory]. */
    private const val COB_EVIDENCE_G = 5.0

    fun evaluate(raw: Input): Output {
        val classified = classifyRaw(raw)
        var stabilized = MealAbsorptionPhaseHysteresis.stabilize(classified)
        // Evidence comes from the raw classification: a held copy is a NONE tick, never evidence.
        MealAbsorptionMemory.update(stabilized, raw.nowMs, classified.evidence)
        if (stabilized !== classified && !MealAbsorptionMemory.isActive(raw.nowMs)) {
            // The episode closed: nothing left to hold.
            MealAbsorptionPhaseHysteresis.reset()
            stabilized = classified
        }
        return stabilized.copy(
            rawPhase = classified.phase.name,
            memoryActive = MealAbsorptionMemory.isActive(raw.nowMs),
            waveCount = MealAbsorptionMemory.waveCount,
        )
    }

    internal fun classifyRaw(input: Input): Output {
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            input.targetBgMgdl,
            input.highBgPreferenceMgdl,
        )
        val dev = input.bgMgdl - input.targetBgMgdl
        val gap = input.bestTerminalMgdl - input.floorTerminalMgdl
        val projectionLead = input.bestTerminalMgdl - input.bgMgdl
        val deltaPrev = input.deltaPrevMgdlPer5 ?: MealAbsorptionMemory.lastDeltaMgdlPer5
        val gapPrev = input.gapPrevMgdl ?: MealAbsorptionMemory.lastGapMgdl
        val accel = if (deltaPrev != null) (input.deltaMgdlPer5 - deltaPrev) / 5.0 else 0.0
        val memoryActive = MealAbsorptionMemory.isActive(input.nowMs)

        val chrono = chronoPrior(input.hourOfDay)
        val kinetic = kineticScore(input)
        val trajectory = trajectoryScore(input, highBand, gap, gapPrev, projectionLead)
        val physio = physioScore(input)

        val beliefBase = (0.30 * chrono + 0.35 * kinetic + 0.25 * trajectory + 0.10 * physio)
            .coerceIn(0.0, 1.0)
        var belief = beliefBase
        // Each bonus is kept as what it really added, after the clamp.
        fun addBonus(on: Boolean, amount: Double): Double {
            if (!on) return 0.0
            val before = belief
            belief = min(1.0, belief + amount)
            return belief - before
        }
        val bonusIntent = addBonus(input.mealIntent, 0.25)
        val bonusCob = addBonus(input.mealCobG >= 5.0, 0.30)
        val bonusPhysioMeal = addBonus(input.physiologicalPhase.isMealRisk, 0.20)
        val bonusRa = addBonus(input.estimatedRa?.let { it >= 2.0 } == true, 0.15)

        val fastRise = isFastRise(input)
        val moderateRise = input.deltaMgdlPer5 >= 1.5 ||
            input.shortAvgDeltaMgdlPer5 >= 1.2 ||
            input.combinedDeltaMgdlPer5 >= 2.0
        val gapWidening = gapPrev != null && gap > gapPrev + 8.0
        val reAcceleration = deltaPrev != null &&
            input.deltaMgdlPer5 > deltaPrev + 0.8 &&
            input.deltaMgdlPer5 >= SECOND_WAVE_DELTA

        val phase = when {
            input.lateFatProteinRise && input.mealCobG < 1.0 -> MealAbsorptionPhase.LATE_FAT
            memoryActive && reAcceleration && input.bgMgdl >= 160.0 -> MealAbsorptionPhase.SECOND_WAVE
            memoryActive && gapWidening && moderateRise && input.bgMgdl >= 140.0 -> MealAbsorptionPhase.SECOND_WAVE
            input.bgMgdl >= 180.0 &&
                input.iobU >= iobFloorU(input.maxIobU) &&
                input.deltaMgdlPer5 <= 2.0 &&
                (memoryActive || belief >= 0.45) -> MealAbsorptionPhase.PEAK_CORRECTION
            memoryActive &&
                input.deltaMgdlPer5 <= INTER_WAVE_DELTA_MAX &&
                input.bgMgdl >= 140.0 &&
                dev >= highBand * 0.5 -> MealAbsorptionPhase.INTER_WAVE
            fastRise && belief >= 0.55 -> MealAbsorptionPhase.FIRST_WAVE
            // Without a glucose floor this rule called a rise at 54 mg/dL "inter-wave". It must give
            // NONE here, not fall through to the chrono FIRST_WAVE rule below.
            memoryActive && moderateRise && belief >= 0.40 ->
                if (input.bgMgdl < INTER_WAVE_MIN_BG_MGDL) MealAbsorptionPhase.NONE else MealAbsorptionPhase.INTER_WAVE
            belief >= 0.55 && moderateRise && chrono >= 0.55 -> MealAbsorptionPhase.FIRST_WAVE
            else -> MealAbsorptionPhase.NONE
        }

        val interWaveFloorBlocked = phase == MealAbsorptionPhase.NONE && memoryActive && moderateRise &&
            belief >= 0.40 && input.bgMgdl < INTER_WAVE_MIN_BG_MGDL
        val evidence = MealAbsorptionMemory.Evidence(
            firstWave = belief >= 0.55 && (fastRise || (moderateRise && chrono >= 0.55)),
            lateFat = input.lateFatProteinRise && input.mealCobG < 1.0,
            declaredMeal = input.mealIntent,
            cob = input.mealCobG >= COB_EVIDENCE_G,
        )

        val deliveryPriority = mealDeliveryPriority(
            phase = phase,
            input = input,
            belief = belief,
            fastRise = fastRise,
            moderateRise = moderateRise,
        )

        return Output(
            phase = phase,
            belief = belief,
            reason = buildReason(phase, belief, chrono, kinetic, trajectory, physio, memoryActive),
            deltaMgdlPer5 = input.deltaMgdlPer5,
            gapMgdl = gap,
            bestTerminalMgdl = input.bestTerminalMgdl,
            memoryActive = memoryActive,
            waveCount = MealAbsorptionMemory.waveCount,
            mealDeliveryPriority = deliveryPriority,
            chronoPrior = chrono,
            kineticScore = kinetic,
            trajectoryScore = trajectory,
            physioScore = physio,
            evidence = evidence,
            suppressHold = interWaveFloorBlocked,
            beliefParts = BeliefParts(beliefBase, bonusIntent, bonusCob, bonusPhysioMeal, bonusRa, fastRise, moderateRise),
        )
    }

    internal fun chronoPrior(hourOfDay: Int): Double = CircadianMealProfileStore.priorForHour(hourOfDay)

    internal fun kineticScore(input: Input): Double {
        val d = input.deltaMgdlPer5.coerceAtLeast(0.0)
        val s = input.shortAvgDeltaMgdlPer5.coerceAtLeast(0.0)
        val c = input.combinedDeltaMgdlPer5.coerceAtLeast(0.0)
        val deltaPrev = input.deltaPrevMgdlPer5 ?: MealAbsorptionMemory.lastDeltaMgdlPer5
        val accelBonus = if (deltaPrev != null && input.deltaMgdlPer5 > deltaPrev + 0.5) 0.15 else 0.0
        val base = max(max(d / 6.0, s / 5.0), c / 7.0).coerceIn(0.0, 1.0)
        return min(1.0, base + accelBonus)
    }

    internal fun trajectoryScore(
        input: Input,
        highBand: Double,
        gap: Double,
        gapPrev: Double?,
        projectionLead: Double,
    ): Double {
        var score = 0.0
        if (projectionLead >= highBand * 0.35) score += 0.35
        if (gap >= 25.0) score += 0.25
        if (gapPrev != null && gap > gapPrev + 8.0) score += 0.25
        if (input.bestTerminalMgdl >= input.bgMgdl + highBand * 0.45) score += 0.15
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Body signals that a meal is being absorbed — steps only.
     *
     * **The heart rate deliberately says nothing here.** It used to add 0.35 to this score for a rise
     * of 5 to 18 bpm over rest, which is a shape a meal makes and also the shape stress, a hurry, a
     * hot room or a climb up the stairs make. The engine cannot tell them apart, and the user's own
     * episode showed the cost: a rush to fetch the children, no food, a real stress rise, and the
     * meal machinery reading it as absorption. The rule now is the one the user asked for — the
     * heart rate may protect, it may never be evidence of a meal.
     *
     * The old penalty for a very high heart rate was dead anyway: the score starts at 0 and is
     * clamped at 0, so a negative term could only ever cancel the steps bonus.
     */
    internal fun physioScore(input: Input): Double {
        var score = 0.0
        if (input.stepsLast15m >= 80 && input.deltaMgdlPer5 >= 2.0) score += 0.10
        return score.coerceIn(0.0, 1.0)
    }

    internal fun isFastRise(input: Input): Boolean =
        input.deltaMgdlPer5 >= FAST_RISE_DELTA ||
            input.shortAvgDeltaMgdlPer5 >= FAST_RISE_SHORT ||
            input.combinedDeltaMgdlPer5 >= FAST_RISE_COMBINED

    /**
     * Insulin on board above which a very high glucose counts as the PEAK_CORRECTION phase.
     *
     * It used to mirror `InsulinStackingStance.iobFloorU`, and it deliberately does **not** any more.
     * There the number means "enough insulin on board to start watching for stacking", so lowering
     * it adds protection; here it is one of the conditions of a phase that **switches that watching
     * off**, so lowering it would remove protection. Same number, opposite meaning — keep them apart.
     */
    internal fun iobFloorU(maxIob: Double): Double {
        val maxIobSafe = maxIob.coerceAtLeast(0.5)
        return max(3.2, maxIobSafe * 0.26)
    }

    internal fun mealDeliveryPriority(
        phase: MealAbsorptionPhase,
        input: Input,
        belief: Double,
        fastRise: Boolean,
        moderateRise: Boolean,
    ): Boolean {
        if (input.mealCobG < 6.0 && !input.mealIntent &&
            (
                input.physiologicalPhase.isHormonalRisk ||
                    input.physiologicalPhase == PhysiologicalPhase.STRESS_CORTISOL ||
                    input.physiologicalPhase.isEndogenousRisk
                )
        ) {
            return false
        }
        // INTER_WAVE and SECOND_WAVE come from the memory, not from this tick's glucose. Without a
        // declared meal, COB or a confirmed rise they must not claim delivery priority.
        val memoryDerived = phase == MealAbsorptionPhase.INTER_WAVE || phase == MealAbsorptionPhase.SECOND_WAVE
        if (memoryDerived && !input.mealIntent && input.mealCobG < 6.0 &&
            input.riseLevel == RiseConfirmation.Level.NONE
        ) {
            return false
        }
        if (phase.bypassesIobSurveillance) return true
        if (phase == MealAbsorptionPhase.INTER_WAVE && belief >= 0.40) return true
        if (phase == MealAbsorptionPhase.LATE_FAT) return false
        return (input.mealIntent || input.mealCobG >= 6.0 || belief >= 0.45) &&
            input.bgMgdl >= 130.0 &&
            (fastRise || moderateRise || phase.isActive)
    }

    private fun buildReason(
        phase: MealAbsorptionPhase,
        belief: Double,
        chrono: Double,
        kinetic: Double,
        trajectory: Double,
        physio: Double,
        memoryActive: Boolean,
    ): String = buildString {
        append(phase.name)
        append(" B=")
        append("%.2f".format(belief))
        append(" π=")
        append("%.2f".format(chrono))
        append(" K=")
        append("%.2f".format(kinetic))
        append(" T=")
        append("%.2f".format(trajectory))
        append(" P=")
        append("%.2f".format(physio))
        if (memoryActive) append(" mem")
    }
}

