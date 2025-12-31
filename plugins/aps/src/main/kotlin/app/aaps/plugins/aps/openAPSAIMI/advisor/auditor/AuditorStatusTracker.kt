package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

/**
 * AI Auditor Status Tracking
 * 
 * Provides explicit, traceable status for AI Auditor instead of vague "OFFLINE"
 * 
 * Status Machine:
 * 1. OFF → Preference disabled
 * 2. SKIPPED_* → Enabled but not eligible this tick
 * 3. OFFLINE_* → Enabled + eligible but can't reach AI
 * 4. ERROR_* → Enabled + attempted but failed
 * 5. OK → Enabled + verdict received
 */
object AuditorStatusTracker {
    
    @Volatile
    private var currentStatus: Status = Status.OFF
    
    @Volatile
    private var lastUpdateMs: Long = 0L
    
    enum class Status(val message: String) {
        // Disabled
        OFF("OFF"),
        
        // Skipped (eligible but deliberately not calling AI)
        SKIPPED_NO_TRIGGER("SKIPPED_NO_TRIGGER"),
        SKIPPED_RATE_LIMITED("SKIPPED_RATE_LIMITED"),
        SKIPPED_PREBOLUS_WINDOW("SKIPPED_PREBOLUS_WINDOW"),
        SKIPPED_COOLDOWN("SKIPPED_COOLDOWN"),
        
        // Offline (can't reach AI)
        OFFLINE_NO_APIKEY("OFFLINE_NO_APIKEY"),
        OFFLINE_NO_NETWORK("OFFLINE_NO_NETWORK"),
        OFFLINE_NO_ENDPOINT("OFFLINE_NO_ENDPOINT"),
        OFFLINE_DNS_FAIL("OFFLINE_DNS_FAIL"),
        
        // Error (attempted but failed)
        ERROR_TIMEOUT("ERROR_TIMEOUT"),
        ERROR_PARSE("ERROR_PARSE"),
        ERROR_HTTP("ERROR_HTTP"),
        ERROR_EXCEPTION("ERROR_EXCEPTION"),
        
        // Active (verdict received)
        OK_CONFIRM("OK_CONFIRM"),
        OK_SOFTEN("OK_SOFTEN"),
        OK_REDUCE("OK_REDUCE"),
        OK_INCREASE_INTERVAL("OK_INCREASE_INTERVAL"),
        OK_PREFER_TBR("OK_PREFER_TBR"),
        
        // Stale
        STALE("STALE");
        
        fun isActive(): Boolean = name.startsWith("OK_")
        fun isError(): Boolean = name.startsWith("ERROR_")
        fun isOffline(): Boolean = name.startsWith("OFFLINE_")
        fun isSkipped(): Boolean = name.startsWith("SKIPPED_")
    }
    
    /**
     * Update current status
     */
    fun updateStatus(status: Status) {
        currentStatus = status
        lastUpdateMs = System.currentTimeMillis()
    }
    
    /**
     * Get current status with age check
     */
    fun getStatus(maxAgeMs: Long = 300_000): Pair<Status, Long?> {
        if (lastUpdateMs == 0L) {
            return Pair(Status.OFF, null)
        }
        
        val ageMs = System.currentTimeMillis() - lastUpdateMs
        if (ageMs > maxAgeMs) {
            return Pair(Status.STALE, ageMs)
        }
        
        return Pair(currentStatus, ageMs)
    }
    
    /**
     * Get detailed status message for rT
     */
    fun getDetailedMessage(): String {
        val (status, ageMs) = getStatus()
        
        return when {
            status == Status.STALE && ageMs != null -> {
                val ageMin = (ageMs / 60_000).toInt()
                "Auditor: STALE (${ageMin}m old, last=${currentStatus.message})"
            }
            status.isActive() -> {
                // Will be built by buildAuditorLine using cache
                "Auditor: ${status.message}"
            }
            else -> {
                "Auditor: ${status.message}"
            }
        }
    }
    
    /**
     * Reset to initial state
     */
    fun reset() {
        currentStatus = Status.OFF
        lastUpdateMs = 0L
    }
}
