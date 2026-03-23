package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
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
        hasRecentMealEstimate: Boolean = false
    ): GatingResult {
        // 1. Fetch real-time health data
        val health = healthRepo.fetchSnapshot()
        
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
        val implicitMealContext =
            explicitMealMode ||
                hasRecentMealEstimate ||
                cob > 8.0 ||
                (cob > 3.0 && uamConfidence >= 0.35)

        // 4. Refined glycemic entry thresholds
        val isHighPlateau = bg > 150.0
        val isActivelyRising = combinedDelta > 0.8
        val isMealRising = implicitMealContext && combinedDelta > 0.25

        val shouldEngage = isHighPlateau || isActivelyRising || isMealRising

        if (!shouldEngage) {
            val reason =
                "BG stable (<150) and rise too weak " +
                    "(BG=$bg, Trend=$combinedDelta, COB=$cob, UAM=$uamConfidence, mealCtx=$implicitMealContext)"
            return GatingResult(engage = false, reason = "🧘 $reason")
        }

        val engageReason = when {
            isHighPlateau -> "High plateau"
            isMealRising -> "Meal-aware rise"
            else -> "Strong rise"
        }
        return GatingResult(
            engage = true,
            reason = "🚀 V3 ENGAGED [$engageReason] (BG=$bg, Trend=$combinedDelta, COB=$cob, UAM=$uamConfidence)"
        )
    }

    data class GatingResult(val engage: Boolean, val reason: String)
}
