package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saves every change made through the assistant, so the user can see and undo it. */
class AssistantHistory(context: Context) {

    private val store = context.getSharedPreferences("aimi_assistant_history", Context.MODE_PRIVATE)

    fun all(): List<ChangeRecord> {
        val raw = store.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChangeRecord(
                    prefKey = o.getString("key"),
                    from = o.getDouble("from"),
                    to = o.getDouble("to"),
                    timeMs = o.getLong("t"),
                    before = o.optJSONObject("before")?.let {
                        AssistantStats(it.getInt("n"), it.getInt("tir"), it.getDouble("low"), it.getDouble("mean"))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun forKey(prefKey: String): List<ChangeRecord> = all().filter { it.prefKey == prefKey }.sortedByDescending { it.timeMs }

    fun add(record: ChangeRecord) {
        val arr = JSONArray()
        (all() + record).takeLast(300).forEach { r ->
            arr.put(
                JSONObject().put("key", r.prefKey).put("from", r.from).put("to", r.to).put("t", r.timeMs).apply {
                    r.before?.let {
                        put("before", JSONObject().put("n", it.count).put("tir", it.tirPercent).put("low", it.lowPercent).put("mean", it.meanMmol))
                    }
                }
            )
        }
        store.edit().putString(KEY, arr.toString()).apply()
    }

    /** True if this setting was changed less than [days] days ago. */
    fun changedRecently(prefKey: String, nowMs: Long, days: Int = 3): Boolean =
        forKey(prefKey).firstOrNull()?.let { nowMs - it.timeMs < days * 24L * 3600_000L } ?: false

    private companion object {
        const val KEY = "records"
    }
}
