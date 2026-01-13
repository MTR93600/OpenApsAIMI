package app.aaps.plugins.aps.openAPSAIMI.steps

import java.time.Instant

/**
 * 🏗️ AIMI Steps Provider Interface - MTR Architecture
 * 
 * Abstraction unifiée pour toutes les sources de pas (steps).  
 * Permet de brancher différents providers (Wear OS, Phone, Health Connect, Garmin)
 * avec une stratégie de fallback automatique via CompositeStepsProvider.
 *
 * Architecture:
 * - WearStepsProvider (priorité 1)
 * - PhoneStepsProvider (priorité 2)  
 * - HealthConnectStepsProviderMTR (priorité 3 - fallback)
 * - GarminStepsProvider (priorité 4 - si applicable)
 *
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
interface AIMIStepsProviderMTR {
    
    /**
     * Retrieves step count delta for a given time window.
     * 
     * @param windowMinutes Duration of the measurement window (5, 10, 15, 30, 60, 180)
     * @param now Reference timestamp (end of window)
     * @return Number of steps in the window, or 0 if unavailable
     */
    fun getStepsDelta(windowMinutes: Int, now: Instant = Instant.now()): Int
    
    /**
     * Retrieves steps per minute rate for a given time window.
     * 
     * @param windowMinutes Duration of the measurement window  
     * @param now Reference timestamp (end of window)
     * @return Steps per minute, or 0.0 if unavailable
     */
    fun getStepsPerMinute(windowMinutes: Int, now: Instant = Instant.now()): Double {
        val delta = getStepsDelta(windowMinutes, now)
        return if (windowMinutes > 0 && delta > 0) delta.toDouble() / windowMinutes else 0.0
    }
    
    /**
     * Timestamp of the last successful data retrieval.
     * 
     * @return Epoch millis of last update, or 0 if never updated
     */
    fun getLastUpdateMillis(): Long
    
    /**
     * Checks if this provider is currently available and can provide data.
     * 
     * @return true if available, false otherwise (permissions denied, service unavailable, etc.)
     */
    fun isAvailable(): Boolean
    
    /**
     * Unique identifier for this provider source.
     * 
     * @return Source name (e.g., "WearOS", "Phone", "HealthConnect", "Garmin")
     */
    fun sourceName(): String
    
    /**
     * Priority level for the chain of responsibility pattern.
     * Lower values = higher priority.
     * 
     * @return Priority value (1 = highest)
     */
    fun priority(): Int
    
    /**
     * Optional: Retrieves detailed steps data for multiple windows at once.
     * Default implementation calls getStepsDelta() for each window.
     * 
     * @param windowsMinutes List of window durations to query
     * @param now Reference timestamp
     * @return Map of window -> step count
     */
    fun getStepsBatch(windowsMinutes: List<Int>, now: Instant = Instant.now()): Map<Int, Int> {
        return windowsMinutes.associateWith { window -> getStepsDelta(window, now) }
    }
}

/**
 * 📊 Steps Data Model - Comprehensive result with metadata
 */
data class AIMIStepsDataMTR(
    val steps5min: Int = 0,
    val steps10min: Int = 0,
    val steps15min: Int = 0,
    val steps30min: Int = 0,
    val steps60min: Int = 0,
    val steps180min: Int = 0,
    val stepsPerMinute5: Double = 0.0,
    val stepsPerMinute15: Double = 0.0,
    val stepsPerMinute30: Double = 0.0,
    val source: String = "Unknown",
    val lastUpdateMillis: Long = 0,
    val isValid: Boolean = false
) {
    companion object {
        val EMPTY = AIMIStepsDataMTR()
    }
}
