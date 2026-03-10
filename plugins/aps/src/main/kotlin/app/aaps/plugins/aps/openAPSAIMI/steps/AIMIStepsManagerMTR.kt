package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎛️ AIMI Steps Manager - MTR Central Controller
 * 
 * Manages all steps data sources (Garmin, Wear OS, Health Connect, Phone Sensor).
 * Starts/stops sync services and provides unified status.
 * 
 * Sources Priority (all write to DB):
 * 1. Garmin (if available) - Highest priority
 * 2. Wear OS (if available)
 * 3. Phone Sensor (always available) - Persistent backup
 * 4. Health Connect (Android 14+) - Fallback if others missing
 * 
 * All consumers (DetermineBasalAIMI2, Dashboard) read from DB.
 * 
 * @author MTR & Lyra AI - AIMI Steps Integration
 */
@Singleton
class AIMIStepsManagerMTR @Inject constructor(
    private val healthConnectSync: AIMIHealthConnectSyncServiceMTR,
    private val phoneStepsSync: AIMIPhoneStepsSyncServiceMTR,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "StepsManager"
    }
    
    private var isStarted = false
    
    /**
     * Starts all steps sync services
     */
    fun start() {
        if (isStarted) {
            aapsLogger.debug(LTag.APS, "[$TAG] Already started")
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🚀 Starting AIMI Steps Manager")
        
        try {
            // Start phone sensor sync (always available)
            phoneStepsSync.start()
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Phone sensor sync started")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Phone sensor sync failed to start", e)
        }
        
        try {
            // Start Health Connect sync (Android 14+ only)
            healthConnectSync.start()
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Health Connect sync started")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect sync failed to start", e)
        }
        
        isStarted = true
        
        aapsLogger.info(LTag.APS, "[$TAG] 📊 Steps Manager Status:")
        aapsLogger.info(LTag.APS, "[$TAG]   - Garmin: Via GarminPlugin (HTTP/CIQ)")
        aapsLogger.info(LTag.APS, "[$TAG]   - Wear OS: Via DataHandlerMobile")
        aapsLogger.info(LTag.APS, "[$TAG]   - Phone: Active (syncing every 5 min)")
        aapsLogger.info(LTag.APS, "[$TAG]   - Health Connect: ${healthConnectSync.getSyncStatus()}")
    }
    
    /**
     * Stops all steps sync services
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🛑 Stopping AIMI Steps Manager")
        
        phoneStepsSync.stop()
        healthConnectSync.stop()
        
        isStarted = false
    }
    
    /**
     * Gets comprehensive status of all sources
     */
    fun getSourcesStatus(): Map<String, String> {
        return mapOf(
            "Garmin" to "Via GarminPlugin (passive)",
            "WearOS" to "Via DataHandlerMobile (passive)",
            "PhoneSensor" to if (isStarted) "Active (syncing)" else "Stopped",
            "HealthConnect" to healthConnectSync.getSyncStatus()
        )
    }
    
    /**
     * Triggers manual sync for all active sources
     */
    fun triggerManualSync() {
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Manual sync triggered for all sources")
        healthConnectSync.triggerManualSync()
        // Phone sensor syncs automatically via timer
    }
    
    /**
     * Logs status for debugging
     */
    fun logStatus() {
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.APS, "[$TAG] AIMI Steps Sources Status:")
        getSourcesStatus().forEach { (source, status) ->
            aapsLogger.info(LTag.APS, "[$TAG]   $source: $status")
        }
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
    }
}
