package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.SC
import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎛️ Unified Activity Provider - MTR Implementation
 * 
 * Orchestrates data retrieval from multiple sources (Wear OS, Health Connect, Phone)
 * based on user preferences and data freshness validation.
 * 
 * Logic:
 * - Queries PersistenceLayer for data within specified window (freshness check)
 * - Filters by source based on preferred Mode (Wear, Auto, HC Only)
 * - Returns the most recent valid data point
 * 
 * @author MTR & Lyra AI - AIMI Activity Orchestrator
 */
@Singleton
class UnifiedActivityProviderMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) : ActivityVitalsProvider {

    companion object {
        private const val TAG = "ActivityProvider"
        
        // Preference Key
        const val PREF_KEY_SOURCE_MODE = "aimi_activity_source_mode"
        
        // Mode Values
        const val MODE_PREFER_WEAR = "prefer_wear"
        const val MODE_AUTO_FALLBACK = "auto"
        const val MODE_HEALTH_CONNECT_ONLY = "hc_only"
        const val MODE_DISABLED = "disabled"
        
        // Default Mode
        const val DEFAULT_MODE = MODE_AUTO_FALLBACK
        
        // Known Source Identifiers
        private const val SOURCE_HC = "HealthConnect"
        private const val SOURCE_PHONE = "PhoneSensor"
    }

    override fun getLatestSteps(windowMs: Long): StepsResult? {
        val mode = getMode()
        if (mode == MODE_DISABLED) return null
        
        val now = System.currentTimeMillis()
        val start = now - windowMs
        
        try {
            // Fetch all records in window
            // Note: getStepsCountFromTimeToTime returns list ordered by ?? Usually time.
            // We'll sort descending to be safe.
            val records = persistenceLayer.getStepsCountFromTimeToTime(start, now)
                .sortedByDescending { it.timestamp }
                
            if (records.isEmpty()) return null
            
            // Separate by source
            val wearRecord = records.firstOrNull { isWearDevice(it.device) }
            val hcRecord = records.firstOrNull { it.device == SOURCE_HC }
            val phoneRecord = records.firstOrNull { it.device == SOURCE_PHONE }
            
            return when (mode) {
                MODE_PREFER_WEAR -> {
                    // Strict Wear OS preference (no fallback to HC/Phone)
                    wearRecord?.let { toStepsResult(it) }
                }
                MODE_HEALTH_CONNECT_ONLY -> {
                    hcRecord?.let { toStepsResult(it) }
                }
                MODE_AUTO_FALLBACK -> {
                    // Priority: Wear > HC > Phone
                    wearRecord?.let { toStepsResult(it) } 
                        ?: hcRecord?.let { toStepsResult(it) }
                        ?: phoneRecord?.let { toStepsResult(it) }
                }
                else -> null
            }
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error fetching steps", e)
            return null
        }
    }

    override fun getLatestHeartRate(windowMs: Long): HrResult? {
        val mode = getMode()
        if (mode == MODE_DISABLED) return null
        
        val now = System.currentTimeMillis()
        val start = now - windowMs
        
        try {
            val records = persistenceLayer.getHeartRatesFromTimeToTime(start, now)
                .sortedByDescending { it.timestamp }
                
            if (records.isEmpty()) return null
            
            val wearRecord = records.firstOrNull { isWearDevice(it.device) }
            val hcRecord = records.firstOrNull { it.device == SOURCE_HC }
            
            return when (mode) {
                MODE_PREFER_WEAR -> wearRecord?.let { toHrResult(it) }
                MODE_HEALTH_CONNECT_ONLY -> hcRecord?.let { toHrResult(it) }
                MODE_AUTO_FALLBACK -> {
                    // Priority: Wear > HC
                    wearRecord?.let { toHrResult(it) } 
                        ?: hcRecord?.let { toHrResult(it) }
                }
                else -> null
            }
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error fetching HR", e)
            return null
        }
    }
    
    // Helpers
    
    private fun getMode(): String {
        return sp.getString(PREF_KEY_SOURCE_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }
    
    private fun isWearDevice(device: String?): Boolean {
        if (device == null) return false
        return device != SOURCE_HC && device != SOURCE_PHONE
    }
    
    private fun toStepsResult(sc: SC): StepsResult {
        // Steps5min is usually the "recent rate". 
        // Or should we return sum of window? The method is getLatestSteps.
        // Returning the latest record's steps5min gives "current activity level".
        return StepsResult(
            steps = sc.steps5min, // Using 5min as standard accumulator
            timestamp = sc.timestamp,
            source = sc.device ?: "Unknown",
            duration = sc.duration
        )
    }
    
    private fun toHrResult(hr: HR): HrResult {
        return HrResult(
            bpm = hr.beatsPerMinute,
            timestamp = hr.timestamp,
            source = hr.device ?: "Unknown"
        )
    }
}
