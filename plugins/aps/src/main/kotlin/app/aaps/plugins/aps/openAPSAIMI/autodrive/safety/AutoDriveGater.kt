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

    fun shouldEngageV3(bg: Double, combinedDelta: Double): GatingResult {
        // 1. Fetch real-time health data
        val health = healthRepo.fetchSnapshot()
        
        // 2. Physiological Blockers (Exercise/Stress)
        // High Intensity HR Check (> 140 is a hard block regardless of BG)
        val hardHRBlock = health.hrNow >= 140
        
        // 🏃 REFINED STEP BLOCK (User Request):
        // Only block if BG is already "fragile" (< 160) and we are moving fast (> 1000 steps in 15 min)
        val activityBlock = bg < 160.0 && health.stepsLast15m > 1000
        
        if (hardHRBlock || activityBlock) {
            val reason = if (hardHRBlock) "❤️ HR High (${health.hrNow})" else "🏃 Activity (BG=$bg, Steps15m=${health.stepsLast15m})"
            return GatingResult(
                engage = false,
                reason = "🏃 Activity Prohibited: $reason"
            )
        }

        // 3. Glycemic Thresholds (Strict Full Closed Loop)
        // Autodrive V3 is designed to "catch" rises. Use combinedDelta (filtered) for trend.
        val bgRising = combinedDelta > 0.1
        
        // 🚀 ANTI-LAG: Lower threshold if rising fast (CombinedDelta > 2.0)
        val activationThreshold = if (combinedDelta > 2.0) 90.0 else 120.0
        val highBg = bg > activationThreshold

        // Logic: Engage if High BG + Rising (Filtered Trend)
        val shouldEngage = highBg && bgRising

        if (!shouldEngage) {
            val reasonSource = if (combinedDelta > 2.0) "Aggressive Rise" else "Standard"
            val reason = if (bg <= activationThreshold) "BG Safe ($bg <= $activationThreshold for $reasonSource)" else "BG Stable/Dropping (CombinedDelta=$combinedDelta)"
            return GatingResult(engage = false, reason = "🧘 $reason")
        }

        return GatingResult(
            engage = true,
            reason = "🚀 V3 ENGAGED (BG=$bg, Trend=$combinedDelta)"
        )
    }

    data class GatingResult(val engage: Boolean, val reason: String)
}
