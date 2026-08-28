package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
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
        val uamConfidence: Double,
        val mealIntent: Boolean,
        val physiologicalPhase: PhysiologicalPhase,
        val estimatedRa: Double? = null,
        val hoursSinceBolus: Double? = null,
        val lateFatProteinRise: Boolean = false,
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
    )

    private const val FAST_RISE_DELTA = 2.5
    private const val FAST_RISE_SHORT = 2.2
    private const val FAST_RISE_COMBINED = 3.2
    private const val SECOND_WAVE_DELTA = 1.2
    private const val INTER_WAVE_DELTA_MAX = 2.25

    fun evaluate(raw: Input): Output {
        val stabilized = MealAbsorptionPhaseHysteresis.stabilize(classifyRaw(raw))
        MealAbsorptionMemory.update(stabilized, raw.nowMs)
        return stabilized.copy(
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

        var belief = (0.30 * chrono + 0.35 * kinetic + 0.25 * trajectory + 0.10 * physio)
            .coerceIn(0.0, 1.0)
        if (input.mealIntent) belief = min(1.0, belief + 0.25)
        if (input.mealCobG >= 5.0) belief = min(1.0, belief + 0.30)
        if (input.physiologicalPhase.isMealRisk) belief = min(1.0, belief + 0.20)
        input.estimatedRa?.takeIf { it >= 2.0 }?.let { belief = min(1.0, belief + 0.15) }
        if (input.uamConfidence >= 0.45) belief = min(1.0, belief + 0.10)

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
            memoryActive && moderateRise && belief >= 0.40 -> MealAbsorptionPhase.INTER_WAVE
            belief >= 0.55 && moderateRise && chrono >= 0.55 -> MealAbsorptionPhase.FIRST_WAVE
            else -> MealAbsorptionPhase.NONE
        }

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

    internal fun physioScore(input: Input): Double {
        val hrDelta = input.heartRateBpm - input.restingHeartRateBpm
        var score = 0.0
        if (hrDelta in 5..18 && input.deltaMgdlPer5 >= 1.5) score += 0.35
        if (input.stepsLast15m >= 80 && input.deltaMgdlPer5 >= 2.0) score += 0.10
        if (hrDelta > 18 && input.deltaMgdlPer5 >= 4.0) score -= 0.20
        return score.coerceIn(0.0, 1.0)
    }

    internal fun isFastRise(input: Input): Boolean =
        input.deltaMgdlPer5 >= FAST_RISE_DELTA ||
            input.shortAvgDeltaMgdlPer5 >= FAST_RISE_SHORT ||
            input.combinedDeltaMgdlPer5 >= FAST_RISE_COMBINED

    /** Mirrors [app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance.iobFloorU] — kept local to avoid physio→safety coupling. */
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
        if (phase.bypassesIobSurveillance) return true
        if (phase == MealAbsorptionPhase.INTER_WAVE && belief >= 0.40) return true
        if (phase == MealAbsorptionPhase.LATE_FAT) return false
        return (input.mealIntent || input.mealCobG >= 6.0 || input.uamConfidence >= 0.45 || belief >= 0.45) &&
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
        append(aimiFmt2(belief))
        append(" π=")
        append(aimiFmt2(chrono))
        append(" K=")
        append(aimiFmt2(kinetic))
        append(" T=")
        append(aimiFmt2(trajectory))
        append(" P=")
        append(aimiFmt2(physio))
        if (memoryActive) append(" mem")
    }
}
