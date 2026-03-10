package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AIMI Auditor Status LiveData
 * 
 * Reactive layer transforming AuditorStatusTracker.Status → AuditorUIState
 * Observé par MainActivity pour mettre à jour l'indicateur toolbar
 * 
 * Architecture:
 * AuditorAIService → AuditorStatusTracker → AuditorStatusLiveData → MainActivity
 */
@Singleton
class AuditorStatusLiveData @Inject constructor() {
    
    private val _uiState = MutableLiveData<AuditorUIState>(AuditorUIState.idle())
    val uiState: LiveData<AuditorUIState> = _uiState
    
    @Volatile
    private var lastNotifiedTimestamp: Long = 0L
    
    @Volatile
    private var lastReadTimestamp: Long = 0L
    
    /**
     * Notify update from AuditorStatusTracker
     * Should be called after AuditorStatusTracker.updateStatus()
     */
    fun notifyUpdate() {
        val (status, ageMs) = AuditorStatusTracker.getStatus()
        val newState = transformStatusToUIState(status, ageMs)
        
        // Post to main thread
        _uiState.postValue(newState)
    }
    
    /**
     * Transform AuditorStatusTracker.Status to AuditorUIState
     */
    private fun transformStatusToUIState(
        status: AuditorStatusTracker.Status,
        ageMs: Long?
    ): AuditorUIState {
        
        // Check if stale (>5 minutes old)
        if (ageMs != null && ageMs > 300_000) {
            return AuditorUIState.idle()
        }
        
        return when {
            // IDLE states
            status == AuditorStatusTracker.Status.OFF -> {
                AuditorUIState.idle()
            }
            
            // PROCESSING state (never actually set currently, but ready for future)
            status.name.contains("PROCESSING") -> {
                AuditorUIState.processing()
            }
            
            // SKIPPED states → IDLE
            status.isSkipped() -> {
                AuditorUIState.idle()
            }
            
            // OFFLINE states → ERROR
            status.isOffline() -> {
                AuditorUIState.error(getOfflineMessage(status))
            }
            
            // ERROR states → ERROR
            status.isError() -> {
                AuditorUIState.error(getErrorMessage(status))
            }
            
            // ACTIVE states (OK_*) → READY or WARNING
            status.isActive() -> {
                val insightCount = getUnreadInsightCount()
                val shouldNotify = shouldNotifyUser()
                
                // Determine if warning based on verdict type
                when (status) {
                    AuditorStatusTracker.Status.OK_REDUCE,
                    AuditorStatusTracker.Status.OK_SOFTEN -> {
                        // Important recommendations → WARNING
                        AuditorUIState.warning("Important: ${status.message}")
                    }
                    else -> {
                        // Normal insights → READY
                        AuditorUIState.ready(insightCount, shouldNotify)
                    }
                }
            }
            
            // Default fallback
            else -> AuditorUIState.idle()
        }
    }
    
    /**
     * Get number of unread insights from cache
     * TODO: Implement proper logic using AuditorVerdictCache.get() when needed
     * Example: val cached = AuditorVerdictCache.get(); count = cached?.verdict?.recommendations?.size ?: 1
     */
    private fun getUnreadInsightCount(): Int {
        // Placeholder: return 1 if status is active
        // AuditorVerdictCache is a singleton object, accessible via AuditorVerdictCache.get()
        return 1
    }
    
    /**
     * Check if should notify user (not too frequent)
     */
    private fun shouldNotifyUser(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastNotif = now - lastNotifiedTimestamp
        
        // Don't notify more than once per 30 minutes
        if (timeSinceLastNotif < 30 * 60 * 1000) {
            return false
        }
        
        // Update last notified timestamp
        lastNotifiedTimestamp = now
        return true
    }
    
    /**
     * Mark insights as read (clears badge)
     */
    fun markAsRead() {
        lastReadTimestamp = System.currentTimeMillis()
        notifyUpdate()  // Refresh UI to update badge
    }
    
    /**
     * Get offline message based on status
     */
    private fun getOfflineMessage(status: AuditorStatusTracker.Status): String {
        return when (status) {
            AuditorStatusTracker.Status.OFFLINE_NO_APIKEY -> "No API key configured"
            AuditorStatusTracker.Status.OFFLINE_NO_NETWORK -> "No network connection"
            AuditorStatusTracker.Status.OFFLINE_NO_ENDPOINT -> "API endpoint unavailable"
            AuditorStatusTracker.Status.OFFLINE_DNS_FAIL -> "DNS resolution failed"
            else -> "Offline"
        }
    }
    
    /**
     * Get error message based on status
     */
    private fun getErrorMessage(status: AuditorStatusTracker.Status): String {
        return when (status) {
            AuditorStatusTracker.Status.ERROR_TIMEOUT -> "Request timeout"
            AuditorStatusTracker.Status.ERROR_PARSE -> "Parse error"
            AuditorStatusTracker.Status.ERROR_HTTP -> "HTTP error"
            AuditorStatusTracker.Status.ERROR_EXCEPTION -> "Exception occurred"
            else -> "Error"
        }
    }
    
    /**
     * Force update (for manual refresh)
     */
    fun forceUpdate() {
        notifyUpdate()
    }
    
    /**
     * Reset state (for cleanup)
     */
    fun reset() {
        lastNotifiedTimestamp = 0L
        lastReadTimestamp = 0L
        _uiState.postValue(AuditorUIState.idle())
    }
}
