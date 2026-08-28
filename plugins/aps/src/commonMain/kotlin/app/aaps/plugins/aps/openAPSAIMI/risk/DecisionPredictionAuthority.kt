package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonalScenarioTerminalCap
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max

enum class DecisionPredictionSource {
    PKPD_ONLY,
    SCENARIO_CONSENSUS,
    SCENARIO_GUARDED_UPLIFT,
    SCENARIO_MEAL_UPLIFT,
    SCENARIO_TRAJECTORY_UPLIFT,
    SCENARIO_SUPPRESSED_NON_MEAL,
}

data class DecisionPredictionAuthority(
    val predTerminalMgdl: Double,
    val eventualTerminalMgdl: Double,
    val pkpdEventualMgdl: Double,
    val scenarioFloorTerminalMgdl: Double?,
    val scenarioBestTerminalMgdl: Double?,
    val source: DecisionPredictionSource,
    val scenarioUpliftApplied: Boolean,
    val falseMealSuppression: Boolean,
    val reason: String,
)

object DecisionPredictionAuthorityResolver {

    // --- Meal-confirmed early release (MCER) tuning. See BooleanKey.OApsAIMIMealConfirmedEarlyRelease. ---
    /** Minimum 5-min delta (mg/dL) to treat the meal as still rising. */
    private const val MCER_RISE_DELTA_MIN_MGDL = 1.2
    /** BG must be at least target + this margin (mg/dL) before releasing the floor. */
    private const val MCER_BG_MARGIN_MGDL = 20.0
    /** Meal-compatible / causal-meal confidence required when tree/priority evidence is absent. */
    private const val MCER_STRONG_MEAL_PROB = 0.80
    /** Tail circuit-breaker: revert to the insulin-only floor once IOB headroom (maxIOB − IOB) drops here. */
    private const val MCER_IOB_HEADROOM_MIN_U = 1.5
    /** Tail circuit-breaker: revert as soon as the rise breaks (delta below this). */
    private const val MCER_TAIL_FALL_DELTA_MGDL = 0.0

