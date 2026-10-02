package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStudyLocations
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.Volatile
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 💾 AIMI Physiological Context Store - MTR Implementation
 *
 * Thread-safe storage for physiological context with dual persistence:
 * - In-memory: Volatile, fast access for loop execution
 * - JSON file: Persistent storage with 15-20h validity
 *
 * Storage Location: the first entry of [AimiStudyLocations.studyDirectories], which on Android is the
 * shared `Documents/AAPS` folder this store has always written to.
 *
 * Lifecycle:
 * - Updated every 6 hours by PhysioManager
 * - Auto-restored on app start
 * - Invalidated after 20 hours
 *
 * Thread Safety: all operations hold one [AapsLock].
 *
 * ## Why one lock and not a read/write pair
 *
 * This used a `ReentrantReadWriteLock`, which does not exist outside the JVM, and [AapsLock] is a
 * single reentrant lock. That is a real narrowing - two readers now wait for each other - and it is
 * safe here only because of what the guarded regions contain. Every read holds the lock for a handful
 * of field reads, and the two that touch the file system (`getStatus`, and the `exists` inside
 * `clear`) only ask the platform to stat a file. Nothing reads under the lock for long enough for the
 * difference to be observable on a five-minute loop. The one long region, the JSON write inside
 * [updateContext], was already exclusive and blocked every reader before this change too.
 *
 * Reentrancy still matters and is still there: `getStatus` takes the lock and calls `isValid`, which
 * takes it again and calls `getEffectiveContext`, which takes it a third time. [AapsLock] is
 * reentrant on every target by contract, so that nesting is as safe as it was.
 *
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@SingleIn(AppScope::class)
class AIMIPhysioContextStoreMTR @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storage: AimiStorage,
    private val studyLocations: AimiStudyLocations
) {

    companion object {
        private const val TAG = "PhysioContextStore"
        private const val FILENAME = "physio_context.json"
        private const val VALIDITY_HOURS = 20
        private const val VALIDITY_MS = VALIDITY_HOURS * 60 * 60 * 1000L
    }

    // Thread-safe storage
    private val lock = AapsLock()
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

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

    /**
     * Storage directory: the first study location, created if it is not there.
     *
     * On Android that is `Documents/AAPS`, exactly the folder built inline here before, so an install
     * that upgrades finds its own `physio_context.json` where it left it. [AimiStorage.directory] is
     * deliberately NOT the fallback of choice: it applies the three-tier write policy and can answer
     * with a different folder, which would hide an existing file. It is only reached when the platform
     * names no study location at all, which on Android it always does.
     */
    private val storageDir: AimiPath by lazy {
        val dir = studyLocations.studyDirectories().firstOrNull() ?: storage.directory()
        storage.createDirectories(dir)
        dir
    }

    private val storageFile: AimiPath by lazy {
        storage.resolve(storageDir, FILENAME)
    }

    init {
        // Auto-restore on initialization
        try {
            restoreFromDisk()
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$TAG] Failed to restore from disk", e)
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
        lock.withLock {
            lastContextUnsafe = context
            if (baseline != null) {
                currentBaseline = baseline
            }
            lastUpdate = aimiWallClockMs()
            lastRunOutcome = outcome
            lastRunTimestamp = aimiWallClockMs()
            if (probeResult != null) {
                lastProbeResult = probeResult
            }

            // Persist to disk
            try {
                saveToDisk(context, baseline ?: currentBaseline, outcome, probeResult)
                aapsLogger.info(
                    LTag.AIMI,
                    "[$TAG] ✅ Context updated (outcome=$outcome, state=${context.state}, conf=${(context.confidence * 100).toInt()}%)"
                )
            } catch (e: Exception) {
                aapsLogger.error(LTag.AIMI, "[$TAG] Failed to save to disk", e)
            }
        }
    }

    /**
     * Gets last context UNSAFE (always returns context if any run succeeded)
     * Use this for UI/logging, NOT for applying multipliers
     *
     * @return PhysioContextMTR or null if NEVER_RUN
     */
    fun getLastContextUnsafe(): PhysioContextMTR? = lock.withLock {
        lastContextUnsafe
    }

    /**
     * Gets EFFECTIVE context (only if confidence >= threshold)
     * Use this for applying multipliers/modulations
     *
     * @param minConfidence Minimum confidence threshold (default 0.5)
     * @return PhysioContextMTR or null
     */
    fun getEffectiveContext(minConfidence: Double = 0.5): PhysioContextMTR? = lock.withLock {
        val context = lastContextUnsafe

        if (context == null) {
            return@withLock null
        }

        if (context.confidence < minConfidence) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] Context confidence too low (${(context.confidence * 100).toInt()}% < ${(minConfidence * 100).toInt()}%)")
            return@withLock null
        }

        // Check age
        val age = (aimiWallClockMs() - lastUpdate) / 1000
        if (age > VALIDITY_MS / 1000) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] Context too old (${age / 3600}h)")
            return@withLock null
        }

        context
    }

    /**
     * Gets last pipeline run outcome
     */
    fun getLastRunOutcome(): PhysioPipelineOutcome = lock.withLock {
        lastRunOutcome
    }

    /**
     * Gets last probe result
     */
    fun getLastProbeResult(): ProbeResult? = lock.withLock {
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
    fun getCurrentBaseline(): PhysioBaselineMTR? = lock.withLock {
        currentBaseline
    }

    /**
     * Checks if context is valid and fresh
     * NOTE: This checks EFFECTIVE context (with confidence threshold)
     *
     * @return true if usable, false otherwise
     */
    fun isValid(): Boolean = lock.withLock {
        getEffectiveContext(0.5) != null
    }

    /**
     * Clears all stored data
     */
    fun clear() {
        lock.withLock {
            lastContextUnsafe = null
            currentBaseline = null
            lastUpdate = 0
            lastRunOutcome = PhysioPipelineOutcome.NEVER_RUN
            lastRunTimestamp = 0
            lastProbeResult = null

            if (storage.exists(storageFile)) {
                storage.delete(storageFile)
            }

            aapsLogger.info(LTag.AIMI, "[$TAG] Context cleared")
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
        val json = buildJsonObject {
            put("version", 2) // Bumped version for new schema
            put("lastUpdate", lastUpdate)
            put("lastRunOutcome", outcome.name)
            put("lastRunTimestamp", lastRunTimestamp)
            put("context", context.toJSON())
            if (baseline != null) {
                put("baseline", baseline.toJSON())
            }
            if (probeResult != null) {
                put(
                    "probeResult",
                    buildJsonObject {
                        put("sdkStatus", probeResult.sdkStatus)
                        put("sleepCount", probeResult.sleepCount)
                        put("hrvCount", probeResult.hrvCount)
                        put("heartRateCount", probeResult.heartRateCount)
                        put("stepsCount", probeResult.stepsCount)
                        put(
                            "dataOrigins",
                            buildJsonObject {
                                probeResult.dataOrigins.forEachIndexed { i, origin ->
                                    put("writer_$i", origin)
                                }
                            }
                        )
                        put("windowDays", probeResult.windowDays)
                    }
                )
            }
        }

        try {
            // 📊 LOG 1: Path absolu avant écriture
            aapsLogger.info(LTag.AIMI, "[$TAG] 💾 PhysioStore: writing to ${storage.displayPath(storageFile)}")

            val jsonString = prettyJson.encodeToString(serializer<JsonElement>(), json)
            if (!storage.writeText(storageFile, jsonString)) {
                aapsLogger.error(LTag.AIMI, "[$TAG] ❌ PhysioStore: Save failed, file not written")
            }

            // 📊 LOG 2: Confirmation écriture avec taille
            val writtenBytes = jsonString.encodeToByteArray().size
            aapsLogger.info(LTag.AIMI, "[$TAG] ✅ PhysioStore: written bytes=$writtenBytes")

            // 📊 LOG 3: Verification fichier après écriture
            val exists = storage.exists(storageFile)
            val size = if (exists) storage.sizeBytes(storageFile) else 0
            val canRead = storage.canRead(storageFile)
            val canWrite = storage.canWrite(storageFile)

            aapsLogger.info(LTag.AIMI, "[$TAG] 🔍 PhysioStore: exists=$exists size=$size canRead=$canRead canWrite=$canWrite")

            if (!exists || size == 0L) {
                aapsLogger.error(LTag.AIMI, "[$TAG] ❌ PhysioStore: WRITE FAILED! File not created or empty")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$TAG] ❌ PhysioStore: Save exception: ${e.message}", e)
        }

        aapsLogger.debug(LTag.AIMI, "[$TAG] Saved to ${storage.displayPath(storageFile)} (${storage.sizeBytes(storageFile)} bytes)")
    }

    /**
     * Restores context and baseline from JSON file (WITH OUTCOME TRACKING)
     */
    private fun restoreFromDisk() {
        if (!storage.exists(storageFile)) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] No saved context found")
            return
        }

        try {
            val text = storage.readText(storageFile) ?: error("cannot read ${storage.displayPath(storageFile)}")
            val json = Json.parseToJsonElement(text).jsonObject
            val version = json.optIntCompat("version", 0)

            if (version < 1) {
                aapsLogger.warn(LTag.AIMI, "[$TAG] Unsupported version: $version")
                return
            }

            lock.withLock {
                lastUpdate = json.optLongCompat("lastUpdate", 0)

                // Restore outcome tracking (v2+)
                if (version >= 2) {
                    val outcomeStr = json.optStringCompat("lastRunOutcome").ifBlank { "NEVER_RUN" }
                    lastRunOutcome = try {
                        PhysioPipelineOutcome.valueOf(outcomeStr)
                    } catch (e: Exception) {
                        PhysioPipelineOutcome.NEVER_RUN
                    }
                    lastRunTimestamp = json.optLongCompat("lastRunTimestamp", 0)
                }

                json.optJsonObjectCompat("context")?.let { contextJson ->
                    lastContextUnsafe = PhysioContextMTR.fromJSON(contextJson)
                }

                json.optJsonObjectCompat("baseline")?.let { baselineJson ->
                    currentBaseline = PhysioBaselineMTR.fromJSON(baselineJson)
                }
            }

            val age = (aimiWallClockMs() - lastUpdate) / (60 * 60 * 1000)

            if (lastContextUnsafe != null) {
                aapsLogger.info(
                    LTag.AIMI,
                    "[$TAG] ✅ Context restored (outcome=$lastRunOutcome, state=${lastContextUnsafe?.state}, age=${age}h)"
                )
            }

        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$TAG] Failed to restore from disk", e)
            // Don't crash - just clear corrupted data
            lock.withLock {
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
    fun getStatus(): Map<String, String> = lock.withLock {
        mapOf(
            "hasContext" to (lastContextUnsafe != null).toString(),
            "hasBaseline" to (currentBaseline != null).toString(),
            "lastRunOutcome" to lastRunOutcome.name,
            "contextState" to (lastContextUnsafe?.state?.name ?: "NONE"),
            "contextAge" to "${(aimiWallClockMs() - lastUpdate) / (60 * 60 * 1000)}h",
            "confidence" to "${((lastContextUnsafe?.confidence ?: 0.0) * 100).toInt()}%",
            "baselineDays" to (currentBaseline?.validDaysCount ?: 0).toString(),
            "isValid" to isValid().toString(),
            "fileExists" to storage.exists(storageFile).toString(),
            "fileSize" to "${storage.sizeBytes(storageFile)} bytes",
            "filePath" to storage.displayPath(storageFile)
        )
    }

    /**
     * Logs current storage status
     */
    fun logStatus() {
        val status = getStatus()
        aapsLogger.info(LTag.AIMI, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.AIMI, "[$TAG] Physio Context Store Status:")
        status.forEach { (key, value) ->
            aapsLogger.info(LTag.AIMI, "[$TAG]   $key: $value")
        }
        aapsLogger.info(LTag.AIMI, "[$TAG] ═══════════════════════════════════════")
    }

    /**
     * Forces a refresh by re-reading from disk
     * Useful for testing or debugging
     */
    fun forceRefresh() {
        aapsLogger.info(LTag.AIMI, "[$TAG] Force refresh requested")
        restoreFromDisk()
    }
}
