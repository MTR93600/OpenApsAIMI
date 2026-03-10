package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🗄️ AIMI Database Steps Provider - MTR Implementation
 * 
 * Wraps the existing PersistenceLayer steps data (from Garmin, Wear OS, etc.)
 * as a provider in the chain of responsibility.
 * 
 * Priority: 99 (lowest - used as last resort when live sources unavailable)
 * 
 * Data source: StepsCount table populated by:
 * - GarminPlugin
 * - DataHandlerMobile (Wear OS)
 * - Manual entries
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
@Singleton
class AIMIDatabaseStepsProviderMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger
) : AIMIStepsProviderMTR {
    
    companion object {
        private const val SOURCE_NAME = "Database"
        private const val PRIORITY = 99 // Lowest priority (last resort)
        private const val SEARCH_WINDOW_BUFFER_MS = 30 * 60 * 1000L // 30 min buffer for delays
    }
    
    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int {
        val nowMs = now.toEpochMilli()
        val windowStartMs = nowMs - (windowMinutes * 60 * 1000L)
        val searchStartMs = windowStartMs - SEARCH_WINDOW_BUFFER_MS // Add buffer for delayed data
        
        return try {
            val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(searchStartMs, nowMs)
            
            if (allStepsCounts.isEmpty()) {
                aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] No steps data in DB for {$windowMinutes}min window")
                return 0
            }
            
            // Find most recent record within the window
            val validRecords = allStepsCounts.filter { it.timestamp >= windowStartMs }
            val steps = when (windowMinutes) {
                5 -> validRecords.maxByOrNull { it.timestamp }?.steps5min ?: 0
                10 -> validRecords.maxByOrNull { it.timestamp }?.steps10min ?: 0
                15 -> validRecords.maxByOrNull { it.timestamp }?.steps15min ?: 0
                30 -> validRecords.maxByOrNull { it.timestamp }?.steps30min ?: 0
                60 -> validRecords.maxByOrNull { it.timestamp }?.steps60min ?: 0
                180 -> validRecords.maxByOrNull { it.timestamp }?.steps180min ?: 0
                else -> {
                    aapsLogger.warn(LTag.APS, "[$SOURCE_NAME] Unsupported window: {$windowMinutes}min")
                    0
                }
            }
            
            val stepsInt = steps?.toInt() ?: 0
            
            if (stepsInt > 0) {
                aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Found $stepsInt steps for {$windowMinutes}min (${validRecords.size} valid records)")
            }
            
            stepsInt
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error fetching steps from DB for {$windowMinutes}min", e)
            0
        }
    }
    
    override fun getLastUpdateMillis(): Long {
        return try {
            val now = System.currentTimeMillis()
            val searchStart = now - 210 * 60 * 1000L // Last 3.5 hours
            val allSteps = persistenceLayer.getStepsCountFromTimeToTime(searchStart, now)
            
            allSteps.maxOfOrNull { it.timestamp } ?: 0L
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error getting last update time", e)
            0L
        }
    }
    
    override fun isAvailable(): Boolean {
        // Database is always available (table exists even if empty)
        return try {
            persistenceLayer != null
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Database availability check failed", e)
            false
        }
    }
    
    override fun sourceName(): String = SOURCE_NAME
    
    override fun priority(): Int = PRIORITY
}
