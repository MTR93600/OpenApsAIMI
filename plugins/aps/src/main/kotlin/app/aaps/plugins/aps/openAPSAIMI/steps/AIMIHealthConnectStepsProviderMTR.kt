package app.aaps.plugins.aps.openAPSAIMI.steps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🏥 AIMI Health Connect Steps Provider - MTR Implementation
 * 
 * Provides step count data from Android 14+ Health Connect as a fallback source.
 * 
 * Features:
 * - Automatic permission checking
 * - Cache TTL (2 minutes) to prevent excessive Health Connect queries
 * - Support for all AIMI window durations (5, 10, 15, 30, 60, 180 min)
 * - Graceful degradation (returns 0 if unavailable)
 * - No crashes if Health Connect not installed or permissions denied
 * 
 * Priority: 3 (after Wear OS and Phone sensors)
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 * @since Android SDK 14+
 */
@Singleton
class AIMIHealthConnectStepsProviderMTR @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : AIMIStepsProviderMTR {
    
    companion object {
        private const val CACHE_TTL_MS = 120_000L // 2 minutes
        private const val SOURCE_NAME = "HealthConnect"
        private const val PRIORITY = 3 // After Wear (1) and Phone (2)
    }
    
    // Cache to avoid excessive Health Connect queries
    private val cache = mutableMapOf<Int, CachedStepsData>()
    private var lastAvailabilityCheck = 0L
    private var cachedAvailability = false
    
    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error initializing Health Connect client", e)
            null
        }
    }
    
    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int {
        if (!isAvailable()) {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Provider not available, returning 0 steps")
            return 0
        }
        
        // Check cache first
        val cached = cache[windowMinutes]
        if (cached != null && !cached.isExpired()) {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Cache hit for {$windowMinutes}min: ${cached.steps} steps (age=${cached.ageSeconds()}s)")
            return cached.steps
        }
        
        // Fetch from Health Connect
        return try {
            val steps = fetchStepsFromHealthConnect(windowMinutes, now)
            
            // Update cache
            cache[windowMinutes] = CachedStepsData(steps, System.currentTimeMillis())
            
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Fetched {$windowMinutes}min: $steps steps from Health Connect")
            steps
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error fetching steps for {$windowMinutes}min window", e)
            0
        }
    }
    
    override fun getLastUpdateMillis(): Long {
        return cache.values.maxOfOrNull { it.timestamp } ?: 0L
    }
    
    override fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        
        // Cache availability check for 30 seconds
        if (now - lastAvailabilityCheck < 30_000 && lastAvailabilityCheck > 0) {
            return cachedAvailability
        }
        
        cachedAvailability = try {
            val client = healthConnectClient ?: return false
            
            // Check if permission is granted (simplified - actual permission check happens at request time)
            runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        client.permissionController.getGrantedPermissions().isNotEmpty()
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$SOURCE_NAME] Availability check failed", e)
            false
        }
        
        lastAvailabilityCheck = now
        
        if (cachedAvailability) {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Provider available and permissions granted")
        } else {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Provider unavailable (missing permissions or Health Connect not installed)")
        }
        
        return cachedAvailability
    }
    
    override fun sourceName(): String = SOURCE_NAME
    
    override fun priority(): Int = PRIORITY
    
    /**
     * Fetches steps from Health Connect for a specific time window.
     * 
     * @param windowMinutes Duration of the window (5, 10, 15, 30, 60, 180)
     * @param now End timestamp of the window
     * @return Total step count in the window
     */
    private fun fetchStepsFromHealthConnect(windowMinutes: Int, now: Instant): Int {
        val client = healthConnectClient ?: return 0
        
        val endTime = now
        val startTime = now.minusSeconds((windowMinutes * 60).toLong())
        
        aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Querying Health Connect: $startTime to $endTime ({$windowMinutes}min)")
        
        return runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    val request = ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                    
                    val response = client.readRecords(request)
                    val totalSteps = response.records.sumOf { it.count.toInt() }
                    
                    aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Found ${response.records.size} records, total $totalSteps steps")
                    totalSteps
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error reading steps from Health Connect", e)
                    0
                }
            }
        }
    }
    
    /**
     * Clears the cache (useful for testing or manual refresh).
     */
    fun clearCache() {
        cache.clear()
        lastAvailabilityCheck = 0L
        aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Cache cleared")
    }
    
    /**
     * Internal data class for caching steps with TTL.
     */
    private data class CachedStepsData(
        val steps: Int,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
        fun ageSeconds(): Long = (System.currentTimeMillis() - timestamp) / 1000
    }
}
