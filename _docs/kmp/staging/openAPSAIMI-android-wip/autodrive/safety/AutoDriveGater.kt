package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

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
@SingleIn(AppScope::class)
class AutoDriveGater @Inject constructor(
    private val healthRepo: AimiHealthContext,
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
                reason = "🏃 Activity Prohibited: $reason"
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
            return GatingResult(engage = false, reason = "🧘 $reason")
        }

        val engageReason = when {
            isHighPlateau -> "High plateau"
            isMealRising -> "Meal-aware rise"
            else -> "Strong rise"
        }
        aapsLogger.debug(
            LTag.APS,
            "${CorrectionAggressionGate.LOG_PREFIX}_V3_GATER: engage $engageReason BG=$bg combD=$combinedDelta minBg75=$minBgLookback75m"
        )
        return GatingResult(
            engage = true,
            reason = "🚀 V3 ENGAGED [$engageReason] (BG=$bg, Trend=$combinedDelta, COB=$cob, UAM=$uamConfidence)"
        )
    }

    data class GatingResult(val engage: Boolean, val reason: String)
}
