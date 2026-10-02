package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiContextLlm
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRefresher
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.reflect.KClass
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
 * - One plain map, guarded by one [AapsLock]. The map is private, so the lock cannot be bypassed.
 * - A read-then-write pair (find the expired keys, then drop them) runs inside a single
 *   [app.aaps.core.interfaces.concurrent.withLock] block, so no other thread can add or remove an
 *   intent in between. A `ConcurrentHashMap` made each single call safe but left those pairs open.
 * - The lock is **never** held across a `suspend` call, across a `ioScope.launch` body, or across a
 *   call back into other code (storage write, Nightscout sync, patient state refresh). Every method
 *   takes the lock, copies or mutates, releases, and only then does the slow work. That is what keeps
 *   this free of deadlock even though the loop tick calls in from another thread.
 * - Safe pour appel depuis multiple threads (UI, Loop, etc.)
 *
 * **Usage** :
 * ```kotlin
 * // Add intent from UI
 * contextManager.addIntent("heavy cardio 1h")
 *
 * // Get snapshot for current tick
 * val snapshot = contextManager.getSnapshot(aimiWallClockMs())
 *
 * // Remove intent
 * contextManager.removeIntent(intentId)
 * ```
 */
@SingleIn(AppScope::class)
class ContextManager @Inject constructor(
    private val contextLLMClient: AimiContextLlm,
    private val contextParser: ContextParser,
    private val sp: KeyValueStore,
    internal val aapsLogger: AAPSLogger,  // Internal for inline functions
    private val persistenceLayer: PersistenceLayer,  // For NS sync
    private val dateUtil: DateUtil
) {

    private val ioScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)

    /** Guards [activeIntents] and [nextId]. Both are plain state; nothing reads them unguarded. */
    private val intentsLock = AapsLock()

    // Thread-safe storage: private on purpose, so every access goes through intentsLock.
    private val activeIntents = LinkedHashMap<String, ContextIntent>()

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
    suspend fun addIntent(
        userText: String,
        forceLLM: Boolean = false,
        remotePin: String? = null
    ): List<String> {
        if (userText.isBlank()) {
            aapsLogger.warn(LTag.APS, "[ContextManager] Empty text, ignoring")
            return emptyList()
        }

        aapsLogger.info(LTag.APS, "[ContextManager] Adding intent: '$userText'")

        // Try LLM if enabled. The lock is not held here: this suspends.
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

        // Store intents. Whole batch under one lock, so a reader never sees half of it.
        val stored = mutableListOf<Pair<String, ContextIntent>>()
        intentsLock.withLock {
            for (intent in intents) {
                val id = generateId()
                activeIntents[id] = intent
                stored += id to intent
            }
        }

        // Logging and the Nightscout sync call out of this class, so they stay outside the lock.
        for ((id, intent) in stored) {
            aapsLogger.debug(LTag.APS, "[ContextManager] Stored intent $id: $intent")
            syncContextToNS(id, intent, remotePin)
        }

        val ids = stored.map { it.first }
        if (ids.isEmpty()) {
            aapsLogger.warn(LTag.APS, "[ContextManager] No intents parsed from: '$userText'")
        }

        // Cleanup expired and SAVE
        cleanupExpired(aimiWallClockMs())
        saveToStorage()
        notifyPatientStateChanged()

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
                is SlowCarbMeal -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
                is HypoRecovery -> intent.copy(durationMs = customDuration.inWholeMilliseconds)
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
                is SlowCarbMeal -> intent.copy(intensity = customIntensity)
                is HypoRecovery -> intent.copy(intensity = customIntensity)
                is Custom -> intent.copy(intensity = customIntensity)
            }
        }

        // The id is taken and the intent stored in one step, so two callers cannot share an id.
        val id = intentsLock.withLock {
            val newId = generateId()
            activeIntents[newId] = intent
            newId
        }

        aapsLogger.debug(LTag.APS, "[ContextManager] Stored preset $id: $intent")

        // Cleanup expired and SAVE
        cleanupExpired(aimiWallClockMs())
        saveToStorage()
        notifyPatientStateChanged()

        return id
    }

    /**
     * Remove intent by ID.
     *
     * @param id Intent ID
     * @return True if removed, false if not found
     */
    fun removeIntent(id: String): Boolean {
        val removed = intentsLock.withLock { activeIntents.remove(id) }
        if (removed != null) {
            aapsLogger.info(LTag.APS, "[ContextManager] Removed intent $id")
            saveToStorage()

            ioScope.launch {
                try {
                    persistenceLayer.invalidateTherapyEventsWithNote("AIMI_CONTEXT:$id", Action.TREATMENT, Sources.Aaps)
                    aapsLogger.debug(LTag.APS, "[ContextManager] Synced invalidation for $id")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "[ContextManager] Failed to invalidate sync record for $id: ${e.message}", e)
                }
            }
            notifyPatientStateChanged()

            return true
        }
        aapsLogger.warn(LTag.APS, "[ContextManager] Intent $id not found")
        return false
    }

    /**
     * Remove all intents of a specific type.
     *
     * The search and the removal happen under one lock, so an intent added in between is never
     * dropped by mistake and one that another thread just removed is not counted twice.
     *
     * @param intentClass Intent type to remove
     * @return Number of removed intents
     */
    fun removeByType(intentClass: KClass<out ContextIntent>): Int {
        val removedCount = intentsLock.withLock {
            val toRemove = activeIntents.filterValues { intentClass.isInstance(it) }.keys
            toRemove.forEach { activeIntents.remove(it) }
            toRemove.size
        }

        if (removedCount > 0) {
            aapsLogger.info(LTag.APS, "[ContextManager] Removed $removedCount intent(s) of type ${intentClass.simpleName}")
            saveToStorage()
            notifyPatientStateChanged()
        }

        return removedCount
    }

    /**
     * Clear all intents.
     */
    fun clearAll() {
        val count = intentsLock.withLock {
            val size = activeIntents.size
            activeIntents.clear()
            size
        }
        aapsLogger.info(LTag.APS, "[ContextManager] Cleared all intents (removed $count)")
        saveToStorage()
        notifyPatientStateChanged()

        ioScope.launch {
            try {
                persistenceLayer.invalidateTherapyEventsWithNote("AIMI_CONTEXT:", Action.TREATMENT, Sources.Aaps)
                aapsLogger.debug(LTag.APS, "[ContextManager] Synced invalidation for all contexts")
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[ContextManager] Failed to invalidate all sync records: ${e.message}", e)
            }
        }
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
        val allIntents = intentsLock.withLock { activeIntents.values.toList() }

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
        return intentsLock.withLock { activeIntents.toMap() }
    }

    /**
     * Get intent by ID.
     *
     * @param id Intent ID
     * @return Intent or null if not found
     */
    fun getIntent(id: String): ContextIntent? {
        return intentsLock.withLock { activeIntents[id] }
    }

    /**
     * Extend intent duration.
     *
     * @param id Intent ID
     * @param additionalDuration Duration to add
     * @return True if extended, false if not found
     */
    fun extendDuration(id: String, additionalDuration: kotlin.time.Duration): Boolean {
        // Read, copy and write back in one step, so a parallel extend cannot be lost.
        val extended = intentsLock.withLock {
            val intent = activeIntents[id]
            if (intent == null) {
                false
            } else {
                activeIntents[id] = when (intent) {
                    is Activity -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is Illness -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is Stress -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is UnannouncedMealRisk -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is Alcohol -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is Travel -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is MenstrualCycle -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is SlowCarbMeal -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is HypoRecovery -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                    is Custom -> intent.copy(durationMs = intent.durationMs + additionalDuration.inWholeMilliseconds)
                }
                true
            }
        }
        if (!extended) return false

        aapsLogger.info(LTag.APS, "[ContextManager] Extended intent $id by ${additionalDuration.inWholeMinutes}min")
        saveToStorage()
        notifyPatientStateChanged()

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

    /**
     * Drop every intent that is no longer active at [timestampMs].
     *
     * The filter and the removals are one locked step. They were two before, so an intent added
     * between them could be dropped although it had never expired.
     */
    internal fun cleanupExpired(timestampMs: Long) {
        val expiredCount = intentsLock.withLock {
            val expired = activeIntents.filterValues { intent ->
                !intent.isActiveAt(timestampMs)
            }.keys
            expired.forEach { activeIntents.remove(it) }
            expired.size
        }

        if (expiredCount > 0) {
            aapsLogger.debug(LTag.APS, "[ContextManager] Cleaned up $expiredCount expired intent(s)")
            saveToStorage()
        }
    }

    /** Caller must hold [intentsLock]: [nextId] is shared state. */
    private fun generateId(): String {
        return "CTX_${aimiWallClockMs()}_${nextId++}"
    }

    // --- PERSISTENCE ---

    private fun saveToStorage() {
        // Copy under the lock, then build and write outside it: the store is other people's code.
        val snapshot = intentsLock.withLock { activeIntents.toMap() }
        try {
            val jsonArray = org.json.JSONArray()
            snapshot.forEach { (id, intent) ->
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
                    is SlowCarbMeal -> obj.put("absorptionDelay", intent.absorptionDelay.inWholeMinutes)
                    is HypoRecovery -> { /* no type-specific field */ }
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
            // Read and parse outside the lock; only the handover to the map is guarded.
            val jsonStr = sp.getString(app.aaps.core.keys.StringKey.OApsAIMIContextStorage.key, "")
            if (jsonStr.isBlank()) return

            val jsonArray = org.json.JSONArray(jsonStr)
            val restored = LinkedHashMap<String, ContextIntent>()

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
                        "Activity" -> Activity(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            activityType = Activity.ActivityType.valueOf(obj.getString("activityType"))
                        )
                        "Illness" -> Illness(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            symptomType = Illness.SymptomType.valueOf(obj.getString("symptomType"))
                        )
                        "Stress" -> Stress(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            stressType = Stress.StressType.valueOf(obj.getString("stressType"))
                        )
                        "Alcohol" -> Alcohol(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            units = obj.getDouble("units").toFloat()
                        )
                        "UnannouncedMealRisk" -> UnannouncedMealRisk(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            riskWindow = obj.getLong("riskWindow").minutes
                        )
                        "Travel" -> Travel(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            timezoneShiftHours = obj.getInt("tz")
                        )
                        "MenstrualCycle" -> MenstrualCycle(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            phase = MenstrualCycle.CyclePhase.valueOf(obj.getString("phase"))
                        )
                        "SlowCarbMeal" -> SlowCarbMeal(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            absorptionDelay = obj.optLong("absorptionDelay", 90L).minutes
                        )
                        "HypoRecovery" -> HypoRecovery(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence
                        )
                        "Custom" -> Custom(
                            startTimeMs = if (startTimeMs > 0) startTimeMs else aimiWallClockMs(),
                            durationMs = durationMs,
                            intensity = intensity,
                            confidence = confidence,
                            description = obj.getString("desc"),
                            suggestedStrategy = obj.optString("strat", "")
                        )
                        else -> null
                    }

                    if (intent != null) {
                        restored[id] = intent
                    }
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[ContextManager] Failed to restore intent $id: ${e.message}")
                }
            }

            val restoredCount = intentsLock.withLock {
                activeIntents.clear()
                activeIntents.putAll(restored)

                // Restore ID counter to avoid collisions
                val maxId = activeIntents.keys.mapNotNull {
                    it.substringAfterLast("_", "").toIntOrNull()
                }.maxOrNull() ?: 0
                nextId = maxId + 1
                activeIntents.size
            }

            aapsLogger.info(LTag.APS, "[ContextManager] Restored $restoredCount intents from storage")

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
    private fun syncContextToNS(intentId: String, intent: ContextIntent, remotePin: String? = null) {
        try {
            val intentJson = serializeContextIntent(intent)
            val note = if (!remotePin.isNullOrBlank()) {
                "AIMI_CONTEXT:$intentId:PIN:${remotePin.trim()}:$intentJson"
            } else {
                "AIMI_CONTEXT:$intentId:$intentJson"
            }

            val therapyEvent = TE(
                timestamp = intent.startTimeMs,
                type = TE.Type.NOTE,
                glucoseUnit = GlucoseUnit.MGDL,
                note = note,
                duration = intent.durationMs
            )

            aapsLogger.debug(LTag.APS, "[ContextManager] Syncing context $intentId to NS")

            ioScope.launch {
                try {
                    persistenceLayer.insertOrUpdateTherapyEvent(therapyEvent)
                    aapsLogger.info(LTag.APS, "[ContextManager] ✅ Context $intentId synced to NS")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "[ContextManager] ❌ Failed to sync context $intentId: ${e.message}", e)
                }
            }
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
            is SlowCarbMeal -> """{"type":"SlowCarbMeal","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is HypoRecovery -> """{"type":"HypoRecovery","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
            is Custom -> """{"type":"Custom","desc":"${intent.description}","strat":"${intent.suggestedStrategy}","int":"${intent.intensity}","dur":${intent.durationMs},"start":${intent.startTimeMs},"conf":${intent.confidence}}"""
        }
    }

    /**
     * Inject ContextIntent received from Nightscout.
     * Skips local parsing, direct injection.
     */
    fun injectContextFromNS(intentId: String, intent: ContextIntent, receivedPin: String? = null) {
        val configuredPin = sp.getString(AimiStringKey.RemoteControlPin.key, "").trim()
        val incomingPin = receivedPin?.trim().orEmpty()
        if (configuredPin.isBlank()) {
            aapsLogger.warn(LTag.APS, "[ContextManager] Rejecting NS context $intentId: AIMI remote PIN not configured")
            return
        }
        if (incomingPin != configuredPin) {
            aapsLogger.warn(LTag.APS, "[ContextManager] Rejecting NS context $intentId: invalid or missing PIN")
            return
        }

        // Check if already exists (deduplication) and insert in one step.
        val inserted = intentsLock.withLock {
            if (activeIntents.containsKey(intentId)) {
                false
            } else {
                activeIntents[intentId] = intent
                true
            }
        }
        if (!inserted) {
            aapsLogger.debug(LTag.APS, "[ContextManager] Context $intentId already exists, skipping NS injection")
            return
        }

        aapsLogger.info(LTag.APS, "[ContextManager] ✅ Injected context from NS: $intentId -> $intent")

        saveToStorage()
        notifyPatientStateChanged()
    }

    private fun notifyPatientStateChanged() {
        val snapshot = try {
            getSnapshot(dateUtil.now())
        } catch (_: Exception) {
            null
        }
        PatientStateRuntimeRefresher.refreshFromContextIntents(
            contextSnapshot = snapshot,
            nowMs = dateUtil.now(),
        )
    }
}
