package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 💾 AIMI Physiological Context Store - MTR Implementation
 * 
 * Thread-safe storage for physiological context with dual persistence:
 * - In-memory: Volatile, fast access for loop execution
 * - JSON file: Persistent storage with 15-20h validity
 * 
 * Storage Location: /storage/emulated/0/Documents/AAPS/physio_context.json
 * 
 * Lifecycle:
 * - Updated every 6 hours by PhysioManager
 * - Auto-restored on app start
 * - Invalidated after 20 hours
 * 
 * Thread Safety: All operations use ReentrantReadWriteLock
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioContextStoreMTR @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioContextStore"
        private const val FILENAME = "physio_context.json"
        private const val VALIDITY_HOURS = 20
        private const val VALIDITY_MS = VALIDITY_HOURS * 60 * 60 * 1000L
    }
    
    // Thread-safe storage
    private val lock = ReentrantReadWriteLock()
    
    // OUTCOME TRACKING (NEW)
    @Volatile
    private var lastRunOutcome: PhysioPipelineOutcome = PhysioPipelineOutcome.NEVER_RUN
    @Volatile
    private var lastRunTimestamp: Long = 0
    @Volatile
    private var lastProbeResult: ProbeResult? = null
    
    // CONTEXT STORAGE
    @Volatile
    private var lastContextUnsafe: PhysioContextMTR? = null  // Always available if any run succeeded
    @Volatile
    private var currentBaseline: PhysioBaselineMTR? = null
    @Volatile
    private var lastUpdate: Long = 0
    
    // Storage directory
    private val storageDir: File by lazy {
        val dir = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Documents/AAPS"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }
    
    private val storageFile: File by lazy {
        File(storageDir, FILENAME)
    }
    
    init {
        // Auto-restore on initialization
        try {
            restoreFromDisk()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to restore from disk", e)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CONTEXT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Updates current context WITH outcome tracking
     * 
     * @param context New physiological context (even if low confidence)
     * @param baseline Updated baseline (optional)
     * @param outcome Pipeline outcome (determines UI messaging)
     * @param probeResult Diagnostic probe result (optional)
     */
    fun updateContext(
        context: PhysioContextMTR, 
        baseline: PhysioBaselineMTR? = null,
        outcome: PhysioPipelineOutcome = PhysioPipelineOutcome.READY,
        probeResult: ProbeResult? = null
    ) {
        lock.write {
            lastContextUnsafe = context
            if (baseline != null) {
                currentBaseline = baseline
            }
            lastUpdate = System.currentTimeMillis()
            lastRunOutcome = outcome
            lastRunTimestamp = System.currentTimeMillis()
            if (probeResult != null) {
                lastProbeResult = probeResult
            }
            
            // Persist to disk
            try {
                saveToDisk(context, baseline ?: currentBaseline, outcome, probeResult)
                aapsLogger.info(
                    LTag.APS,
                    "[$TAG] ✅ Context updated (outcome=$outcome, state=${context.state}, conf=${(context.confidence * 100).toInt()}%)"
                )
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Failed to save to disk", e)
            }
        }
    }
    
    /**
     * Gets last context UNSAFE (always returns context if any run succeeded)
     * Use this for UI/logging, NOT for applying multipliers
     * 
     * @return PhysioContextMTR or null if NEVER_RUN
     */
    fun getLastContextUnsafe(): PhysioContextMTR? = lock.read {
        lastContextUnsafe
    }
    
    /**
     * Gets EFFECTIVE context (only if confidence >= threshold)
     * Use this for applying multipliers/modulations
     * 
     * @param minConfidence Minimum confidence threshold (default 0.5)
     * @return PhysioContextMTR or null
     */
    fun getEffectiveContext(minConfidence: Double = 0.5): PhysioContextMTR? = lock.read {
        val context = lastContextUnsafe
        
        if (context == null) {
            return@read null
        }
        
        if (context.confidence < minConfidence) {
            aapsLogger.debug(LTag.APS, "[$TAG] Context confidence too low (${(context.confidence * 100).toInt()}% < ${(minConfidence * 100).toInt()}%)")
            return@read null
        }
        
        // Check age
        val age = (System.currentTimeMillis() - lastUpdate) / 1000
        if (age > VALIDITY_MS / 1000) {
            aapsLogger.debug(LTag.APS, "[$TAG] Context too old (${age / 3600}h)")
            return@read null
        }
        
        context
    }
    
    /**
     * Gets last pipeline run outcome
     */
    fun getLastRunOutcome(): PhysioPipelineOutcome = lock.read {
        lastRunOutcome
    }
    
    /**
     * Gets last probe result
     */
    fun getLastProbeResult(): ProbeResult? = lock.read {
        lastProbeResult
    }
    
    /**
     * Gets current context (DEPRECATED - use getLastContextUnsafe or getEffectiveContext)
     * Kept for compatibility
     */
    @Deprecated("Use getLastContextUnsafe() or getEffectiveContext() instead")
    fun getCurrentContext(): PhysioContextMTR? = getEffectiveContext(0.3)
    
    /**
     * Gets current baseline
     * 
     * @return PhysioBaselineMTR or null
     */
    fun getCurrentBaseline(): PhysioBaselineMTR? = lock.read {
        currentBaseline
    }
    
    /**
     * Checks if context is valid and fresh
     * NOTE: This checks EFFECTIVE context (with confidence threshold)
     * 
     * @return true if usable, false otherwise
     */
    fun isValid(): Boolean = lock.read {
        getEffectiveContext(0.5) != null
    }
    
    /**
     * Clears all stored data
     */
    fun clear() {
        lock.write {
            lastContextUnsafe = null
            currentBaseline = null
            lastUpdate = 0
            lastRunOutcome = PhysioPipelineOutcome.NEVER_RUN
            lastRunTimestamp = 0
            lastProbeResult = null
            
            if (storageFile.exists()) {
                storageFile.delete()
            }
            
            aapsLogger.info(LTag.APS, "[$TAG] Context cleared")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // DISK PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Saves context and baseline to JSON file (WITH OUTCOME TRACKING)
     */
    private fun saveToDisk(
        context: PhysioContextMTR, 
        baseline: PhysioBaselineMTR?,
        outcome: PhysioPipelineOutcome,
        probeResult: ProbeResult?
    ) {
        val json = JSONObject().apply {
            put("version", 2) // Bumped version for new schema
            put("lastUpdate", lastUpdate)
            put("lastRunOutcome", outcome.name)
            put("lastRunTimestamp", lastRunTimestamp)
            put("context", context.toJSON())
            if (baseline != null) {
                put("baseline", baseline.toJSON())
            }
            if (probeResult != null) {
                put("probeResult", JSONObject().apply {
                    put("sdkStatus", probeResult.sdkStatus)
                    put("sleepCount", probeResult.sleepCount)
                    put("hrvCount", probeResult.hrvCount)
                    put("heartRateCount", probeResult.heartRateCount)
                    put("stepsCount", probeResult.stepsCount)
                    put("dataOrigins", JSONObject().apply {
                        probeResult.dataOrigins.forEachIndexed { i, origin ->
                            put("writer_$i", origin)
                        }
                    })
                    put("windowDays", probeResult.windowDays)
                })
            }
        }
        
        try {
            // 📊 LOG 1: Path absolu avant écriture
            aapsLogger.info(LTag.APS, "[$TAG] 💾 PhysioStore: writing to ${storageFile.absolutePath}")
            
            val jsonString = json.toString(2) // Pretty print with indent=2
            storageFile.writeText(jsonString)
            
            // 📊 LOG 2: Confirmation écriture avec taille
            val writtenBytes = jsonString.toByteArray().size
            aapsLogger.info(LTag.APS, "[$TAG] ✅ PhysioStore: written bytes=$writtenBytes")
            
            // 📊 LOG 3: Verification fichier après écriture  
            val exists = storageFile.exists()
            val size = if (exists) storageFile.length() else 0
            val canRead = storageFile.canRead()
            val canWrite = storageFile.canWrite()
            
            aapsLogger.info(LTag.APS, "[$TAG] 🔍 PhysioStore: exists=$exists size=$size canRead=$canRead canWrite=$canWrite")
            
            if (!exists || size == 0L) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ PhysioStore: WRITE FAILED! File not created or empty")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ PhysioStore: Save exception: ${e.message}", e)
        }
        
        aapsLogger.debug(LTag.APS, "[$TAG] Saved to ${storageFile.absolutePath} (${storageFile.length()} bytes)")
    }
    
    /**
     * Restores context and baseline from JSON file (WITH OUTCOME TRACKING)
     */
    private fun restoreFromDisk() {
        if (!storageFile.exists()) {
            aapsLogger.debug(LTag.APS, "[$TAG] No saved context found")
            return
        }
        
        try {
            val json = JSONObject(storageFile.readText())
            val version = json.optInt("version", 0)
            
            if (version < 1) {
                aapsLogger.warn(LTag.APS, "[$TAG] Unsupported version: $version")
                return
            }
            
            lock.write {
                lastUpdate = json.optLong("lastUpdate", 0)
                
                // Restore outcome tracking (v2+)
                if (version >= 2) {
                    val outcomeStr = json.optString("lastRunOutcome", "NEVER_RUN")
                    lastRunOutcome = try {
                        PhysioPipelineOutcome.valueOf(outcomeStr)
                    } catch (e: Exception) {
                        PhysioPipelineOutcome.NEVER_RUN
                    }
                    lastRunTimestamp = json.optLong("lastRunTimestamp", 0)
                }
                
                json.optJSONObject("context")?.let { contextJson ->
                    lastContextUnsafe = PhysioContextMTR.fromJSON(contextJson)
                }
                
                json.optJSONObject("baseline")?.let { baselineJson ->
                    currentBaseline = PhysioBaselineMTR.fromJSON(baselineJson)
                }
            }
            
            val age = (System.currentTimeMillis() - lastUpdate) / (60 * 60 * 1000)
            
            if (lastContextUnsafe != null) {
                aapsLogger.info(
                    LTag.APS,
                    "[$TAG] ✅ Context restored (outcome=$lastRunOutcome, state=${lastContextUnsafe?.state}, age=${age}h)"
                )
            }
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to restore from disk", e)
            // Don't crash - just clear corrupted data
            lock.write {
                lastContextUnsafe = null
                currentBaseline = null
                lastUpdate = 0
                lastRunOutcome = PhysioPipelineOutcome.NEVER_RUN
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STATUS & DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Gets storage status for debugging/UI
     * 
     * @return Map of status information
     */
    fun getStatus(): Map<String, String> = lock.read {
        mapOf(
            "hasContext" to (lastContextUnsafe != null).toString(),
            "hasBaseline" to (currentBaseline != null).toString(),
            "lastRunOutcome" to lastRunOutcome.name,
            "contextState" to (lastContextUnsafe?.state?.name ?: "NONE"),
            "contextAge" to "${(System.currentTimeMillis() - lastUpdate) / (60 * 60 * 1000)}h",
            "confidence" to "${((lastContextUnsafe?.confidence ?: 0.0) * 100).toInt()}%",
            "baselineDays" to (currentBaseline?.validDaysCount ?: 0).toString(),
            "isValid" to isValid().toString(),
            "fileExists" to storageFile.exists().toString(),
            "fileSize" to "${storageFile.length()} bytes",
            "filePath" to storageFile.absolutePath
        )
    }
    
    /**
     * Logs current storage status
     */
    fun logStatus() {
        val status = getStatus()
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.APS, "[$TAG] Physio Context Store Status:")
        status.forEach { (key, value) ->
            aapsLogger.info(LTag.APS, "[$TAG]   $key: $value")
        }
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
    }
    
    /**
     * Forces a refresh by re-reading from disk
     * Useful for testing or debugging
     */
    fun forceRefresh() {
        aapsLogger.info(LTag.APS, "[$TAG] Force refresh requested")
        restoreFromDisk()
    }
}
