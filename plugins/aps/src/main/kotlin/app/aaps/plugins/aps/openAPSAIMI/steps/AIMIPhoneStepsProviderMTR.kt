package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.StepService
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📱 AIMI Phone Steps Provider - MTR Implementation
 * 
 * Wraps the existing StepService (Android step counter sensor) as a provider
 * in the chain of responsibility.
 * 
 * Priority: 2 (after Wear OS but before Health Connect)
 * 
 * Data source: Android's hardware step counter sensor via StepService
 * - Uses in-memory LinkedHashMap
 * - 5-minute granularity buckets
 * - Limited history (20 buckets = 100 minutes)
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
@Singleton
class AIMIPhoneStepsProviderMTR @Inject constructor(
    private val aapsLogger: AAPSLogger
) : AIMIStepsProviderMTR {
    
    companion object {
        private const val SOURCE_NAME = "PhoneSensor"
        private const val PRIORITY = 2 // Higher priority than Health Connect
    }
    
    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int {
        return try {
            val steps = when (windowMinutes) {
                5 -> StepService.getRecentStepCount5Min()
                10 -> StepService.getRecentStepCount10Min()
                15 -> StepService.getRecentStepCount15Min()
                30 -> StepService.getRecentStepCount30Min()
                60 -> StepService.getRecentStepCount60Min()
                180 -> StepService.getRecentStepCount180Min()
                else -> {
                    aapsLogger.warn(LTag.APS, "[$SOURCE_NAME] Unsupported window: {$windowMinutes}min")
                    0
                }
            }
            
            if (steps > 0) {
                aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Retrieved $steps steps for {$windowMinutes}min")
            }
            
            steps
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error fetching steps for {$windowMinutes}min", e)
            0
        }
    }
    
    override fun getLastUpdateMillis(): Long {
        // StepService doesn't track update time, use current time if data exists
        return try {
            val has5MinData = StepService.getRecentStepCount5Min() > 0
            if (has5MinData) System.currentTimeMillis() else 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    override fun isAvailable(): Boolean {
        // StepService is always available (it's a singleton object)
        // but data might be 0 if sensor not active
        return try {
            // Check if we have any recent data (last 15 minutes)
            val recentSteps = StepService.getRecentStepCount5Min() +
                    StepService.getRecentStepCount10Min() +
                    StepService.getRecentStepCount15Min()
            
            recentSteps >= 0 // Always true, but validates StepService is accessible
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Availability check failed", e)
            false
        }
    }
    
    override fun sourceName(): String = SOURCE_NAME
    
    override fun priority(): Int = PRIORITY
}
