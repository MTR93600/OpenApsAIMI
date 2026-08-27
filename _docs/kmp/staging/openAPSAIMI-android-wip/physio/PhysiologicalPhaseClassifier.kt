package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import kotlin.math.abs

/**
 * Classifies the dominant physiological phase for behavioral risk (HTR / MPC / scenario).
 */
object PhysiologicalPhaseClassifier {

    /**
     * Rise per 5 min that cortisol alone cannot explain. Measured peak of the genuine cortisol
     * episodes was 9.1 mg/dL per 5 min, so 11.0 keeps a margin above real physiology.
     */
    private const val CORTISOL_ESCAPE_RATE_MGDL_PER_5M = 11.0

    /**
     * Sustained (short average) rise per 5 min for the level clause, used together with the level
     * test below. Genuine cortisol episodes never held such a rise that high above target, while
     * the two mis-read breakfasts peaked at 12.9 and 20.1 mg/dL per 5 min.
     */
    private const val CORTISOL_ESCAPE_SUSTAINED_MGDL_PER_5M = 5.0

    /**
     * Level test for the sustained clause: 1.6 * high band, which is BG 170 for this deployment
     * (target 90, high 140, band 50). No genuine cortisol episode was that high while still rising.
     */
    private const val CORTISOL_ESCAPE_DEV_BAND_MULT = 1.6

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val mealCobG: Double,
        val hourOfDay: Int,
        val stepsLast15m: Int,
        val heartRateBpm: Int,
        val restingHeartRateBpm: Int,
        val bestTerminalMgdl: Double,
        val floorTerminalMgdl: Double,
        val dwellAboveHighBgMinutes: Int,
        val wCycleEnabled: Boolean,
        val wCycleTrackingMode: CycleTrackingMode?,
        val wCyclePhase: CyclePhase?,
        val htrTier: HyperSeverityTier = HyperSeverityTier.OFF,
        val plateauSustain: Boolean = false,
        /** Pre-computed once per tick by the caller; avoids a global-singleton read inside the classifier. */
        val mealAbsorptionMemoryActive: Boolean = false,
    )

    data class Output(
        val phase: PhysiologicalPhase,
        val confidence: Double,
        val policy: BehavioralRiskPolicy,
        /**
         * Set when the tick left the cortisol family because the rise was too steep for it
         * (see [isTooSteepForCortisolAlone]). `EndogenousPhaseHysteresis` must not damp such a
         * change: its hold exists to stop 5-minute flip-flop, not to outvote new strong evidence.
         */
        val breaksEndogenousHold: Boolean = false,
    )

    /**
     * Classifies the tick, then marks the result when a steep rise took it out of the cortisol
     * family. The mark is what lets `EndogenousPhaseHysteresis` drop a hold that would otherwise
     * re-apply the cortisol policy — with its 0.75 U SMB cap — for the first four ticks of a meal,
     * which is exactly when the dose matters.
     */
    fun classify(input: Input): Output {
        val raw = classifyPhase(input)
        if (raw.phase == PhysiologicalPhase.STRESS_CORTISOL) return raw
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            input.targetBgMgdl,
            input.highBgPreferenceMgdl,
        )
        if (!isTooSteepForCortisolAlone(input, highBand, input.bgMgdl - input.targetBgMgdl)) return raw
        return raw.copy(breaksEndogenousHold = true)
    }

    private fun classifyPhase(input: Input): Output {
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            input.targetBgMgdl,
            input.highBgPreferenceMgdl,
        )
        val dev = input.bgMgdl - input.targetBgMgdl
        val projectionLead = input.bestTerminalMgdl - input.bgMgdl
        val tooSteepForCortisolAlone = isTooSteepForCortisolAlone(input, highBand, dev)

        if (input.mealCobG >= 5.0) {
            return out(PhysiologicalPhase.MEAL_DECLARED, 0.95, "COB>=5g")
        }

        val dawnHormonal = classifyDawnHormonalIfNearTarget(input, highBand, dev, projectionLead)
        if (dawnHormonal != null) {
            return dawnHormonal
        }

        val mealDiscriminantBestT = EndogenousCounterRegulatoryDetector.capBestTerminalForMealDiscriminant(
            input,
            highBand,
            dev,
        )
        val mealProjectionLead = mealDiscriminantBestT - input.bgMgdl

        if (EndogenousCounterRegulatoryDetector.isEndogenousRamp(input, highBand, dev, projectionLead)) {
            return out(PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY, 0.87, "endogenous R_HGP ramp COB=0")
        }

        classifyMorningCortisolPrior(
            input = input,
            highBand = highBand,
            dev = dev,
            projectionLead = projectionLead,
            tooSteepForCortisolAlone = tooSteepForCortisolAlone,
        )?.let { return it }

        val mealLikeRise = isMealLikeRise(input, highBand, mealProjectionLead, mealDiscriminantBestT)
        val mealDominantRise = isMealDominantRise(input, highBand, mealProjectionLead, mealDiscriminantBestT)
        if (mealLikeRise) {
            return out(PhysiologicalPhase.MEAL_UNDECLARED, 0.88, "mealLike Δ/gap/proj")
        }
        if (mealDominantRise) {
            return out(PhysiologicalPhase.MEAL_UNDECLARED, 0.84, "mealDominant Δ/proj")
        }

        // Second cortisol site. It runs after the meal checks, so it must carry the same escape,
        // otherwise a tick that no meal branch caught would get the cortisol phase back.
        if (isStressCortisol(input) && !input.mealAbsorptionMemoryActive && !tooSteepForCortisolAlone) {
            return out(PhysiologicalPhase.STRESS_CORTISOL, 0.82, "stress Δ+HR COB=0")
        }

        if (input.plateauSustain ||
            input.htrTier == HyperSeverityTier.ESTABLISHED ||
            input.htrTier == HyperSeverityTier.DEEP
        ) {
            if (dev >= highBand * 1.5 || input.dwellAboveHighBgMinutes >= 45) {
                return out(PhysiologicalPhase.HYPER_INSTALLED, 0.80, "hyperInstalled tier/dwell")
            }
        }

        val hormonalKinetic = isHormonalKinetic(input, highBand, dev, projectionLead)
        if (hormonalKinetic && input.hourOfDay in 4..10) {
            return classifyHormonalMorning(input, "slowRamp dawnWindow")
        }

        return out(PhysiologicalPhase.OFF, 0.5, "noDominantPhase")
    }

    private fun out(phase: PhysiologicalPhase, confidence: Double, reason: String): Output {
        val conf = confidence.coerceIn(0.0, 1.0)
        return Output(
            phase = phase,
            confidence = conf,
            policy = BehavioralRiskPolicy.forPhase(phase, conf, reason),
        )
    }

    /**
     * Dawn window (4h–10h): near-target ramp from night low BG must not become MEAL_UNDECLARED
     * when UAM inflates [bestT] while dev is still small (field package 04:11–04:21).
     */
    private fun classifyDawnHormonalIfNearTarget(
        input: Input,
        highBand: Double,
        dev: Double,
        projectionLead: Double,
    ): Output? {
        if (input.hourOfDay !in 4..10) return null
        if (input.mealCobG >= 1.0) return null
        if (dev >= highBand * 0.35) return null
        if (isAcuteMealSurgeAtDawn(input, highBand, dev, projectionLead)) return null
        if (!isDawnNearTargetRamp(input, highBand, dev)) return null
        return classifyHormonalMorning(input, "dawnNearTarget UAM ramp")
    }

    private fun classifyHormonalMorning(input: Input, reasonSuffix: String): Output {
        if (isFemaleCycleHormonal(input)) {
            return out(
                PhysiologicalPhase.FEMALE_CYCLE_HORMONAL,
                0.86,
                "wCycle ${input.wCyclePhase} $reasonSuffix",
            )
        }
        if (isMaleCircadianProfile(input)) {
            return out(
                PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
                0.85,
                "maleCircadian h=${input.hourOfDay} $reasonSuffix",
            )
        }
        return out(PhysiologicalPhase.DAWN_CORTISOL, 0.84, "dawnWindow $reasonSuffix")
    }

    internal fun isDawnNearTargetRamp(input: Input, highBand: Double, dev: Double): Boolean {
        if (dev >= highBand) return false
        val moderateRise = input.deltaMgdlPer5 >= 1.5 ||
            input.shortAvgDeltaMgdlPer5 >= 1.2 ||
            input.combinedDeltaMgdlPer5 >= 2.0
        return moderateRise || dev < highBand * 0.25
    }

    internal fun isAcuteMealSurgeAtDawn(
        input: Input,
        highBand: Double,
        dev: Double,
        projectionLead: Double,
    ): Boolean {
        if (dev >= highBand * 0.45) return true
        if (input.combinedDeltaMgdlPer5 >= 4.5) return true
        if (input.deltaMgdlPer5 >= 4.0 && dev >= highBand * 0.35) return true
        if (input.deltaMgdlPer5 >= 4.0 &&
            input.bestTerminalMgdl >= input.bgMgdl + highBand * 2.0
        ) {
            return true
        }
        return projectionLead >= highBand * 2.2 &&
            input.deltaMgdlPer5 >= 3.5 &&
            dev >= highBand * 0.35
    }

    /** Meal-like rise without full gap/projection gate — used to block STRESS during lunch ramps. */
    internal fun isMealDominantRise(
        input: Input,
        highBand: Double,
        projectionLead: Double,
        bestTerminalForMeal: Double = input.bestTerminalMgdl,
    ): Boolean {
        if (input.mealCobG >= 1.0) return false
        val fastRise = input.deltaMgdlPer5 >= 2.5 ||
            input.shortAvgDeltaMgdlPer5 >= 2.2 ||
            input.combinedDeltaMgdlPer5 >= 3.2
        if (!fastRise) return false
        return projectionLead >= highBand * 0.85 ||
            bestTerminalForMeal >= input.bgMgdl + highBand * 0.55
    }

    internal fun isMealLikeRise(
        input: Input,
        highBand: Double,
        projectionLead: Double,
        bestTerminalForMeal: Double = input.bestTerminalMgdl,
    ): Boolean {
        if (input.mealCobG >= 1.0) return false
        val fastRise = input.deltaMgdlPer5 >= 2.5 ||
            input.shortAvgDeltaMgdlPer5 >= 2.2 ||
            input.combinedDeltaMgdlPer5 >= 3.2
        val strongProjection = projectionLead >= highBand * 1.1 &&
            bestTerminalForMeal >= input.bgMgdl + highBand * 0.35
        val gap = bestTerminalForMeal - input.floorTerminalMgdl
        val gapMin = HyperSeverityClassifier.gapMinMgdl(55.0)
        val projectionHyper = gap >= gapMin && strongProjection
        return fastRise && (strongProjection || projectionHyper)
    }

    fun classifyWithHysteresis(input: Input): Output =
        EndogenousPhaseHysteresis.stabilize(classify(input))

    internal fun isHormonalKinetic(
        input: Input,
        highBand: Double,
        dev: Double,
        projectionLead: Double,
    ): Boolean {
        if (input.mealCobG >= 1.0) return false
        if (dev >= highBand * 1.25) return false
        if (input.bestTerminalMgdl < input.bgMgdl - 10.0) return false
        if (projectionLead < -5.0) return false
        val slowRamp = input.deltaMgdlPer5 < 4.0 &&
            input.shortAvgDeltaMgdlPer5 < 3.5 &&
            input.combinedDeltaMgdlPer5 < 3.5
        val nearTarget = dev < highBand
        val projectionNotMealLike = projectionLead <= highBand * 1.35 ||
            input.bestTerminalMgdl <= input.bgMgdl + 55.0
        return slowRamp && nearTarget && projectionNotMealLike
    }

    internal fun isStressCortisol(input: Input): Boolean {
        if (input.mealCobG >= 1.0) return false
        val morningCortisolWindow = input.hourOfDay in 5..11
        if (!morningCortisolWindow && input.mealAbsorptionMemoryActive) {
            return false
        }
        val hrElevated = input.heartRateBpm > input.restingHeartRateBpm + 12
        val acute = input.deltaMgdlPer5 >= 4.0 || input.combinedDeltaMgdlPer5 >= 4.5
        return hrElevated && acute
    }

    /**
     * True when the rise is too steep, or too high while still rising, to be cortisol alone.
     * The caller then skips the cortisol classification so the tick can be seen as a meal.
     *
     * Measured on 952 ticks and 14 STRESS_CORTISOL episodes (3 food, 11 genuine cortisol):
     * genuine cortisol never rose faster than 9.1 mg/dL per 5 min and its whole excursion stayed
     * inside 26 mg/dL, while the two mis-read breakfasts (12 Aug 178 -> 290, 13 Aug 103 -> 243)
     * both trip this test on their first rising tick.
     *
     * Declared carbs take the MEAL_DECLARED path, so `mealCobG` >= 1 returns false here.
     *
     * This is on purpose a different test from [isAcuteMealSurgeAtDawn]: that one starts with
     * dev >= 0.45 * band, which is BG >= 112 for this deployment and fires on all 11 genuine
     * cortisol episodes, so it cannot be used as the escape.
     */
    internal fun isTooSteepForCortisolAlone(input: Input, highBand: Double, dev: Double): Boolean {
        if (input.mealCobG >= 1.0) return false
        if (input.deltaMgdlPer5 >= CORTISOL_ESCAPE_RATE_MGDL_PER_5M) return true
        return input.shortAvgDeltaMgdlPer5 >= CORTISOL_ESCAPE_SUSTAINED_MGDL_PER_5M &&
            dev >= highBand * CORTISOL_ESCAPE_DEV_BAND_MULT
    }

    /**
     * Morning chrono prior (COB=0): cortisol / circadian hormonal wins over mealLike when UAM inflates bestT.
     * Window 5h–11h local — individual wake shifts absorbed by HR + rise shape, not only near-target dawn.
     *
     * When [tooSteepForCortisolAlone] is set, **both** cortisol branches are skipped, not only
     * [isStressCortisol]. The `morningChrono rebound` branch is the same cortisol family with a
     * looser HR test (resting + 8 instead of + 12) and it only asks dev < 1.45 * band. On the
     * 13 Aug 08:16 tick dev was 53 and 1.45 * band was 72.5, so that branch would hand back
     * STRESS_CORTISOL and the escape would change nothing. The tail of this function
     * ([classifyDawnHormonalIfNearTarget], [isHormonalKinetic]) stays reachable as before.
     */
    internal fun classifyMorningCortisolPrior(
        input: Input,
        highBand: Double,
        dev: Double,
        projectionLead: Double,
        tooSteepForCortisolAlone: Boolean,
    ): Output? {
        if (input.mealCobG >= 1.0) return null
        if (input.hourOfDay !in 5..10) return null

        if (!tooSteepForCortisolAlone) {
            if (isStressCortisol(input)) {
                return out(PhysiologicalPhase.STRESS_CORTISOL, 0.86, "morningStress Δ+HR COB=0")
            }

            val hrElevated = input.heartRateBpm > input.restingHeartRateBpm + 8
            val moderateRise = input.deltaMgdlPer5 >= 2.5 ||
                input.shortAvgDeltaMgdlPer5 >= 2.0 ||
                input.combinedDeltaMgdlPer5 >= 3.0
            if (hrElevated && moderateRise && dev < highBand * 1.45) {
                return out(PhysiologicalPhase.STRESS_CORTISOL, 0.84, "morningChrono rebound COB=0")
            }
        }

        if (isAcuteMealSurgeAtDawn(input, highBand, dev, projectionLead)) return null

        classifyDawnHormonalIfNearTarget(input, highBand, dev, projectionLead)?.let { return it }

        if (isHormonalKinetic(input, highBand, dev, projectionLead)) {
            return classifyHormonalMorning(input, "morningChrono COB=0")
        }
        return null
    }

    internal fun isFemaleCycleHormonal(input: Input): Boolean {
        if (!input.wCycleEnabled) return false
        val mode = input.wCycleTrackingMode ?: return false
        if (mode == CycleTrackingMode.MENOPAUSE || mode == CycleTrackingMode.NO_MENSES_LARC) {
            return false
        }
        val phase = input.wCyclePhase ?: return false
        return phase == CyclePhase.LUTEAL || phase == CyclePhase.OVULATION
    }

    /** Male / non-cycle-tracking: circadian hormonal morning profile. */
    internal fun isMaleCircadianProfile(input: Input): Boolean {
        if (!input.wCycleEnabled) return true
        val mode = input.wCycleTrackingMode ?: return true
        return mode == CycleTrackingMode.MENOPAUSE
    }

    fun capHtrTier(tier: HyperSeverityTier, maxTier: HyperSeverityTier): HyperSeverityTier {
        if (!tier.isReleaseEligible) return tier
        if (!maxTier.isReleaseEligible) return HyperSeverityTier.OFF
        return if (tier.ordinal > maxTier.ordinal) maxTier else tier
    }

    fun isExtendedDawnGuardActive(policy: BehavioralRiskPolicy?, hour: Int, cob: Double): Boolean {
        if (policy?.extendedDawnGuard != true) return false
        if (cob >= 0.1) return false
        return hour in 4..10
    }
}
