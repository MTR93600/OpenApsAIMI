package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🚦 AutoDrive Gater - Smart Activation Filter
 * 
 * Determines if Autodrive V3 should take control based on:
 * 1. Glycemic thresholds (BG > 120 and rising)
 * 2. Physiological safety (Heart Rate & Steps)
 * 3. Carb context (COB > 0)
 * 
 * If conditions are not met, the system falls back to AIMI Classic (V2).
 */
@Singleton
class AutoDriveGater @Inject constructor(
    private val healthRepo: HealthContextRepository,
    private val aapsLogger: AAPSLogger
) {

    fun shouldEngageV3(
        bg: Double,
        combinedDelta: Double,
        cob: Double = 0.0,
        uamConfidence: Double = 0.0,
        explicitMealMode: Boolean = false,
        hasRecentMealEstimate: Boolean = false,
        minBgLookback75m: Double = 200.0,
        estimatedRa: Double = 0.0,
        mealChannelHint: MealChannelHint? = null,
    ): GatingResult {
        // 1. Fetch real-time health data
        val health = healthRepo.fetchSnapshotForAutodriveGater()
        
        // 2. Physiological Blockers (Exercise/Stress)
        // High Intensity HR Check (> 140 is a hard block regardless of BG)
        val hardHRBlock = health.hrNow >= 140
        
        // 🏃 REFINED STEP BLOCK (User Request):
        // Only block if BG is already "fragile" (< 160) and we are moving fast (> 1000 steps in 15 min)
        // EXCEPT if movement stopped (0 steps in 5 min) -> Immediate release to handle rise
        val activityBlock = bg < 160.0 && health.stepsLast15m > 1000 && health.stepsLast5m > 0
        
        if (hardHRBlock || activityBlock) {
            val reason = if (hardHRBlock) "❤️ HR High (${health.hrNow})" else "🏃 Activity (BG=$bg, Steps15m=${health.stepsLast15m}, Steps5m=${health.stepsLast5m})"
            return GatingResult(
                engage = false,
                reason = "🏃 Activity Prohibited: $reason",
                kind = if (hardHRBlock) GateKind.HR_HIGH else GateKind.ACTIVITY,
            )
        }

        // 3. Meal-awareness: explicit mode OR implicit meal-like context
        val rbtMealPriority = mealChannelHint == MealChannelHint.PRIORITY
        val rbtMealSuppress = mealChannelHint == MealChannelHint.SUPPRESS
        val implicitMealContext =
            !rbtMealSuppress &&
                (
                    rbtMealPriority ||
                        explicitMealMode ||
                        hasRecentMealEstimate ||
                        cob > 8.0 ||
                        (cob > 3.0 && uamConfidence >= 0.35)
                    )

        // 4. Refined glycemic entry thresholds
        val isHighPlateau = bg > 150.0
        val isActivelyRising = when {
            bg >= 150.0 -> combinedDelta > 0.8
            bg >= 120.0 -> combinedDelta > 1.2 ||
                (combinedDelta > 0.8 && (uamConfidence >= 0.5 || estimatedRa >= 0.7))
            else -> combinedDelta > 2.0 && minBgLookback75m >= CorrectionAggressionGate.REBOUND_MIN_BG_LOOKBACK_MGDL
        }
        val isMealRising = implicitMealContext && combinedDelta > 0.25

        val shouldEngage = isHighPlateau || isActivelyRising || isMealRising

        if (!shouldEngage) {
            val reason =
                "BG stable (<150) and rise too weak " +
                    "(BG=$bg, Trend=$combinedDelta, COB=$cob, UAM=$uamConfidence, mealCtx=$implicitMealContext, minBg75=$minBgLookback75m)"
            aapsLogger.debug(LTag.APS, "${CorrectionAggressionGate.LOG_PREFIX}_V3_GATER: disengage $reason")
            return GatingResult(engage = false, reason = "🧘 $reason", kind = GateKind.RISE_TOO_WEAK)
        }

        // The meal reason is read first on purpose. `shouldEngage` above is a plain OR, so this
        // order cannot change whether the gate opens; it only changes the name written on the tick.
        // With the plateau read first, every tick above BG 150 was called HIGH_PLATEAU even when a
        // meal was what opened the gate, and MEAL_AWARE_RISE could only ever be seen below 150. A
        // meal is the more specific fact, so it wins the label.
        val engageKind = when {
            isMealRising  -> GateKind.MEAL_AWARE_RISE
            isHighPlateau -> GateKind.HIGH_PLATEAU
            else          -> GateKind.STRONG_RISE
        }
        val engageReason = when (engageKind) {
            GateKind.HIGH_PLATEAU    -> "High plateau"
            GateKind.MEAL_AWARE_RISE -> "Meal-aware rise"
            else                     -> "Strong rise"
        }
        aapsLogger.debug(
            LTag.APS,
            "${CorrectionAggressionGate.LOG_PREFIX}_V3_GATER: engage $engageReason BG=$bg combD=$combinedDelta minBg75=$minBgLookback75m"
        )
        return GatingResult(
            engage = true,
            reason = "🚀 V3 ENGAGED [$engageReason] (BG=$bg, Trend=$combinedDelta, COB=$cob, UAM=$uamConfidence)",
            kind = engageKind,
        )
    }

    /**
     * Why the gate opened or stayed shut.
     *
     * [reason] carries the live numbers and is meant to be read by a person; every string is unique,
     * so it cannot be grouped. [kind] is the stable token to count on: measured on 2026-09-07, the
     * gate stayed shut on 884 ticks out of 1351 and nothing exported said why, so two thirds of the
     * day could not be explained at all.
     */
    data class GatingResult(
        val engage: Boolean,
        val reason: String,
        val kind: GateKind = GateKind.RISE_TOO_WEAK,
    )

    /** The stable reasons `shouldEngageV3` can return, for counting rather than reading. */
    enum class GateKind {

        /** Heart rate at or above 140: a hard block whatever the glucose does. */
        HR_HIGH,

        /** Moving fast while glucose is still fragile (under 160). */
        ACTIVITY,

        /** Glucose under 150 and the rise below the entry thresholds. The common case. */
        RISE_TOO_WEAK,

        /**
         * Glucose above 150 and no meal context on the tick.
         *
         * This is the weaker of the two open labels: when a meal context is also present,
         * [MEAL_AWARE_RISE] is written instead, because a meal says more about why the gate opened
         * than the glucose level alone.
         */
        HIGH_PLATEAU,

        /**
         * A meal context plus any rise worth the name.
         *
         * This label wins over [HIGH_PLATEAU] when both are true. Before that, the plateau was read
         * first, so above BG 150 a meal was never named and this label could only appear below 150.
         * It never appeared at all on the measured day.
         */
        MEAL_AWARE_RISE,

        /** A rise strong enough on its own. */
        STRONG_RISE,
    }
}
