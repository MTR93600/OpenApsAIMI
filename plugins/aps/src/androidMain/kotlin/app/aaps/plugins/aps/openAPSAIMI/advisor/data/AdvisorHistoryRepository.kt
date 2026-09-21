package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

/**
 * Tracks the history of actions applied via the AIMI Advisor.
 * Helps prevent "ping-pong" advice by providing context about recent changes.
 */
class AdvisorHistoryRepository(private val cache: AimiKeyValueCache) {

    private val PREF_NAME = "AimiAdvisorHistory"
    private val KEY_HISTORY = "history_log"
    private val gson = Gson()

    data class AdvisorActionLog(
        val timestamp: Long,
        val type: ActionType,
        val description: String,
        val key: String,
        val oldValue: String,
        val newValue: String
    )

    enum class ActionType {
        PREFERENCE_CHANGE,
        PROFILE_CHANGE,
        /** Multi-key tuning context bundle from Advisor. */
        TUNING_BUNDLE,
        TPO_SESSION_START,
        TPO_SESSION_REVERT,
        TPO_LLM_VETO,
    }

    /**
     * Log a new action.
     */
    fun logAction(type: ActionType, key: String, desc: String, oldVal: Any, newVal: Any) {
        val currentList = loadHistory().toMutableList()
        val entry = AdvisorActionLog(
            timestamp = aimiWallClockMs(),
            type = type,
            description = desc,
            key = key,
            oldValue = oldVal.toString(),
            newValue = newVal.toString()
        )
        currentList.add(0, entry) // Add to top
        
        // Keep only last 50 actions to save space
        val trimmed = if (currentList.size > 50) currentList.subList(0, 50) else currentList
        
        saveHistory(trimmed)
    }

    /**
     * Get actions from the last N days.
     */
    fun getRecentActions(days: Int): List<AdvisorActionLog> {
        val cutoff = aimiWallClockMs() - (days * 24 * 60 * 60 * 1000L)
        return loadHistory().filter { it.timestamp >= cutoff }
    }

    private fun loadHistory(): List<AdvisorActionLog> {
        val json = cache.getString(PREF_NAME, KEY_HISTORY) ?: return emptyList()
        val type = object : TypeToken<List<AdvisorActionLog>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<AdvisorActionLog>) {
        val json = gson.toJson(list)
        // AimiKeyValueCache.putString commits synchronously, so an immediate recreate() (e.g. after
        // Apply) sees the new entry before 48h de-dup runs.
        cache.putString(PREF_NAME, KEY_HISTORY, json)
    }
}
