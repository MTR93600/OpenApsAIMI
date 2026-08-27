package app.aaps.plugins.aps.openAPSAIMI.steps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

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
@SingleIn(AppScope::class)
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
    private val availabilityRefreshInFlight = AtomicBoolean(false)
    private val stepsRefreshInFlight = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
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
        refreshStepsAsync(windowMinutes, now)
        return cache[windowMinutes]?.steps ?: 0
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
        
        refreshAvailabilityAsync(now)

        if (cachedAvailability) {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Provider available and permissions granted")
        } else {
            aapsLogger.debug(LTag.APS, "[$SOURCE_NAME] Provider unavailable (missing permissions or Health Connect not installed)")
        }
        
        return cachedAvailability
    }
    
    override fun sourceName(): String = SOURCE_NAME
    
    override fun priority(): Int = PRIORITY

    private fun refreshAvailabilityAsync(now: Long) {
        if (!availabilityRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                cachedAvailability = directAvailabilityCheck()
                lastAvailabilityCheck = now
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$SOURCE_NAME] Availability check failed", e)
                cachedAvailability = false
            } finally {
                availabilityRefreshInFlight.set(false)
            }
        }
    }

    private suspend fun directAvailabilityCheck(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            client.permissionController.getGrantedPermissions().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshStepsAsync(windowMinutes: Int, now: Instant) {
        if (!stepsRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val client = healthConnectClient ?: return@launch
                val endTime = now
                val startTime = now.minusSeconds((windowMinutes * 60).toLong())
                val request = ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
                val response = client.readRecords(request)
                val totalSteps = response.records.sumOf { it.count.toInt() }
                cache[windowMinutes] = CachedStepsData(totalSteps, System.currentTimeMillis())
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$SOURCE_NAME] Error fetching steps for {$windowMinutes}min window", e)
            } finally {
                stepsRefreshInFlight.set(false)
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
