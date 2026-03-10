package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

/**
 * Tracks the history of actions applied via the AIMI Advisor.
 * Helps prevent "ping-pong" advice by providing context about recent changes.
 */
class AdvisorHistoryRepository(context: Context) {

    private val PREF_NAME = "AimiAdvisorHistory"
    private val KEY_HISTORY = "history_log"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
        PROFILE_CHANGE
    }

    /**
     * Log a new action.
     */
    fun logAction(type: ActionType, key: String, desc: String, oldVal: Any, newVal: Any) {
        val currentList = loadHistory().toMutableList()
        val entry = AdvisorActionLog(
            timestamp = System.currentTimeMillis(),
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
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return loadHistory().filter { it.timestamp >= cutoff }
    }

    private fun loadHistory(): List<AdvisorActionLog> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<AdvisorActionLog>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<AdvisorActionLog>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
