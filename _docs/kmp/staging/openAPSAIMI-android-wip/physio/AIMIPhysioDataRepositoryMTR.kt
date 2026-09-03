package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.BasalBodyTemperatureMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.HcRecoveryProxyThermalSource
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.OuraApiThermalClient
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataCache
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataOrigins
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataWindowMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSampleMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSourceTier
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
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
@SingleIn(AppScope::class)
class AIMIPhysioDataRepositoryMTR @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val ouraApiThermalClient: OuraApiThermalClient,
) : AimiPhysioSource {
    
    companion object {
        private const val TAG = "PhysioRepository"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
        private const val API_TIMEOUT_MS = 10_000L // 10 seconds
        private const val MORNING_WINDOW_START = 2 // 2 AM (Widened)
        private const val MORNING_WINDOW_END = 11 // 11 AM (Widened)
        /** Dashboard today-steps aggregate: short TTL so a lagging HC value is not pinned too long. */
        private const val TODAY_STEPS_HC_CACHE_MS = 12_000L
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

    private data class TodayStepsAggCache(
        val dayStartMs: Long,
        val total: Long,
        val cachedAtMs: Long
    )

    /** Short TTL: dashboard debounces refreshes; shorter cache avoids sticking on a lagging HC aggregate. */
    private val todayStepsAggCache = AtomicReference<TodayStepsAggCache?>(null)
    
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
    suspend fun fetchSleepData(): SleepDataMTR? {
        val cacheKey = "sleep_last"
        val cached = cache[cacheKey] as? CachedData<SleepDataMTR>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] Sleep data from cache")
            return cached.data
        }
        
        val client = healthConnectClient ?: return null
        
        return try {
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
                        
                        // Inclure les nuits qui se terminent dans la même fenêtre que la lecture (48h).
                        // Un filtre 24h seulement excluait des sessions Garmin valides (décalage fuseau / fin de nuit).
                        val windowCutoff = now.minusSeconds(48 * 60 * 60)
                        val recentSessions = response.records.filter { it.endTime.isAfter(windowCutoff) }
                        val nowMs = now.toEpochMilli()
                        val ongoingRecord = response.records.firstOrNull { record ->
                            val startMs = record.startTime.toEpochMilli()
                            val endMs = record.endTime.toEpochMilli()
                            nowMs in startMs..endMs
                        }

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
                                fragmentationScore = 0.0,
                                ongoingStartMs = ongoingRecord?.startTime?.toEpochMilli(),
                                ongoingEndMs = ongoingRecord?.endTime?.toEpochMilli(),
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
    suspend fun fetchHRVData(daysBack: Int = 7): List<HRVDataMTR> {
        val cacheKey = "hrv_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<List<HRVDataMTR>>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] HRV data from cache")
            return cached.data ?: emptyList()
        }
        
        val client = healthConnectClient ?: return emptyList()
        
        return try {
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
    override suspend fun fetchLastHeartRate(): Int {
        val client = healthConnectClient ?: return 0
        return try {
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
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "[$TAG] Last HR fetch failed: ${e.message}")
            0
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RESTING HEART RATE (RHR)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FC « repos » telle qu’écrite par Garmin Connect et d’autres apps (type HC dédié).
     */
    private suspend fun readRestingHeartRateRecordsAsRhr(
        client: HealthConnectClient,
        daysBack: Int,
        now: Instant
    ): List<RHRDataMTR> {
        return try {
            val start = now.minusSeconds((daysBack * 24 * 60 * 60).toLong())
            val request = ReadRecordsRequest(
                recordType = RestingHeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, now)
            )
            val response = client.readRecords(request)
            response.records.mapNotNull { rec ->
                val bpm = rec.beatsPerMinute.toInt()
                if (bpm in 35..120) {
                    RHRDataMTR(
                        timestamp = rec.time.toEpochMilli(),
                        bpm = bpm,
                        source = "HealthConnect(RestingHR:${rec.metadata.dataOrigin.packageName})"
                    )
                } else null
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] RestingHeartRateRecord read failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Fetches morning Resting Heart Rate for last N days
     * Morning window: 5 AM - 9 AM
     * 
     * @return List of RHRDataMTR, empty if unavailable
     */
    suspend fun fetchMorningRHR(daysBack: Int = 7): List<RHRDataMTR> {
        val cacheKey = "rhr_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<List<RHRDataMTR>>
        
        if (cached?.isValid() == true) {
            aapsLogger.debug(LTag.APS, "[$TAG] RHR data from cache")
            return cached.data ?: emptyList()
        }
        
        val client = healthConnectClient ?: return emptyList()
        
        return try {
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
                        var sortedRHR = rhrList.sortedBy { it.timestamp }

                        if (sortedRHR.isEmpty()) {
                            sortedRHR = readRestingHeartRateRecordsAsRhr(client, daysBack, now)
                        }

                        cache[cacheKey] = CachedData(sortedRHR, System.currentTimeMillis())
                        
                        if (sortedRHR.isNotEmpty()) {
                            val avgRHR = sortedRHR.map { it.bpm }.average()
                            aapsLogger.info(
                                LTag.APS,
                                "[$TAG] ✅ RHR: ${sortedRHR.size} points, avg=${avgRHR.toInt()} bpm (aggregate morning min and/or RestingHeartRateRecord)"
                            )
                        }
                        
                        sortedRHR
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
     *
     * @param ignoreUnifiedSourceMode si true, lit quand même Health Connect (ex. pipeline Physio quand
     *        les pas Garmin sont dans HC mais le mode UI est « préférer la montre »).
     */
    override suspend fun fetchStepsData(daysBack: Int, ignoreUnifiedSourceMode: Boolean): Int {
        if (!ignoreUnifiedSourceMode) {
            val mode = app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.getMode(context)
            if (mode == app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.MODE_PREFER_WEAR ||
                mode == app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.MODE_DISABLED) {
                return 0
            }
        }

        val cacheKey = "steps_${daysBack}days"
        val cached = cache[cacheKey] as? CachedData<Int>
        
        if (cached?.isValid() == true) {
            return cached.data ?: 0
        }
        
        val client = healthConnectClient ?: return 0
        
        return try {
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
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Steps aggregation failed", e)
            0
        }
    }

    /**
     * Clears the in-memory today aggregate so the next [fetchTodayStepsTotalAggregated] call queries
     * Health Connect again. Intended when step rows ([SC]) change in AAPS so the dashboard can pick up
     * HC-side updates without waiting for the short TTL.
     */
    fun invalidateTodayStepsHcAggregateCache() {
        todayStepsAggCache.set(null)
    }

    /**
     * Dashboard “today” steps aligned with Health Connect’s consolidated daily total when permitted.
     * Uses [StepsRecord.COUNT_TOTAL] from local midnight ([dayStartMs]) to [nowMs].
     *
     * The aggregate can refresh more slowly than step rows ingested into AAPS; the dashboard combines
     * this value with persistence totals so a lagging aggregate does not replace fresher DB rows.
     *
     * Returns **null** when unified activity mode skips HC, HC is unavailable, or the query fails —
     * callers should fall back to persistence-layer bucket logic.
     */
    suspend fun fetchTodayStepsTotalAggregated(dayStartMs: Long, nowMs: Long): Long? {
        val mode = UnifiedActivityProviderMTR.getMode(context)
        if (mode == UnifiedActivityProviderMTR.MODE_PREFER_WEAR ||
            mode == UnifiedActivityProviderMTR.MODE_DISABLED
        ) {
            return null
        }
        if (dayStartMs >= nowMs) return null

        val nowWall = System.currentTimeMillis()
        val cached = todayStepsAggCache.get()
        if (cached != null &&
            cached.dayStartMs == dayStartMs &&
            nowWall - cached.cachedAtMs < TODAY_STEPS_HC_CACHE_MS
        ) {
            return cached.total
        }

        val client = healthConnectClient ?: return null

        return try {
            withTimeout(API_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val response = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(
                                Instant.ofEpochMilli(dayStartMs),
                                Instant.ofEpochMilli(nowMs)
                            )
                        )
                    )
                    val total = response[StepsRecord.COUNT_TOTAL] ?: 0L
                    todayStepsAggCache.set(TodayStepsAggCache(dayStartMs, total, System.currentTimeMillis()))
                    total
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Today steps aggregate failed", e)
            null
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
    suspend fun fetchAllData(daysBack: Int = 7): RawPhysioDataMTR {
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
            
            val steps = fetchStepsData(daysBack, ignoreUnifiedSourceMode = true)
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
    suspend fun isAvailable(): Boolean {
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
            val grantedPerms = try {
                client.permissionController.getGrantedPermissions()
            } catch (e: Exception) {
                emptySet<String>()
            }
            
            // 🔍 DIAGNOSTIC: Log actual granted strings seen by the app
            aapsLogger.info(LTag.APS, "[$TAG] 🔍 PERMISSIONS DIAGNOSTIC:")
            aapsLogger.info(LTag.APS, "[$TAG]    Required (Central): ${AIMIHealthConnectPermissions.ALL_REQUIRED_PERMISSIONS.map { it.substringAfterLast(".") }}")
            aapsLogger.info(LTag.APS, "[$TAG]    Granted (System):   ${grantedPerms.map { it.substringAfterLast(".") }}")
            
            // Use the centralized source of truth for checking
            val missingCore = AIMIHealthConnectPermissions.getMissingPermissions(grantedPerms)
            val missingThermal = AIMIHealthConnectPermissions.getMissingOptionalThermalPermissions(grantedPerms)

            if (missingCore.isNotEmpty()) {
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] ⚠️ Missing core Health Connect permissions: " +
                        AIMIHealthConnectPermissions.getMissingPermissionsSummary(grantedPerms),
                )
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Grant permissions in: Settings > Apps > AAPS > Health Connect")
            } else {
                aapsLogger.info(LTag.APS, "[$TAG] ✅ Core Health Connect permissions granted for Physio")
                if (missingThermal.isNotEmpty()) {
                    aapsLogger.info(
                        LTag.APS,
                        "[$TAG] ℹ️ Optional thermal not granted: " +
                            AIMIHealthConnectPermissions.getMissingOptionalThermalSummary(grantedPerms),
                    )
                }
            }
            
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Could not check permissions: ${e.message}")
        }
        
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THERMAL DATA (Garmin / Oura via Health Connect)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetches thermal data using a source chain:
     * 1) Health Connect skin temperature (Samsung / future writers)
     * 2) Oura API daily readiness deviation (Garmin/Oura do not write skin temp to HC)
     * 3) HC recovery proxy from RHR + HRV (Garmin and Oura via HC)
     */
    internal suspend fun fetchThermalWindow(daysBack: Int = 3): ThermalDataWindowMTR {
        val cacheKey = "thermal_${daysBack}d"
        val cached = cache[cacheKey] as? CachedData<ThermalDataWindowMTR>
        if (cached?.isValid() == true) {
            return cached.data ?: ThermalDataWindowMTR()
        }

        val client = healthConnectClient
        val fetchedAtMs = System.currentTimeMillis()

        return try {
            withTimeout(API_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val now = Instant.now()
                    val start = now.minusSeconds(daysBack * 24L * 60L * 60L)

                    if (client != null) {
                        val hcSkin = fetchSkinTemperatureSamples(client, start, now)
                        val basal = fetchLatestBasalBodyTemperature(client, start, now)
                        if (hcSkin.isNotEmpty() || basal != null) {
                            return@withContext storeThermalWindow(
                                cacheKey = cacheKey,
                                window = ThermalDataWindowMTR(
                                    skinSamples = hcSkin,
                                    basalBodyTemperature = basal,
                                    fetchedAtMs = fetchedAtMs,
                                    sourceTier = ThermalSourceTier.MEASURED,
                                    resolvedSource = ThermalDataOrigins.HC_SKIN,
                                ),
                                logLabel = "HC skin: ${hcSkin.size} samples, basal=${basal?.temperatureCelsius}",
                            )
                        }
                    }

                    val ouraSamples = ouraApiThermalClient.fetchSamples(daysBack)
                    if (ouraSamples.isNotEmpty()) {
                        return@withContext storeThermalWindow(
                            cacheKey = cacheKey,
                            window = ThermalDataWindowMTR(
                                skinSamples = ouraSamples,
                                fetchedAtMs = fetchedAtMs,
                                sourceTier = ThermalSourceTier.MEASURED,
                                resolvedSource = ThermalDataOrigins.OURA_API,
                            ),
                            logLabel = "Oura API: ${ouraSamples.size} readiness samples",
                        )
                    }

                    val vitalsDays = daysBack.coerceAtLeast(7)
                    val rhr = fetchMorningRHR(vitalsDays)
                    val hrv = fetchHRVData(vitalsDays)
                    val proxySamples = HcRecoveryProxyThermalSource.build(
                        rhrPoints = rhr,
                        hrvPoints = hrv,
                        daysBack = daysBack,
                        nowMs = fetchedAtMs,
                    )
                    if (proxySamples.isNotEmpty()) {
                        val origin = proxySamples.last().dataOrigin
                        return@withContext storeThermalWindow(
                            cacheKey = cacheKey,
                            window = ThermalDataWindowMTR(
                                skinSamples = proxySamples,
                                fetchedAtMs = fetchedAtMs,
                                sourceTier = ThermalSourceTier.INFERRED,
                                resolvedSource = origin,
                            ),
                            logLabel = "HC proxy: ${proxySamples.size} recovery samples ($origin)",
                        )
                    }

                    ThermalDataWindowMTR(fetchedAtMs = fetchedAtMs).also {
                        cache[cacheKey] = CachedData(it, System.currentTimeMillis())
                        ThermalDataCache.update(it)
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Thermal fetch failed: ${e.message}")
            ThermalDataWindowMTR(fetchedAtMs = fetchedAtMs)
        }
    }

    private fun storeThermalWindow(
        cacheKey: String,
        window: ThermalDataWindowMTR,
        logLabel: String,
    ): ThermalDataWindowMTR {
        cache[cacheKey] = CachedData(window, System.currentTimeMillis())
        ThermalDataCache.update(window)
        aapsLogger.info(LTag.APS, "[$TAG] ✅ Thermal: $logLabel")
        return window
    }

    private suspend fun fetchSkinTemperatureSamples(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): List<ThermalSampleMTR> {
        val featureStatus = try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
        } catch (_: Exception) {
            HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        }
        if (featureStatus != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            return emptyList()
        }

        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SkinTemperatureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val samples = mutableListOf<ThermalSampleMTR>()
            for (record in response.records) {
                val origin = record.metadata.dataOrigin.packageName
                val location = skinMeasurementLocationLabel(record.measurementLocation)
                var cumulative = record.baseline?.inCelsius ?: 0.0
                for (delta in record.deltas) {
                    cumulative += delta.delta.inCelsius
                    samples += ThermalSampleMTR(
                        timestampMs = delta.time.toEpochMilli(),
                        deltaCelsius = cumulative - (record.baseline?.inCelsius ?: 0.0),
                        measurementLocation = location,
                        dataOrigin = origin,
                    )
                }
                if (record.deltas.isEmpty() && record.baseline != null) {
                    samples += ThermalSampleMTR(
                        timestampMs = record.endTime.toEpochMilli(),
                        deltaCelsius = 0.0,
                        measurementLocation = location,
                        dataOrigin = origin,
                    )
                }
            }
            samples.sortedBy { it.timestampMs }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Skin temperature read failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchLatestBasalBodyTemperature(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): BasalBodyTemperatureMTR? =
        try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = BasalBodyTemperatureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val latest = response.records.maxByOrNull { it.time } ?: return null
            BasalBodyTemperatureMTR(
                timestampMs = latest.time.toEpochMilli(),
                temperatureCelsius = latest.temperature.inCelsius,
                dataOrigin = latest.metadata.dataOrigin.packageName,
            )
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Basal body temperature read failed: ${e.message}")
            null
        }

    private fun skinMeasurementLocationLabel(location: Int): String =
        when (location) {
            SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST -> "WRIST"
            SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER -> "FINGER"
            SkinTemperatureRecord.MEASUREMENT_LOCATION_TOE -> "TOE"
            else -> "UNKNOWN"
        }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
