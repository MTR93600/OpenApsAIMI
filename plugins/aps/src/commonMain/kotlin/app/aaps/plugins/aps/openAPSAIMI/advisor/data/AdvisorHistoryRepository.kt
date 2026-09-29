package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tracks the history of actions applied via the AIMI Advisor.
 * Helps prevent "ping-pong" advice by providing context about recent changes.
 *
 * ## The stored document
 *
 * The history is one JSON array in [AimiKeyValueCache], written by Gson until this class moved to
 * shared code. Phones already carry that document, so the reader here has to keep reading it and
 * the writer has to keep producing the same bytes. The shape is:
 *
 * ```
 * [{"timestamp":1757942400000,"type":"PREFERENCE_CHANGE","description":"Raised max SMB",
 *   "key":"OApsAIMIMaxSMB","oldValue":"1.0","newValue":"1.2"}]
 * ```
 *
 * Field names, field order and the enum written as its constant name are all the same in Gson and
 * in kotlinx, so the only real difference is escaping - see [escapeLikeGsonHtmlSafe], which this
 * class applies so the two writers agree byte for byte.
 *
 * `@Serializable` is deliberately NOT used: `:plugins:aps` does not apply the kotlinx serialization
 * **compiler** plugin, only the runtime library it gets through `:core:utils`. The document is
 * therefore read and written through the [JsonObject] element API, the same way
 * [T3cRuntimeHistoryReader] and [HarmoniaRuntimeHistoryReader] read theirs in this package.
 */
class AdvisorHistoryRepository(private val cache: AimiKeyValueCache) {

    private val PREF_NAME = "AimiAdvisorHistory"
    private val KEY_HISTORY = "history_log"

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
        val trimmed = if (currentList.size > MAX_ENTRIES) currentList.subList(0, MAX_ENTRIES) else currentList

        saveHistory(trimmed)
    }

    /**
     * Get actions from the last N days.
     */
    fun getRecentActions(days: Int): List<AdvisorActionLog> {
        val cutoff = aimiWallClockMs() - (days * 24 * 60 * 60 * 1000L)
        return loadHistory().filter { it.timestamp >= cutoff }
    }

    /**
     * Every entry the store holds, newest first.
     *
     * A document that cannot be parsed at all gives an empty list, exactly as the Gson reader did.
     * A single entry that cannot be read - most likely a `type` this build does not know, written
     * by a newer one - is dropped and the rest are kept. Gson used to keep such an entry with a
     * `null` in the non-null [AdvisorActionLog.type] field, which throws as soon as any caller
     * reads it, so dropping the one entry is the safer half of the same trade.
     */
    private fun loadHistory(): List<AdvisorActionLog> {
        val json = cache.getString(PREF_NAME, KEY_HISTORY) ?: return emptyList()
        val document = try {
            Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return document.mapNotNull { element -> (element as? JsonObject)?.toActionLog() }
    }

    /** One stored object as an entry, or null when its [ActionType] is missing or unknown. */
    private fun JsonObject.toActionLog(): AdvisorActionLog? {
        val storedType = optStringCompat(FIELD_TYPE)
        val type = ActionType.entries.firstOrNull { it.name == storedType } ?: return null
        return AdvisorActionLog(
            timestamp = optLongCompat(FIELD_TIMESTAMP, 0L),
            type = type,
            description = optStringCompat(FIELD_DESCRIPTION),
            key = optStringCompat(FIELD_KEY),
            oldValue = optStringCompat(FIELD_OLD_VALUE),
            newValue = optStringCompat(FIELD_NEW_VALUE)
        )
    }

    private fun saveHistory(list: List<AdvisorActionLog>) {
        val document = buildJsonArray {
            list.forEach { entry ->
                add(
                    buildJsonObject {
                        // This order is the order Gson wrote, which is the declaration order of
                        // AdvisorActionLog. Keep the two in step.
                        put(FIELD_TIMESTAMP, entry.timestamp)
                        put(FIELD_TYPE, entry.type.name)
                        put(FIELD_DESCRIPTION, entry.description)
                        put(FIELD_KEY, entry.key)
                        put(FIELD_OLD_VALUE, entry.oldValue)
                        put(FIELD_NEW_VALUE, entry.newValue)
                    }
                )
            }
        }
        // AimiKeyValueCache.putString commits synchronously, so an immediate recreate() (e.g. after
        // Apply) sees the new entry before 48h de-dup runs.
        cache.putString(PREF_NAME, KEY_HISTORY, document.toString().escapeLikeGsonHtmlSafe())
    }

    private companion object {

        /** Retention: the newest 50 actions, to save space. */
        const val MAX_ENTRIES = 50

        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_TYPE = "type"
        const val FIELD_DESCRIPTION = "description"
        const val FIELD_KEY = "key"
        const val FIELD_OLD_VALUE = "oldValue"
        const val FIELD_NEW_VALUE = "newValue"
    }
}

/**
 * The five characters Gson escapes and kotlinx does not.
 *
 * A plain `Gson()` writes in "HTML safe" mode, so `<`, `>`, `&`, `=` and `'` come out as the six
 * character unicode escapes listed in the code below. kotlinx writes them as themselves. Both forms
 * mean the same string and both readers accept both, so this is cosmetic - but advisor text really
 * does contain `=` and `>` (the LLM reasons say things like `ISF > 90`), and matching Gson byte for
 * byte is cheaper than arguing that the difference is harmless every time someone looks at the
 * store.
 *
 * Applying this to the finished document is safe: none of the five is JSON syntax, so each one can
 * only be inside a string value, and none of the replacements introduces another one.
 */
private fun String.escapeLikeGsonHtmlSafe(): String =
    replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("=", "\\u003d")
        .replace("'", "\\u0027")
