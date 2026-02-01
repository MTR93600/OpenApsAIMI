package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * 📊 Health Connect Fetch Outcome
 * 
 * Distinguishes between different fetch results to avoid confusing
 * "no data available" with "fetch failed"
 */
enum class FetchOutcome {
    /** Successfully fetched data (records > 0) */
    SUCCESS,
    
    /** Query succeeded but returned 0 records (not an error!) */
    NO_DATA,
    
    /** SecurityException - permission not granted */
    SECURITY_ERROR,
    
    /** General exception during fetch */
    ERROR,
    
    /** Health Connect client unavailable */
    UNAVAILABLE
}

/**
 * 🔍 Health Connect Probe Result
 * 
 * Diagnostic information about Health Connect state and available data
 */
data class ProbeResult(
    val sdkStatus: String,
    val grantedPermissions: Set<String>,
    val sleepCount: Int,
    val hrvCount: Int,
    val heartRateCount: Int,
    val stepsCount: Int,
    val dataOrigins: Set<String>,
    val windowDays: Int,
    val probeTimestamp: Long = System.currentTimeMillis()
) {
    fun hasAnyData(): Boolean = sleepCount > 0 || hrvCount > 0 || heartRateCount > 0 || stepsCount > 0
    
    fun toLogString(): String {
        return "HC Probe (${windowDays}d): Sleep=$sleepCount HRV=$hrvCount HR=$heartRateCount Steps=$stepsCount | Writers=${dataOrigins.joinToString(",")}"
    }
}

/**
 * 🎯 Pipeline Run Outcome
 * 
 * Overall result of a physiological analysis pipeline run
 */
enum class PhysioPipelineOutcome {
    /** Never executed */
    NEVER_RUN,
    
    /** Health Connect synced OK but returned zero data for all metrics */
    SYNC_OK_NO_DATA,
    
    /** Partial data available (e.g., Steps/HR but not Sleep/HRV) */
    SYNC_PARTIAL,
    
    /** Full data available, context computed */
    READY,
    
    /** Security/permission error */
    SECURITY_ERROR,
    
    /** General error during pipeline */
    ERROR
}
