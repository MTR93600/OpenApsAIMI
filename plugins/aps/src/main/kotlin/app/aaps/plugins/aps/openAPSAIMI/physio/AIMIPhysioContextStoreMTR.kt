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
    @Volatile
    private var currentContext: PhysioContextMTR? = null
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
     * Updates current context and persists to disk
     * 
     * @param context New physiological context
     * @param baseline Updated baseline (optional)
     */
    fun updateContext(context: PhysioContextMTR, baseline: PhysioBaselineMTR? = null) {
        lock.write {
            currentContext = context
            if (baseline != null) {
                currentBaseline = baseline
            }
            lastUpdate = System.currentTimeMillis()
            
            // Persist to disk
            try {
                saveToDisk(context, baseline ?: currentBaseline)
                aapsLogger.info(
                    LTag.APS,
                    "[$TAG] ✅ Context updated and saved (state=${context.state}, confidence=${(context.confidence * 100).toInt()}%)"
                )
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Failed to save to disk", e)
            }
        }
    }
    
    /**
     * Gets current context (thread-safe read)
     * Returns null if context is invalid or expired
     * 
     * @return PhysioContextMTR or null
     */
    fun getCurrentContext(): PhysioContextMTR? = lock.read {
        val context = currentContext
        
        if (context == null) {
            aapsLogger.debug(LTag.APS, "[$TAG] No context available")
            return null
        }
        
        if (!context.isValid()) {
            aapsLogger.debug(LTag.APS, "[$TAG] Context expired (age=${context.ageSeconds()}s)")
            return null
        }
        
        val age = (System.currentTimeMillis() - lastUpdate) / 1000
        if (age > VALIDITY_MS / 1000) {
            aapsLogger.debug(LTag.APS, "[$TAG] Context too old (${age / 3600}h)")
            return null
        }
        
        context
    }
    
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
     * 
     * @return true if usable, false otherwise
     */
    fun isValid(): Boolean = lock.read {
        val context = currentContext ?: return@read false
        val age = System.currentTimeMillis() - lastUpdate
        
        context.isValid() && age < VALIDITY_MS
    }
    
    /**
     * Clears all stored data
     */
    fun clear() {
        lock.write {
            currentContext = null
            currentBaseline = null
            lastUpdate = 0
            
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
     * Saves context and baseline to JSON file
     */
    private fun saveToDisk(context: PhysioContextMTR, baseline: PhysioBaselineMTR?) {
        val json = JSONObject().apply {
            put("version", 1)
            put("lastUpdate", lastUpdate)
            put("context", context.toJSON())
            if (baseline != null) {
                put("baseline", baseline.toJSON())
            }
        }
        
        storageFile.writeText(json.toString(2)) // Pretty print with indent=2
        
        aapsLogger.debug(LTag.APS, "[$TAG] Saved to ${storageFile.absolutePath} (${storageFile.length()} bytes)")
    }
    
    /**
     * Restores context and baseline from JSON file
     */
    private fun restoreFromDisk() {
        if (!storageFile.exists()) {
            aapsLogger.debug(LTag.APS, "[$TAG] No saved context found")
            return
        }
        
        try {
            val json = JSONObject(storageFile.readText())
            val version = json.optInt("version", 0)
            
            if (version != 1) {
                aapsLogger.warn(LTag.APS, "[$TAG] Unsupported version: $version")
                return
            }
            
            lock.write {
                lastUpdate = json.optLong("lastUpdate", 0)
                
                json.optJSONObject("context")?.let { contextJson ->
                    currentContext = PhysioContextMTR.fromJSON(contextJson)
                }
                
                json.optJSONObject("baseline")?.let { baselineJson ->
                    currentBaseline = PhysioBaselineMTR.fromJSON(baselineJson)
                }
            }
            
            val age = (System.currentTimeMillis() - lastUpdate) / (60 * 60 * 1000)
            
            if (currentContext != null) {
                aapsLogger.info(
                    LTag.APS,
                    "[$TAG] ✅ Context restored from disk " +
                    "(state=${currentContext?.state}, age=${age}h, " +
                    "baseline=${if (currentBaseline != null) "${currentBaseline!!.validDaysCount}d" else "none"})"
                )
            }
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to restore from disk", e)
            // Don't crash - just clear corrupted data
            lock.write {
                currentContext = null
                currentBaseline = null
                lastUpdate = 0
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
            "hasContext" to (currentContext != null).toString(),
            "hasBaseline" to (currentBaseline != null).toString(),
            "contextState" to (currentContext?.state?.name ?: "NONE"),
            "contextAge" to "${(System.currentTimeMillis() - lastUpdate) / (60 * 60 * 1000)}h",
            "confidence" to "${((currentContext?.confidence ?: 0.0) * 100).toInt()}%",
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
