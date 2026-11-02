package app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings

import android.content.Context
import android.content.SharedPreferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.AimiModeDurationStore.Companion.DEFAULT_MODE_DURATION_MIN
import app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.AimiModeDurationStore.Companion.STORE_NAME

/**
 * Android half of [AimiModeDurationStore].
 *
 * It reads and writes `Int` values in the `aimi_mode_activity` file, exactly as `dev_OAPSAIMI`
 * does. It does not go through `AimiKeyValueCache`: that store has no `Int` type, and reading an
 * `Int` entry as a `Long` would lose the durations users already saved.
 */
class AndroidAimiModeDurationStore(private val context: Context) : AimiModeDurationStore {

    private fun prefs(): SharedPreferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    override fun minutes(key: String): Int =
        runCatching { prefs().getInt(key, DEFAULT_MODE_DURATION_MIN) }.getOrDefault(DEFAULT_MODE_DURATION_MIN)

    override fun save(key: String, minutes: Int) {
        runCatching { prefs().edit().putInt(key, minutes).apply() }
    }
}
