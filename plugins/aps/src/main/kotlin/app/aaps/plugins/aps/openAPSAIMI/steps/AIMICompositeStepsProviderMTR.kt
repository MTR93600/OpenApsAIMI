package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔗 AIMI Composite Steps Provider - MTR Chain of Responsibility
 * 
 * Orchestrates multiple steps providers with automatic fallback strategy.
 * 
 * Priority order:
 * 1. Wear OS (if available and recent data)
 * 2. Phone Sensor (if available)  
 * 3. Health Connect (fallback for Android 14+)
 * 4. Garmin (if applicable)
 * 5. Database (last resort - historical data)
 * 
 * Selection logic:
 * - Use first available provider with non-zero data
 * - If all return 0, use highest priority available provider
 * - Log source selection for diagnostics
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
@Singleton
class AIMICompositeStepsProviderMTR @Inject constructor(
    private val healthConnectProvider: AIMIHealthConnectStepsProviderMTR,
    private val dbProvider: AIMIDatabaseStepsProviderMTR,
    private val phoneProvider: AIMIPhoneStepsProviderMTR,
    private val aapsLogger: AAPSLogger
) : AIMIStepsProviderMTR {
    
    companion object {
        private const val TAG = "CompositeStepsProvider"
    }
    
    // All providers sorted by priority (lower = higher priority)
    private val providers: List<AIMIStepsProviderMTR> by lazy {
        listOf(
            phoneProvider,          // Priority 2 (Phone sensor)
            healthConnectProvider,  // Priority 3 (Health Connect fallback)
            dbProvider              // Priority 99 (Database - last resort)
        ).sortedBy { it.priority() }
    }
    
    private var lastUsedSource: String = "None"
    private var lastSourceSelectionTime: Long = 0
    
    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int {
        val availableProviders = providers.filter { it.isAvailable() }
        
        if (availableProviders.isEmpty()) {
            aapsLogger.warn(LTag.APS, "[$TAG] No steps providers available, returning 0")
            return 0
        }
        
        // Try each provider in priority order
        for (provider in availableProviders) {
            val steps = provider.getStepsDelta(windowMinutes, now)
            
            if (steps > 0) {
                // Found data - use this provider
                updateLastSource(provider.sourceName())
                aapsLogger.debug(LTag.APS, "[$TAG] Using ${provider.sourceName()}: $steps steps ({$windowMinutes}min)")
                return steps
            }
        }
        
        // All providers returned 0 - use highest priority available
        val fallbackProvider = availableProviders.first()
        updateLastSource(fallbackProvider.sourceName())
        aapsLogger.debug(LTag.APS, "[$TAG] All providers returned 0, using ${fallbackProvider.sourceName()} (highest priority)")
        
        return 0
    }
    
    override fun getStepsPerMinute(windowMinutes: Int, now: Instant): Double {
        val delta = getStepsDelta(windowMinutes, now)
        return if (windowMinutes > 0 && delta > 0) delta.toDouble() / windowMinutes else 0.0
    }
    
    override fun getLastUpdateMillis(): Long {
        return providers
            .filter { it.isAvailable() }
            .maxOfOrNull { it.getLastUpdateMillis() } ?: 0L
    }
    
    override fun isAvailable(): Boolean {
        return providers.any { it.isAvailable() }
    }
    
    override fun sourceName(): String {
        return "Composite($lastUsedSource)"
    }
    
    override fun priority(): Int = 0 // Highest (this is the entry point)
    
    /**
     * Retrieves comprehensive steps data for all standard windows.
     * 
     * @param now Reference timestamp
     * @return Complete steps data model with metadata
     */
    fun getComprehensiveStepsData(now: Instant = Instant.now()): AIMIStepsDataMTR {
        val windows = listOf(5, 10, 15, 30, 60, 180)
        val results = windows.associateWith { window -> getStepsDelta(window, now) }
        
        return AIMIStepsDataMTR(
            steps5min = results[5] ?: 0,
            steps10min = results[10] ?: 0,
            steps15min = results[15] ?: 0,
            steps30min = results[30] ?: 0,
            steps60min = results[60] ?: 0,
            steps180min = results[180] ?: 0,
            stepsPerMinute5 = getStepsPerMinute(5, now),
            stepsPerMinute15 = getStepsPerMinute(15, now),
            stepsPerMinute30 = getStepsPerMinute(30, now),
            source = lastUsedSource,
            lastUpdateMillis = getLastUpdateMillis(),
            isValid = results.values.any { it > 0 }
        )
    }
    
    /**
     * Gets diagnostic information about all providers.
     * 
     * @return Map of provider name -> availability status
     */
    fun getProvidersStatus(): Map<String, Boolean> {
        return providers.associate { it.sourceName() to it.isAvailable() }
    }
    
    /**
     * Logs comprehensive provider status for debugging.
     */
    fun logProviderStatus() {
        val status = getProvidersStatus()
        val availableCount = status.count { it.value }
        
        aapsLogger.info(LTag.APS, "[$TAG] Provider Status ($availableCount/${status.size} available):")
        status.forEach { (name, available) ->
            val symbol = if (available) "✅" else "❌"
            aapsLogger.info(LTag.APS, "  $symbol $name")
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] Current source: $lastUsedSource (last selected ${getTimeSinceLastSelection()}s ago)")
    }
    
    private fun updateLastSource(source: String) {
        if (source != lastUsedSource) {
            aapsLogger.info(LTag.APS, "[$TAG] Steps source changed: $lastUsedSource -> $source")
            lastUsedSource = source
        }
        lastSourceSelectionTime = System.currentTimeMillis()
    }
    
    private fun getTimeSinceLastSelection(): Long {
        return if (lastSourceSelectionTime == 0L) {
            0
        } else {
            (System.currentTimeMillis() - lastSourceSelectionTime) / 1000
        }
    }
}
