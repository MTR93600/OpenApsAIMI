package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Context Manager - Storage & lifecycle gestion des intents utilisateur.
 * 
 * **Responsabilités** :
 * - Stockage thread-safe des intents actifs
 * - Lifecycle management (expiration automatique)
 * - Parsing via LLM ou offline
 * - Snapshot generation pour chaque tick
 * 
 * **Thread-Safety** :
 * - ConcurrentHashMap pour storage
 * - Synchronized methods pour modifications
 * - Safe pour appel depuis multiple threads (UI, Loop, etc.)
 * 
 * **Usage** :
 * ```kotlin
 * // Add intent from UI
 * contextManager.addIntent("heavy cardio 1h")
 * 
 * // Get snapshot for current tick
 * val snapshot = contextManager.getSnapshot(System.currentTimeMillis())
 * 
 * // Remove intent
 * contextManager.removeIntent(intentId)
 * ```
 */
@Singleton
class ContextManager @Inject constructor(
    private val contextLLMClient: ContextLLMClient,
    private val contextParser: ContextParser,
    private val sp: SP,
    internal val aapsLogger: AAPSLogger,  // Internal for inline functions
    private val persistenceLayer: PersistenceLayer,  // For NS sync
    private val dateUtil: DateUtil,
    private val aapsSchedulers: AapsSchedulers
) {
    
    // Thread-safe storage (internal for inline functions)
    internal val activeIntents = ConcurrentHashMap<String, ContextIntent>()
    
    // Auto-increment ID
    private var nextId = 1
    
    init {
        loadFromStorage()
    }
    
    /**
     * Add intent from user text.
     * 
     * Tries LLM first (if enabled), then falls back to offline parser.
     * 
     * @param userText User message or preset
     * @param forceLLM Force LLM even if disabled (for testing)
     * @return List of added intent IDs
     */
    suspend fun addIntent(userText: String, forceLLM: Boolean = false): List<String> {
        if (userText.isBlank()) {
            aapsLogger.warn(LTag.APS, "[ContextManager] Empty text, ignoring")
            return emptyList()
        }
        
        aapsLogger.info(LTag.APS, "[ContextManager] Adding intent: '$userText'")
        
        // Try LLM if enabled
        val intents = if (shouldUseLLM() || forceLLM) {
            try {
                aapsLogger.debug(LTag.APS, "[ContextManager] Trying LLM parsing...")
                val llmIntents = contextLLMClient.parseWithLLM(userText)
                
                if (llmIntents.isNotEmpty()) {
                    aapsLogger.info(LTag.APS, "[ContextManager] LLM parsed ${llmIntents.size} intent(s)")
                    llmIntents
                } else {
                    aapsLogger.warn(LTag.APS, "[ContextManager] LLM returned empty, falling back to offline")
                    contextParser.parse(userText)
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[ContextManager] LLM failed: ${e.message}, using offline")
                contextParser.parse(userText)
            }
        } else {
            aapsLogger.debug(LTag.APS, "[ContextManager] Using offline parser (LLM disabled)")
            contextParser.parse(userText)
        }
        
        // Store intents
        val ids = mutableListOf<String>()
        for (intent in intents) {
            val id = generateId()
            activeIntents[id] = intent
            ids.add(id)
            aapsLogger.debug(LTag.APS, "[ContextManager] Stored intent $id: $intent")
            
            // Sync to Nightscout via TherapyEvent wrapper
            syncContextToNS(id, intent)
        }
        
        if (ids.isEmpty()) {
            aapsLogger.warn(LTag.APS, "[ContextManager] No intents parsed from: '$userText'")
        }
        
        // Cleanup expired and SAVE
        cleanupExpired(System.currentTimeMillis())
        saveToStorage()
        
        return ids
    }
    
    /**
     * Add intent from preset (UI button).
     * 
     * @param preset Preset definition
     * @param customDuration Optional custom duration (override default)
     * @param customIntensity Optional custom intensity (override default)
     * @return Intent ID
     */
    @Synchronized
    fun addPreset(
        preset: ContextPreset,
        customDuration: kotlin.time.Duration? = null,
        customIntensity: Intensity? = null
    ): String {
        aapsLogger.info(LTag.APS, "[ContextManager] Adding preset: ${preset.displayName}")
        
        var intent = contextParser.parsePreset(preset)
        
        // Override duration if provided
        if (customDuration != null) {
            intent = when (intent) {
                is Activity -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is Illness -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is Stress -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is UnannouncedMealRisk -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is Alcohol -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is Travel -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is MenstrualCycle -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is Custom -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
            }
        }
        
        // Override intensity if provided
        if (customIntensity != null) {
            intent = when (intent) {
                is Activity -> intent.copy(intensity = customIntensity)
                is Illness -> intent.copy(intensity = customIntensity)
                is Stress -> intent.copy(intensity = customIntensity)
                is UnannouncedMealRisk -> intent.copy(intensity = customIntensity)
                is Alcohol -> intent.copy(intensity = customIntensity)
                is Travel -> intent.copy(intensity = customIntensity)
                is MenstrualCycle -> intent.copy(intensity = customIntensity)
                is Custom -> intent.copy(intensity = customIntensity)
            }
        }
        
        val id = generateId()
        activeIntents[id] = intent
        
        aapsLogger.debug(LTag.APS, "[ContextManager] Stored preset $id: $intent")
        
        // Cleanup expired and SAVE
        cleanupExpired(System.currentTimeMillis())
        saveToStorage()
        
        return id
    }
    
    /**
     * Remove intent by ID.
     * 
     * @param id Intent ID
     * @return True if removed, false if not found
     */
    @Synchronized
    fun removeIntent(id: String): Boolean {
        val removed = activeIntents.remove(id)
        if (removed != null) {
            aapsLogger.info(LTag.APS, "[ContextManager] Removed intent $id")
            saveToStorage()
            return true
        }
        aapsLogger.warn(LTag.APS, "[ContextManager] Intent $id not found")
        return false
    }
    
    /**
     * Remove all intents of a specific type by class.
     * 
     * @param intentClass Intent type class to remove
     * @return Number of removed intents
     */
    @Synchronized
    fun removeByType(intentClass: Class<out ContextIntent>): Int {
        val toRemove = activeIntents.filter { intentClass.isInstance(it.value) }.keys
        toRemove.forEach { activeIntents.remove(it) }
        
        if (toRemove.isNotEmpty()) {
            aapsLogger.info(LTag.APS, "[ContextManager] Removed ${toRemove.size} intent(s) of type ${intentClass.simpleName}")
            saveToStorage()
        }
        
        return toRemove.size
    }
    
    /**
     * Clear all intents.
     */
    @Synchronized
    fun clearAll() {
        val count = activeIntents.size
        activeIntents.clear()
        aapsLogger.info(LTag.APS, "[ContextManager] Cleared all intents (removed $count)")
        saveToStorage()
    }
    
    /**
     * Get snapshot at specific timestamp.
     * 
     * Removes expired intents and returns active ones.
     * 
     * @param timestampMs Current timestamp
     * @return Snapshot of active intents
     */
    fun getSnapshot(timestampMs: Long): ContextSnapshot {
        // Cleanup expired first
        cleanupExpired(timestampMs)
        
        // Get all active intents
        val allIntents = activeIntents.values.toList()
        
        // Build snapshot
        val snapshot = ContextSnapshot.from(timestampMs, allIntents)
        
        if (snapshot.intentCount > 0) {
            aapsLogger.debug(LTag.APS, "[ContextManager] Snapshot: ${snapshot.intentCount} active intent(s)")
        }
        
        return snapshot
    }
    
    /**
     * Get all active intents with their IDs.
     * 
     * @return Map of ID → Intent
     */
    fun getAllIntents(): Map<String, ContextIntent> {
        return activeIntents.toMap()
    }
    
    /**
     * Get intent by ID.
     * 
     * @param id Intent ID
     * @return Intent or null if not found
     */
    fun getIntent(id: String): ContextIntent? {
        return activeIntents[id]
    }
    
    /**
     * Extend intent duration.
     * 
     * @param id Intent ID
     * @param additionalDuration Duration to add
     * @return True if extended, false if not found
     */
    @Synchronized
    fun extendDuration(id: String, additionalDuration: kotlin.time.Duration): Boolean {
        val intent = activeIntents[id] ?: return false
        
        val extended = when (intent) {
            is Activity -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is Illness -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is Stress -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is UnannouncedMealRisk -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is Alcohol -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is Travel -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is MenstrualCycle -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
            is Custom -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
        }
        
        activeIntents[id] = extended
        
        aapsLogger.info(LTag.APS, "[ContextManager] Extended intent $id by ${additionalDuration.inWholeMinutes}min")
        saveToStorage()
        
        return true
    }
    
    /**
     * Check if context module is enabled.
     */
    fun isEnabled(): Boolean {
        return sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled.key, false)
    }
    
    // Private helpers
    
    private fun shouldUseLLM(): Boolean {
        if (!sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, false)) {
            return false
        }
        
        // CHECK SHARED/ADVISOR KEYS instead of legacy Context Keys
        val provider = sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorProvider.key, "OPENAI")
        val apiKey = when (provider) {
            "OPENAI" -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorOpenAIKey.key, "")
            "GEMINI" -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorGeminiKey.key, "")
            "DEEPSEEK" -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorDeepSeekKey.key, "")
            "CLAUDE" -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorClaudeKey.key, "")
            else -> ""
        }
        
        return apiKey.isNotBlank()
    }
    
    @Synchronized
    internal fun cleanupExpired(timestampMs: Long) {
        val expired = activeIntents.filter { (_, intent) -> 
            !intent.isActiveAt(timestampMs) 
        }.keys
        
        if (expired.isNotEmpty()) {
            expired.forEach { activeIntents.remove(it) }
            aapsLogger.debug(LTag.APS, "[ContextManager] Cleaned up ${expired.size} expired intent(s)")
            saveToStorage()
        }
    }
    
    private fun generateId(): String {
        return "CTX_${System.currentTimeMillis()}_${nextId++}"
    }

    // --- PERSISTENCE ---

    private fun saveToStorage() {
        try {
            val jsonArray = org.json.JSONArray()
            activeIntents.forEach { (id, intent) ->
                val obj = org.json.JSONObject()
                obj.put("id", id)
                obj.put("type", intent::class.simpleName)
                
                // Common fields
                obj.put("start", intent.startTimeMs)
                obj.put("duration", intent.durationMs)
                obj.put("intensity", intent.intensity.name)
                obj.put("confidence", intent.confidence.toDouble())
                
                // Specific fields
                when (intent) {
                    is Activity -> obj.put("activityType", intent.activityType.name)
                    is Illness -> obj.put("symptomType", intent.symptomType.name)
                    is Stress -> obj.put("stressType", intent.stressType.name)
                    is Alcohol -> obj.put("units", intent.units.toDouble())
                    is UnannouncedMealRisk -> obj.put("riskWindow", intent.riskWindow.inWholeMinutes)
                    is Travel -> obj.put("tz", intent.timezoneShiftHours)
                    is MenstrualCycle -> obj.put("phase", intent.phase.name)
                    is Custom -> {
                         obj.put("desc", intent.description)
                         obj.put("strat", intent.suggestedStrategy)
                    }
                }
                jsonArray.put(obj)
            }
            
            sp.putString(app.aaps.core.keys.StringKey.OApsAIMIContextStorage.key, jsonArray.toString())
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextManager] Save failed: ${e.message}")
        }
    }

    private fun loadFromStorage() {
        try {
            val jsonStr = sp.getString(app.aaps.core.keys.StringKey.OApsAIMIContextStorage.key, "")
            if (jsonStr.isBlank()) return
            
            val jsonArray = org.json.JSONArray(jsonStr)
            activeIntents.clear()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                
                try {
                    // Parse intent
                    val type = obj.getString("type")
                    val startTimeMs = obj.getLong("start")
                    val durationMs = obj.getLong("duration")
                    val intensity = Intensity.valueOf(obj.getString("intensity"))
                    val confidence = obj.getDouble("confidence").toFloat()
                    
                    val intent = when(type) {
                        "Activity" -> Activity(startTimeMs, durationMs, intensity, confidence, Activity.ActivityType.valueOf(obj.getString("activityType")))
                        "Illness" -> Illness(startTimeMs, durationMs, intensity, confidence, Illness.SymptomType.valueOf(obj.getString("symptomType")))
                        "Stress" -> Stress(startTimeMs, durationMs, intensity, confidence, Stress.StressType.valueOf(obj.getString("stressType")))
                        "Alcohol" -> Alcohol(startTimeMs, durationMs, intensity, confidence, obj.getDouble("units").toFloat())
                        "UnannouncedMealRisk" -> UnannouncedMealRisk(startTimeMs, durationMs, intensity, confidence, obj.getLong("riskWindow").minutes)
                        "Travel" -> Travel(startTimeMs, durationMs, intensity, confidence, obj.getInt("tz"))
                        "MenstrualCycle" -> MenstrualCycle(startTimeMs, durationMs, intensity, confidence, MenstrualCycle.CyclePhase.valueOf(obj.getString("phase")))
                        "Custom" -> Custom(startTimeMs, durationMs, intensity, confidence, obj.getString("desc"), obj.optString("strat", ""))
                        else -> null
                    }
                    
                    if (intent != null) {
                        activeIntents[id] = intent
                    }
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[ContextManager] Failed to restore intent $id: ${e.message}")
                }
            }
            
            // Restore ID counter to avoid collisions
            val maxId = activeIntents.keys.mapNotNull { 
                it.substringAfterLast("_", "").toIntOrNull() 
            }.maxOrNull() ?: 0
            nextId = maxId + 1
             
            aapsLogger.info(LTag.APS, "[ContextManager] Restored ${activeIntents.size} intents from storage")
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextManager] Load failed: ${e.message}")
        }
    }
    
    // ========================================
    // NIGHTSCOUT SYNC
    // ========================================
    
    /**
     * Sync ContextIntent to Nightscout via TherapyEvent wrapper.
     * Creates a NOTE TherapyEvent with AIMI_CONTEXT prefix.
     */
    private fun syncContextToNS(intentId: String, intent: ContextIntent) {
        try {
            val intentJson = serializeContextIntent(intent)
            
            val therapyEvent = TE(
                timestamp = intent.startTimeMs,
                type = TE.Type.NOTE,
                glucoseUnit = GlucoseUnit.MGDL,
                note = "AIMI_CONTEXT:$intentId:$intentJson",
                duration = intent.durationMs
            )
            
            aapsLogger.debug(LTag.APS, "[ContextManager] Syncing context $intentId to NS")
            
            val disposable = CompositeDisposable()
            disposable += persistenceLayer.insertOrUpdateTherapyEvent(therapyEvent)
                .subscribeOn(aapsSchedulers.io)
                .subscribe(
                    {
                        aapsLogger.info(LTag.APS, "[ContextManager] ✅ Context $intentId synced to NS")
                        disposable.clear()
                    },
                    { error ->
                        aapsLogger.error(LTag.APS, "[ContextManager] ❌ Failed to sync context $intentId: $error")
                        disposable.clear()
                    }
                )
                
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextManager] Exception syncing context $intentId", e)
        }
    }
    
    private fun serializeContextIntent(intent: ContextIntent): String {
        return when (intent) {
            is Activity -> """{"type":"Activity","act":"${intent.activityType}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Stress -> """{"type":"Stress","stress":"${intent.stressType}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Illness -> """{"type":"Illness","symptom":"${intent.symptomType}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is UnannouncedMealRisk -> """{"type":"UnannouncedMeal","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Alcohol -> """{"type":"Alcohol","units":${intent.units},"dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Travel -> """{"type":"Travel","tz":${intent.timezoneShiftHours},"dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is MenstrualCycle -> """{"type":"MenstrualCycle","phase":"${intent.phase}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Custom -> """{"type":"Custom","desc":"${intent.description}","strat":"${intent.suggestedStrategy}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
        }
    }
    
    /**
     * Inject ContextIntent received from Nightscout.
     * Skips local parsing, direct injection.
     */
    @Synchronized
    fun injectContextFromNS(intentId: String, intent: ContextIntent) {
        // Check if already exists (deduplication)
        if (activeIntents.containsKey(intentId)) {
            aapsLogger.debug(LTag.APS, "[ContextManager] Context $intentId already exists, skipping NS injection")
            return
        }
        
        activeIntents[intentId] = intent
        aapsLogger.info(LTag.APS, "[ContextManager] ✅ Injected context from NS: $intentId -> $intent")
        
        saveToStorage()
    }
}
