package app.aaps.plugins.aps.openAPSAIMI.steps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔄 AIMI Health Connect Sync Service - MTR Implementation
 * 
 * Periodically syncs step count data from Health Connect to AAPS database.
 * This is a COMPLEMENTARY source that writes to the same DB as Garmin/Wear OS.
 * 
 * Architecture:
 * - Runs every 5 minutes (configurable)
 * - Fetches steps from Health Connect for last 5 minutes
 * - Calculates all window aggregates (5, 10, 15, 30, 60, 180 min)
 * - Inserts into StepsCount table via PersistenceLayer
 * - Only writes if Health Connect has newer data than DB
 * 
 * Priority:
 * - Health Connect is a FALLBACK source (lower priority than Garmin/Wear)
 * - Only writes if no recent data from other sources
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 * @since Android SDK 14+
 */
@Singleton
class AIMIHealthConnectSyncServiceMTR @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "HealthConnectSync"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SOURCE_DEVICE = "HealthConnect"
        private const val PREF_KEY_ENABLED = "aimi_health_connect_steps_enable"
        private const val PREF_KEY_LAST_SYNC = "aimi_health_connect_last_sync_ms"
    }
    
    private val disposable = CompositeDisposable()
    private var syncTimer: Timer? = null
    private var lastSyncTimestamp: Long = 0
    
    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error initializing Health Connect client", e)
            null
        }
    }
    
    /**
     * Starts periodic synchronization
     */
    fun start() {
        if (!isEnabled()) {
            aapsLogger.info(LTag.APS, "[$TAG] Health Connect sync disabled in preferences")
            return
        }
        
        if (syncTimer != null) {
            aapsLogger.debug(LTag.APS, "[$TAG] Already running")
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] Starting Health Connect sync (every ${SYNC_INTERVAL_MS / 60000} min)")
        
        // Restore last sync timestamp
        lastSyncTimestamp = sp.getLong(PREF_KEY_LAST_SYNC, 0L)
        
        syncTimer = Timer("HealthConnectSync", true).apply {
            // First sync after 10 seconds, then every 5 minutes
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        syncStepsToDatabase()
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "[$TAG] Sync error", e)
                    }
                }
            }, 10_000L, SYNC_INTERVAL_MS)
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
     * Main sync logic: Health Connect → Database
     */
    private fun syncStepsToDatabase() {
        if (!isEnabled()) {
            aapsLogger.debug(LTag.APS, "[$TAG] Sync skipped (disabled)")
            return
        }
        
        val client = healthConnectClient
        if (client == null) {
            aapsLogger.debug(LTag.APS, "[$TAG] Health Connect client unavailable")
            return
        }
        
        val now = System.currentTimeMillis()
        val syncStart = now - SYNC_INTERVAL_MS // Last 5 minutes
        
        // Check if we already have recent data from other sources
        if (hasRecentDataFromOtherSources(syncStart, now)) {
            aapsLogger.debug(LTag.APS, "[$TAG] Recent data from Garmin/Wear exists, skipping Health Connect sync")
            return
        }
        
        aapsLogger.debug(LTag.APS, "[$TAG] Syncing steps from Health Connect (${Instant.ofEpochMilli(syncStart)} to ${Instant.ofEpochMilli(now)})")
        
        try {
            // Fetch steps from Health Connect for multiple windows
            val stepsData = fetchStepsFromHealthConnect(now)
            
            if (stepsData.steps5min > 0) {
                // Insert into database
                val sc = SC(
                    timestamp = now,
                    duration = SYNC_INTERVAL_MS,
                    steps5min = stepsData.steps5min,
                    steps10min = stepsData.steps10min,
                    steps15min = stepsData.steps15min,
                    steps30min = stepsData.steps30min,
                    steps60min = stepsData.steps60min,
                    steps180min = stepsData.steps180min,
                    device = SOURCE_DEVICE
                )
                
                disposable.add(
                    persistenceLayer.insertOrUpdateStepsCount(sc)
                        .subscribeOn(Schedulers.io())
                        .subscribe(
                            { result ->
                                aapsLogger.info(
                                    LTag.APS,
                                    "[$TAG] ✅ Synced: ${stepsData.steps5min} steps (5min), " +
                                            "15min=${stepsData.steps15min}, 30min=${stepsData.steps30min}"
                                )
                                lastSyncTimestamp = now
                                sp.putLong(PREF_KEY_LAST_SYNC, now)
                            },
                            { error ->
                                aapsLogger.error(LTag.APS, "[$TAG] ❌ DB insert failed", error)
                            }
                        )
                )
            } else {
                aapsLogger.debug(LTag.APS, "[$TAG] No steps detected in last 5 minutes")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Sync failed", e)
        }
    }
    
    /**
     * Fetches steps from Health Connect for multiple time windows
     */
    private fun fetchStepsFromHealthConnect(nowMs: Long): AIMIStepsDataMTR {
        val client = healthConnectClient ?: return AIMIStepsDataMTR.EMPTY
        
        return runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    // Define time windows (in minutes)
                    val windows = listOf(5, 10, 15, 30, 60, 180)
                    val stepsMap = mutableMapOf<Int, Long>()
                    
                    // Fetch steps for each window
                    for (windowMin in windows) {
                        val startTime = Instant.ofEpochMilli(nowMs - (windowMin * 60 * 1000L))
                        val endTime = Instant.ofEpochMilli(nowMs)
                        
                        val request = ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                        
                        val response = client.readRecords(request)
                        val totalSteps = response.records.sumOf { it.count }
                        stepsMap[windowMin] = totalSteps
                    }
                    
                    aapsLogger.debug(LTag.APS, "[$TAG] HC data: 5m=${stepsMap[5]}, 15m=${stepsMap[15]}, 180m=${stepsMap[180]}")
                    
                    AIMIStepsDataMTR(
                        steps5min = (stepsMap[5] ?: 0L).toInt(),
                        steps10min = (stepsMap[10] ?: 0L).toInt(),
                        steps15min = (stepsMap[15] ?: 0L).toInt(),
                        steps30min = (stepsMap[30] ?: 0L).toInt(),
                        steps60min = (stepsMap[60] ?: 0L).toInt(),
                        steps180min = (stepsMap[180] ?: 0L).toInt(),
                        source = SOURCE_DEVICE,
                        lastUpdateMillis = nowMs,
                        isValid = (stepsMap[5] ?: 0) > 0
                    )
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "[$TAG] Health Connect read error", e)
                    AIMIStepsDataMTR.EMPTY
                }
            }
        }
    }
    
    /**
     * Checks if DB has recent data from Garmin/Wear OS
     * Returns true if recent data exists (Health Connect should NOT write)
     */
    private fun hasRecentDataFromOtherSources(startMs: Long, endMs: Long): Boolean {
        return try {
            val recentSteps = persistenceLayer.getStepsCountFromTimeToTime(startMs, endMs)
            
            // Filter out our own Health Connect data
            val otherSourcesData = recentSteps.filter { it.device != SOURCE_DEVICE }
            
            if (otherSourcesData.isNotEmpty()) {
                val latestSource = otherSourcesData.maxByOrNull { it.timestamp }
                aapsLogger.debug(LTag.APS, "[$TAG] Found recent data from ${latestSource?.device}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Error checking existing data", e)
            false // Assume no data, proceed with sync
        }
    }
    
    /**
     * Checks if Health Connect sync is enabled in preferences
     */
    private fun isEnabled(): Boolean {
        return sp.getBoolean(PREF_KEY_ENABLED, true) // Default: enabled
    }
    
    /**
     * Manual sync trigger (for testing or user-initiated sync)
     */
    fun triggerManualSync(): Boolean {
        aapsLogger.info(LTag.APS, "[$TAG] Manual sync triggered")
        Thread {
            syncStepsToDatabase()
        }.start()
        return true
    }
    
    /**
     * Gets sync status for UI display
     */
    fun getSyncStatus(): String {
        val enabled = isEnabled()
        val clientAvailable = healthConnectClient != null
        val lastSync = if (lastSyncTimestamp > 0) {
            val ageSeconds = (System.currentTimeMillis() - lastSyncTimestamp) / 1000
            "${ageSeconds}s ago"
        } else {
            "Never"
        }
        
        return when {
            !enabled -> "Disabled"
            !clientAvailable -> "Health Connect unavailable"
            else -> "Active (last sync: $lastSync)"
        }
    }
}
