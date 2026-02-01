package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 🏥 AIMI Physiological Data Repository - MTR Implementation
 * 
 * Fetches physiological data from Google Health Connect (Android 14+).
 * Implements caching, freshness checks, and graceful degradation.
 * 
 * CRITICAL: This class NEVER crashes if data is unavailable.
 * All methods return nullable results with safe defaults.
 * 
 * Data Sources:
 * - Sleep: Duration, stages, efficiency
 * - HRV: RMSSD (Root Mean Square of Successive Differences)
 * - Heart Rate: Resting HR calculation
 * - Steps: Daily totals (delegated to existing StepsManager)
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioDataRepositoryMTR @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioRepository"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
        private const val API_TIMEOUT_MS = 10_000L // 10 seconds
        private const val MORNING_WINDOW_START = 2 // 2 AM (Widened)
        private const val MORNING_WINDOW_END = 11 // 11 AM (Widened)
    }
    
    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Health Connect unavailable", e)
            null
        }
    }
    
    // Cache storage
    private data class CachedData<T>(
        val data: T?,
        val timestamp: Long,
        val expiresAt: Long = timestamp + CACHE_TTL_MS
    ) {
        fun isValid(): Boolean = System.currentTimeMillis() < expiresAt
    }
    
    private val cache = ConcurrentHashMap<String, CachedData<*>>()
    
    // ═══════════════════════════════════════════════════════════════════════
    // PROBE & DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * 🔍 Probes Health Connect to diagnose availability and data counts
     * CRITICAL for debugging "NEVER_SYNCED" issues
     */
    suspend fun probeHealthConnect(windowDays: Long = 7): ProbeResult {
        val client = healthConnectClient
        
        if (client == null) {
            aapsLogger.error(LTag.APS, "[$TAG] PROBE: Health Connect client unavailable")
            return ProbeResult(
                sdkStatus = "UNAVAILABLE",
                grantedPermissions = emptySet(),
                sleepCount = 0,
                hrvCount = 0,
                heartRateCount = 0,
                stepsCount = 0,
                dataOrigins = emptySet(),
                windowDays = windowDays.toInt()
            )
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val sdkStatus = try {
                    HealthConnectClient.getSdkStatus(context).toString()
                } catch (e: Exception) {
                    "SDK_CHECK_FAILED"
                }
                
                val grantedPerms = try {
                    client.permissionController.getGrantedPermissions()
                } catch (e: Exception) {
                    emptySet()
                }
                
                val now = Instant.now()
                val start = now.minusSeconds(windowDays * 24 * 60 * 60)
                val writers = mutableSetOf<String>()
                
                // Count Sleep
                val sleepCount = try {
                    val req = ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, now))
                    val resp = client.readRecords(req)
                    resp.records.forEach { writers.add(it.metadata.dataOrigin.packageName) }
                    resp.records.size
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] PROBE: Sleep count failed - ${e.message}")
                    0
                }
                
                // Count HRV
                val hrvCount = try {
                    val req = ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(start, now))
                    val resp = client.readRecords(req)
                    resp.records.forEach { writers.add(it.metadata.dataOrigin.packageName) }
                    resp.records.size
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] PROBE: HRV count failed - ${e.message}")
                    0
                }
                
                // Count HeartRate
                val hrCount = try {
                    val req = ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, now))
                    val resp = client.readRecords(req)
                    resp.records.forEach { writers.add(it.metadata.dataOrigin.packageName) }
                    resp.records.size
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] PROBE: HR count failed - ${e.message}")
                    0
                }
                
                // Count Steps
                val stepsCount = try {
                    val req = ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, now))
                    val resp = client.readRecords(req)
                    resp.records.forEach { writers.add(it.metadata.dataOrigin.packageName) }
                    resp.records.size
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] PROBE: Steps count failed - ${e.message}")
                    0
                }
                
                val result = ProbeResult(
                    sdkStatus = sdkStatus,
                    grantedPermissions = grantedPerms,
                    sleepCount = sleepCount,
                    hrvCount = hrvCount,
                    heartRateCount = hrCount,
                    stepsCount = stepsCount,
                    dataOrigins = writers,
                    windowDays = windowDays.toInt()
                )
                
                aapsLogger.info(LTag.APS, "[$TAG] ✅ PROBE: ${result.toLogString()}")
                aapsLogger.info(LTag.APS, "[$TAG] PROBE: Granted perms=${grantedPerms.size}, SDK=$sdkStatus")
                
                result
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] PROBE CRASH", e)
                ProbeResult(
                    sdkStatus = "PROBE_FAILED",
                    grantedPermissions = emptySet(),
                    sleepCount = 0,
                    hrvCount = 0,
                    heartRateCount = 0,
                    stepsCount = 0,
                    dataOrigins = emptySet(),
                    windowDays = windowDays.toInt()
                )
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SLEEP DATA
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Fetches last night's sleep data (or most recent sleep session)
     * 
     * @return SleepDataMTR or null if unavailable
     */
    fun fetchSleepData(): SleepDataMTR? {
        val cacheKey = "sleep_last"
        val cached = cache[cacheKey] as? CachedData<SleepDataMTR>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] Sleep data from cache")
            return cached.data
        }
        
        val client = healthConnectClient ?: return null
        
        return try {
            runBlocking {
                withTimeout(API_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val yesterday = now.minusSeconds(48 * 60 * 60) // Last 48h
                        
                        val request = ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(yesterday, now)
                        )
                        
                        val response = client.readRecords(request)
                        
                        // 🚀 FILTER & AGGREGATE Sleep Sessions
                        // Health Connect can return multiple segments (Naps, fragmented night)
                        // We sum up all sleep within the 'Last Night' window (e.g. last 16h to be safe, or just use the response window)
                        // The response window is 'yesterday' to 'now' (48h). 
                        // To get "Last Night" specifically, we should look for the most recent generic block.
                        
                        // Simple robust logic: Sum all sleep ending in the last 24h
                        val oneDayAgo = now.minusSeconds(24 * 60 * 60)
                        val recentSessions = response.records.filter { it.endTime.isAfter(oneDayAgo) }

                        if (recentSessions.isNotEmpty()) {
                            // Sum durations
                            val totalDurationHours = recentSessions.sumOf { 
                                (it.endTime.epochSecond - it.startTime.epochSecond) / 3600.0 
                            }
                            
                            // Use the latest end time as the session "end"
                            val latestEnd = recentSessions.maxOf { it.endTime }
                            val earliestStart = recentSessions.minOf { it.startTime }

                            val sleepData = SleepDataMTR(
                                startTime = earliestStart.toEpochMilli(),
                                endTime = latestEnd.toEpochMilli(),
                                durationHours = totalDurationHours,
                                efficiency = 0.85, // Conservative estimate
                                deepSleepMinutes = 0,
                                remSleepMinutes = 0,
                                lightSleepMinutes = 0,
                                awakeMinutes = 0,
                                fragmentationScore = 0.0
                            )
                            
                            cache[cacheKey] = CachedData(sleepData, System.currentTimeMillis())
                            
                            aapsLogger.info(
                                LTag.APS,
                                "[$TAG] ✅ Sleep (Aggregated): ${totalDurationHours.format(1)}h from ${recentSessions.size} segments"
                            )
                            
                            sleepData
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Sleep data fetch failed", e)
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HRV DATA
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Fetches HRV data for last 7 days
     * 
     * @return List of HRVDataMTR, empty if unavailable
     */
    fun fetchHRVData(daysBack: Int = 7): List<HRVDataMTR> {
        val cacheKey = "hrv_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<List<HRVDataMTR>>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] HRV data from cache")
            return cached.data ?: emptyList()
        }
        
        val client = healthConnectClient ?: return emptyList()
        
        return try {
            runBlocking {
                withTimeout(API_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val startTime = now.minusSeconds((daysBack * 24 * 60 * 60).toLong())
                        
                        val request = ReadRecordsRequest(
                            recordType = HeartRateVariabilityRmssdRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, now)
                        )
                        
                        val response = client.readRecords(request)
                        
                        val hrvList = response.records.map { record ->
                            HRVDataMTR(
                                timestamp = record.time.toEpochMilli(),
                                rmssd = record.heartRateVariabilityMillis,
                                source = record.metadata.dataOrigin.packageName
                            )
                        }
                        
                        cache[cacheKey] = CachedData(hrvList, System.currentTimeMillis())
                        
                        if (hrvList.isNotEmpty()) {
                            val avgRMSSD = hrvList.map { it.rmssd }.average()
                            aapsLogger.info(
                                LTag.APS,
                                "[$TAG] ✅ HRV: ${hrvList.size} samples, avg RMSSD=${avgRMSSD.format(1)}ms"
                            )
                        }
                        
                        hrvList
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] HRV data fetch failed", e)
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HEART RATE DATA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetches the most recent Heart Rate sample (Real-Time check)
     * Lookback window: 1 hour
     */
    fun fetchLastHeartRate(): Int {
        val client = healthConnectClient ?: return 0
        return try {
            runBlocking {
                withTimeout(API_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val start = now.minusSeconds(3600) // 1 hour lookback
                        val request = ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, now),
                            ascendingOrder = false, // Newest first
                            pageSize = 1
                        )
                        val response = client.readRecords(request)
                        val lastRecord = response.records.firstOrNull()
                        // Get the last sample in the record series
                        lastRecord?.samples?.lastOrNull()?.beatsPerMinute?.toInt() ?: 0
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "[$TAG] Last HR fetch failed: ${e.message}")
            0
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RESTING HEART RATE (RHR)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Fetches morning Resting Heart Rate for last N days
     * Morning window: 5 AM - 9 AM
     * 
     * @return List of RHRDataMTR, empty if unavailable
     */
    fun fetchMorningRHR(daysBack: Int = 7): List<RHRDataMTR> {
        val cacheKey = "rhr_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<List<RHRDataMTR>>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] RHR data from cache")
            return cached.data ?: emptyList()
        }
        
        val client = healthConnectClient ?: return emptyList()
        
        return try {
            runBlocking {
                withTimeout(API_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val zoneId = ZoneId.systemDefault()
                        val rhrList = mutableListOf<RHRDataMTR>()
                        
                        // Loop through last N days to get daily morning mins
                        for (i in 0 until daysBack) {
                            val dayStart = now.minusSeconds((i * 24 * 60 * 60).toLong())
                            
                             // Define Morning Window (e.g., 04:00 - 10:00 local time) regarding the *start* of that 24h block
                            val localDate = dayStart.atZone(zoneId).toLocalDate()
                            val windowStart = localDate.atTime(4, 0).atZone(zoneId).toInstant()
                            val windowEnd = localDate.atTime(10, 0).atZone(zoneId).toInstant()
                            
                            // Skip if window is in future
                            if (windowStart.isAfter(now)) continue

                            val aggregation = client.aggregate(
                                AggregateRequest(
                                    metrics = setOf(HeartRateRecord.BPM_MIN),
                                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd)
                                )
                            )
                            val minBPM = aggregation[HeartRateRecord.BPM_MIN]
                            
                            if (minBPM != null && minBPM > 0) {
                                rhrList.add(
                                    RHRDataMTR(
                                        timestamp = windowStart.toEpochMilli(),
                                        bpm = minBPM.toInt(),
                                        source = "HealthConnect(DailyMin)"
                                    )
                                )
                            }
                        }
                        
                        // Sort by timestamp (oldest first usually, but list is irrelevant)
                        val sortedRHR = rhrList.sortedBy { it.timestamp }

                        cache[cacheKey] = CachedData(sortedRHR, System.currentTimeMillis())
                        
                        if (sortedRHR.isNotEmpty()) {
                            val avgRHR = sortedRHR.map { it.bpm }.average()
                            aapsLogger.info(
                                LTag.APS,
                                "[$TAG] ✅ RHR: ${sortedRHR.size} daily points, avg=${avgRHR.toInt()} bpm"
                            )
                        }
                        
                        sortedRHR
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] RHR data fetch failed", e)
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STEPS DATA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetches steps for last 7 days (daily totals)
     */
    fun fetchStepsData(daysBack: Int = 7): Int {
        // Check preference first
        val mode = app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.getMode(context)
        if (mode == app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.MODE_PREFER_WEAR || 
            mode == app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.MODE_DISABLED) {
            // Steps handled by Wear/Plugin directly, or disabled
            return 0
        }

        val cacheKey = "steps_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<Int>
        
        if (cached?.isValid() == true) {
            return cached.data ?: 0
        }
        
        val client = healthConnectClient ?: return 0
        
        return try {
            runBlocking {
                withTimeout(API_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val now = Instant.now()
                        val startTime = now.minusSeconds((daysBack * 24 * 60 * 60).toLong())
                        
                        val response = client.aggregate(
                            AggregateRequest(
                                metrics = setOf(StepsRecord.COUNT_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(startTime, now)
                            )
                        )
                        
                        val totalSteps = response[StepsRecord.COUNT_TOTAL] ?: 0L
                        
                        // Average daily steps
                        val avgSteps = if (daysBack > 0) (totalSteps / daysBack).toInt() else 0
                        
                        cache[cacheKey] = CachedData(avgSteps, System.currentTimeMillis())
                        
                        aapsLogger.info(LTag.APS, "[$TAG] ✅ Steps (HC Aggregated): total=$totalSteps, avg=$avgSteps/day")
                        avgSteps
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Steps aggregation failed", e)
            0
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HEART RATE SAMPLE DATA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetches detailed HR samples for RHR calculation fallback
     */
    private suspend fun fetchHeartRateSamples(startTime: Instant, endTime: Instant): List<HeartRateRecord> {
        val client = healthConnectClient ?: return emptyList()
        return try {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            client.readRecords(request).records
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AGGREGATED DATA FETCH
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Fetches all physiological data in one call
     * 
     * @return RawPhysioDataMTR with available data (never null)
     */
    fun fetchAllData(daysBack: Int = 7): RawPhysioDataMTR {
        val startTime = System.currentTimeMillis()
        
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Fetching physiological data (${daysBack}d window)...")
        
        return try {
            // 📊 DIAGNOSTIC: Log each fetch result individually
            val sleep = fetchSleepData()
            aapsLogger.info(LTag.APS, "[$TAG] 📊 FETCH RESULT - Sleep: ${if (sleep != null) "${sleep.durationHours.format(1)}h" else "NULL (no data)"}")
            
            val hrv = fetchHRVData(daysBack)
            aapsLogger.info(LTag.APS, "[$TAG] 📊 FETCH RESULT - HRV: ${hrv.size} samples ${if (hrv.isEmpty()) "(empty - no HRV data in HC)" else ""}")
            
            val rhr = fetchMorningRHR(daysBack)
            aapsLogger.info(LTag.APS, "[$TAG] 📊 FETCH RESULT - RHR: ${rhr.size} samples ${if (rhr.isEmpty()) "(empty - no morning HR data)" else ""}")
            
            val steps = fetchStepsData(daysBack)
            aapsLogger.info(LTag.APS, "[$TAG] 📊 FETCH RESULT - Steps: $steps avg/day ${if (steps == 0) "(no steps data)" else ""}")
            
            val elapsed = System.currentTimeMillis() - startTime
            
            // 📊 SUMMARY
            val hasAnyData = sleep != null || hrv.isNotEmpty() || rhr.isNotEmpty() || steps > 0
            if (!hasAnyData) {
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ FETCH SUMMARY: NO DATA from Health Connect!")
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Check: 1) HC permissions in Settings 2) Samsung Health/Oura sync to HC 3) Recent sleep/HR data exists")
            } else {
                aapsLogger.info(LTag.APS, "[$TAG] ✅ Fetch completed in ${elapsed}ms - Sleep=${sleep != null}, HRV=${hrv.size}, RHR=${rhr.size}, Steps=$steps")
            }
            
            RawPhysioDataMTR(
                sleep = sleep,
                hrv = hrv,
                rhr = rhr,
                steps = steps,
                fetchTimestamp = System.currentTimeMillis()
            )
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ SECURITY ERROR: Health Connect permissions denied! Check Settings > Apps > AAPS > Health Connect", e)
            RawPhysioDataMTR.EMPTY
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Fetch failed: ${e.javaClass.simpleName} - ${e.message}", e)
            RawPhysioDataMTR.EMPTY
        }
    }
    
    /**
     * Clears all cached data
     */
    fun clearCache() {
        cache.clear()
        aapsLogger.debug(LTag.APS, "[$TAG] Cache cleared")
    }
    
    /**
     * Checks if Health Connect is available and permissions granted
     * Logs diagnostic info about permission state
     */
    fun isAvailable(): Boolean {
        val client = healthConnectClient
        if (client == null) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect client is NULL - not available on this device")
            return false
        }
        
        // Check SDK status
        try {
            val sdkStatus = HealthConnectClient.getSdkStatus(context)
            aapsLogger.info(LTag.APS, "[$TAG] Health Connect SDK Status: $sdkStatus")
            
            if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect SDK not available (status=$sdkStatus)")
                return false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Failed to check SDK status", e)
            return false
        }
        
        // Check granted permissions (async)
        try {
            val grantedPerms = runBlocking {
                try {
                    client.permissionController.getGrantedPermissions()
                } catch (e: Exception) {
                    emptySet<String>()
                }
            }
            
            // 🔍 DIAGNOSTIC: Log actual granted strings seen by the app
            aapsLogger.info(LTag.APS, "[$TAG] 🔍 PERMISSIONS DIAGNOSTIC:")
            aapsLogger.info(LTag.APS, "[$TAG]    Required (Central): ${AIMIHealthConnectPermissions.ALL_REQUIRED_PERMISSIONS.map { it.substringAfterLast(".") }}")
            aapsLogger.info(LTag.APS, "[$TAG]    Granted (System):   ${grantedPerms.map { it.substringAfterLast(".") }}")
            
            // Use the centralized source of truth for checking
            val requiredPerms = AIMIHealthConnectPermissions.PHYSIO_REQUIRED_PERMISSIONS
            val missing = requiredPerms.filter { !grantedPerms.contains(it) }
            
            if (missing.isNotEmpty()) {
                val missingNames = missing.map { 
                    AIMIHealthConnectPermissions.PERMISSION_NAMES[it] ?: it.substringAfterLast(".") 
                }
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Missing Health Connect permissions: ${missingNames.joinToString(", ")}")
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Grant permissions in: Settings > Apps > AAPS > Health Connect")
            } else {
                aapsLogger.info(LTag.APS, "[$TAG] ✅ All required Health Connect permissions granted for Physio")
            }
            
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Could not check permissions: ${e.message}")
        }
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
