package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSAIMI.StepService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📱 AIMI Phone Steps Sync Service - MTR Implementation
 * 
 * Periodically syncs step count data from phone sensor (StepService) to AAPS database.
 * This complements the existing in-memory StepService by persisting data to DB.
 * 
 * Architecture:
 * - Runs every 5 minutes
 * - Reads steps from StepService (in-memory)
 * - Writes to StepsCount table via PersistenceLayer
 * - Allows DetermineBasalAIMI2 & Dashboard to use unified DB source
 * 
 * @author MTR & Lyra AI - AIMI Steps Integration
 */
@Singleton
class AIMIPhoneStepsSyncServiceMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhoneStepsSync"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SOURCE_DEVICE = "PhoneSensor"
        private const val PREF_KEY_ENABLED = "aimi_phone_steps_sync_enable"
    }
    
    private val disposable = CompositeDisposable()
    private var syncTimer: Timer? = null
    
    /**
     * Starts periodic synchronization
     */
    fun start() {
        if (!isEnabled()) {
            aapsLogger.debug(LTag.APS, "[$TAG] Phone steps sync disabled")
            return
        }
        
        if (syncTimer != null) {
            aapsLogger.debug(LTag.APS, "[$TAG] Already running")
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] Starting phone steps sync (every 5 min)")
        
        syncTimer = Timer("PhoneStepsSync", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        syncStepsToDatabase()
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "[$TAG] Sync error", e)
                    }
                }
            }, 30_000L, SYNC_INTERVAL_MS) // First sync after 30s
        }
    }
    
    /**
     * Stops synchronization
     */
    fun stop() {
        syncTimer?.cancel()
        syncTimer = null
        disposable.clear()
        aapsLogger.info(LTag.APS, "[$TAG] Stopped")
    }
    
    /**
     * Syncs StepService data → Database
     */
    private fun syncStepsToDatabase() {
        try {
            val now = System.currentTimeMillis()
            
            // Read all windows from StepService
            val steps5 = StepService.getRecentStepCount5Min()
            val steps10 = StepService.getRecentStepCount10Min()
            val steps15 = StepService.getRecentStepCount15Min()
            val steps30 = StepService.getRecentStepCount30Min()
            val steps60 = StepService.getRecentStepCount60Min()
            val steps180 = StepService.getRecentStepCount180Min()
            
            // Only write if we have data
            if (steps5 > 0) {
                val sc = SC(
                    timestamp = now,
                    duration = SYNC_INTERVAL_MS,
                    steps5min = steps5,
                    steps10min = steps10,
                    steps15min = steps15,
                    steps30min = steps30,
                    steps60min = steps60,
                    steps180min = steps180,
                    device = SOURCE_DEVICE
                )
                
                disposable.add(
                    persistenceLayer.insertOrUpdateStepsCount(sc)
                        .subscribeOn(Schedulers.io())
                        .subscribe(
                            { _ ->
                                aapsLogger.debug(
                                    LTag.APS,
                                    "[$TAG] ✅ Synced: $steps5 steps (5min), 15min=$steps15, 30min=$steps30"
                                )
                            },
                            { error ->
                                aapsLogger.error(LTag.APS, "[$TAG] ❌ DB insert failed", error)
                            }
                        )
                )
            } else {
                aapsLogger.debug(LTag.APS, "[$TAG] No steps to sync (StepService returned 0)")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Sync failed", e)
        }
    }
    
    /**
     * Checks if phone steps sync is enabled
     */
    private fun isEnabled(): Boolean {
        return sp.getBoolean(PREF_KEY_ENABLED, true) // Default: enabled
    }
}