    fun resolve(
        bgMgdl: Double,
        pkpdEventualMgdl: Double,
        scenarioProjection: ScenarioProjectionPair?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        latentState: PhysioLatentState?,
        causalStatePosterior: CausalStatePosterior?,
        trajectoryAnalysis: TrajectoryAnalysis?,
        physioPolicy: BehavioralRiskPolicy?,
        uamConfidence: Double,
        postHypoDelivery: PostHypoDeliveryAuthority.Decision = PostHypoDeliveryAuthority.INACTIVE,
        mealCertainty: MealCertainty? = null,
        trunkGlobalState: GlobalPhysiologicalState? = null,
        mealConfirmedEarlyReleaseEnabled: Boolean = false,
        combinedDeltaMgdl5m: Double = 0.0,
        targetBgMgdl: Double = 100.0,
        iobU: Double = 0.0,
        maxIobU: Double = 0.0,
    ): DecisionPredictionAuthority {
        val pkpd = pkpdEventualMgdl.takeIf { it.isFinite() } ?: bgMgdl
        val rawScenarioFloor = scenarioProjection?.clinicalFloor?.terminalMgdl?.takeIf { it.isFinite() }
        val rawScenarioBest = scenarioProjection?.scenarioBest?.terminalMgdl?.takeIf { it.isFinite() }
        val scenarioBest = rawScenarioBest?.let {
            HormonalScenarioTerminalCap.capBestTerminalMgdl(
                bgMgdl = bgMgdl,
                bestTerminalMgdl = it,
                policy = physioPolicy,
            )
        }
        var predTerminal = rawScenarioFloor ?: pkpd
        val causalMealConfidence = causalStatePosterior?.mealConfidence ?: 0.0
        val causalProtectiveConfidence = causalStatePosterior?.protectiveConfidence ?: 0.0
        val causalDominant = causalStatePosterior?.dominant ?: CausalStateId.UNKNOWN
        val posteriorSuppressMeal =
            causalDominant in setOf(
                CausalStateId.DAWN_ENDOGENOUS,
                CausalStateId.POST_HYPO_RECOVERY,
                CausalStateId.STRESS_RESISTANCE,
                CausalStateId.INFLAMMATORY_DRIFT,
                CausalStateId.ABSORPTION_UNCERTAIN,
            ) &&
                causalProtectiveConfidence >= causalMealConfidence + 0.08 &&
                (causalStatePosterior?.dominantConfidence ?: 0.0) >= 0.60
        val falseMealSuppression =
            postHypoDelivery.forceMealInterpretationSuppressed ||
                hypothesisState?.suppressMealInterpretation == true ||
                latentState?.falseMealSuppression == true ||
                physioPolicy?.suppressMealLikeScenario == true ||
                posteriorSuppressMeal

        if (scenarioBest == null || rawScenarioFloor == null) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.PKPD_ONLY,
                scenarioUpliftApplied = false,
                falseMealSuppression = falseMealSuppression,
                reason = "no_scenario_projection",
            )
        }

        if (postHypoDelivery.active && postHypoDelivery.forceMealInterpretationSuppressed) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL,
                scenarioUpliftApplied = false,
                falseMealSuppression = true,
                reason = "post_hypo_delivery_guard ${postHypoDelivery.reasonTag}",
            )
        }

        val scenarioLead = scenarioBest - pkpd
        val mealPhaseActive = mealAbsorptionOutput?.phase?.isActive == true
        val mealDeliveryPriority = mealAbsorptionOutput?.mealDeliveryPriority == true
        val mealCompatibleProb = max(hypothesisState?.mealCompatibleProb() ?: 0.0, causalMealConfidence)
        val competingNonMealProb = max(hypothesisState?.competingNonMealProb() ?: 0.0, causalProtectiveConfidence)
        val trajectoryType = trajectoryAnalysis?.classification
        val trajectorySupportsUplift =
            trajectoryType == TrajectoryType.OPEN_DIVERGING ||
                trajectoryType == TrajectoryType.SLOW_DRIFT
        val strongRiseProjection = scenarioBest > bgMgdl + if (causalDominant == CausalStateId.FAST_MEAL) 12.0 else 15.0
        val treeMealEvidence =
            trunkGlobalState == GlobalPhysiologicalState.DIGESTION_ACTIVE ||
                trunkGlobalState == GlobalPhysiologicalState.MEAL_PROBABLE ||
                mealCertainty?.supportsMealSupport == true
        val mealEvidence =
            treeMealEvidence ||
                mealPhaseActive ||
                mealDeliveryPriority ||
                mealCompatibleProb >= 0.55 ||
                causalMealConfidence >= 0.55 ||
                uamConfidence >= 0.45

        // --- Meal-Confirmed Early Release (MCER) -------------------------------------------------
        // The dose-governing floor (predTerminal) is the PKPD insulin-only path — carb-blind. On an
        // undeclared meal it forecasts a phantom descent and throttles the SMB cap (tube minPred),
        // holds RBT authority at SOFT and lets the PKPD safety zero the SMB, so a confirmed meal runs
        // uncorrected. When enabled, once the meal is corroborated AND rising AND above target, release
        // the floor toward the best/UAM path so the loop can reach the configured maxima early. It only
        // ever RAISES the floor (never lowers it) and stays bounded downstream by max SMB/basal/IOB.
        // Tail circuit-breaker: revert to the insulin-only floor on peak/late phase, IOB headroom
        // consumed, or the rise breaking — so it cannot set up a post-peak hypo. Genuine hypo is
        // sovereign: never engages under false-meal suppression or the post-hypo delivery guard.
        var mcerSuffix = ""
        if (mealConfirmedEarlyReleaseEnabled) {
            val scenarioBestPathMin = scenarioProjection?.scenarioBest?.pathMinMgdl ?: scenarioBest
            val rising = combinedDeltaMgdl5m >= MCER_RISE_DELTA_MIN_MGDL
            val aboveTarget = bgMgdl >= targetBgMgdl + MCER_BG_MARGIN_MGDL
            val strongMealConfirmed =
                treeMealEvidence ||
                    mealDeliveryPriority ||
                    mealAbsorptionOutput?.phase?.forcesHtrRise == true ||
                    mealCompatibleProb >= MCER_STRONG_MEAL_PROB ||
                    causalMealConfidence >= MCER_STRONG_MEAL_PROB
            val iobHeadroomU = maxIobU - iobU
            // Tail = post-peak, where insulin (not more SMB) brings BG down. PEAK_CORRECTION only —
            // NOT LATE_FAT, which is a slow late fat/protein RISE that still needs insulin.
            val tailByPhase = mealAbsorptionOutput?.phase == MealAbsorptionPhase.PEAK_CORRECTION
            val tailByIob = maxIobU > 0.0 && iobHeadroomU <= MCER_IOB_HEADROOM_MIN_U
            val tailByFall = combinedDeltaMgdl5m < MCER_TAIL_FALL_DELTA_MGDL
            val tailBreaker = tailByPhase || tailByIob || tailByFall
            val sovereignHypoBlock = falseMealSuppression || postHypoDelivery.active
            val armed = rising && aboveTarget && strongMealConfirmed && !tailBreaker && !sovereignHypoBlock
            if (armed && scenarioBestPathMin.isFinite() && scenarioBestPathMin > predTerminal) {
                predTerminal = scenarioBestPathMin
                mcerSuffix = " | MCER=ARMED release->${scenarioBestPathMin.toInt()}"
            } else {
                val offTag = when {
                    tailBreaker        -> if (tailByPhase) "tail_phase" else if (tailByIob) "tail_iob" else "tail_fall"
                    sovereignHypoBlock -> "hypo_sovereign"
                    !strongMealConfirmed -> "not_confirmed"
                    !rising            -> "not_rising"
                    !aboveTarget       -> "near_target"
                    else               -> "noop"
                }
                mcerSuffix = " | MCER=OFF($offTag)"
            }
        }

        if (scenarioLead <= 5.0) {
            val consensus = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = consensus,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_CONSENSUS,
                scenarioUpliftApplied = false,
                falseMealSuppression = falseMealSuppression,
                reason = "scenario_consensus lead=${aimiFmt1(scenarioLead)}" + mcerSuffix,
            )
        }

        if (falseMealSuppression && competingNonMealProb >= 0.60) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL,
                scenarioUpliftApplied = false,
                falseMealSuppression = true,
                reason = "non_meal_guard prob=${aimiFmt2(competingNonMealProb)} cause=${causalDominant.name}" + mcerSuffix,
            )
        }

        if (strongRiseProjection && mealEvidence && scenarioLead >= if (causalDominant == CausalStateId.FAST_MEAL) 12.0 else 15.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "meal_evidence phase=${mealAbsorptionOutput?.phase?.name ?: "NONE"} " +
                    "mealCert=${mealCertainty?.level?.name ?: "NONE"} " +
                    "trunk=${trunkGlobalState?.name ?: "NONE"} " +
                    "lead=${aimiFmt1(scenarioLead)} cause=${causalDominant.name}" + mcerSuffix,
            )
        }

        if (!falseMealSuppression && strongRiseProjection && trajectorySupportsUplift && scenarioLead >= 20.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_TRAJECTORY_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "trajectory=${trajectoryType.name} lead=${aimiFmt1(scenarioLead)}" + mcerSuffix,
            )
        }

        if (!falseMealSuppression && strongRiseProjection && (mealEvidence || trajectorySupportsUplift) && scenarioLead >= 10.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_GUARDED_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "guarded_uplift lead=${aimiFmt1(scenarioLead)} meal=${mealEvidence} traj=${trajectoryType?.name ?: "NONE"}" + mcerSuffix,
            )
        }

        return DecisionPredictionAuthority(
            predTerminalMgdl = predTerminal,
            eventualTerminalMgdl = pkpd,
            pkpdEventualMgdl = pkpd,
            scenarioFloorTerminalMgdl = rawScenarioFloor,
            scenarioBestTerminalMgdl = scenarioBest,
            source = DecisionPredictionSource.PKPD_ONLY,
            scenarioUpliftApplied = false,
            falseMealSuppression = falseMealSuppression,
            reason = "pkpd_retained lead=${aimiFmt1(scenarioLead)}" + mcerSuffix,
        )
    }

    fun formatLogLine(authority: DecisionPredictionAuthority): String =
        "PRED_AUTHORITY: src=${authority.source.name} predT=${authority.predTerminalMgdl.toInt()} " +
            "evT=${authority.eventualTerminalMgdl.toInt()} pkpd=${authority.pkpdEventualMgdl.toInt()} " +
            "best=${authority.scenarioBestTerminalMgdl?.toInt() ?: "-"} " +
            "mealSupp=${authority.falseMealSuppression} uplift=${authority.scenarioUpliftApplied} " +
            authority.reason
}
