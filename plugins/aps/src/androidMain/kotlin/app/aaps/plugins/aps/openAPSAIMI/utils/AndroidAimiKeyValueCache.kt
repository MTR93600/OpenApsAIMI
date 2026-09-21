package app.aaps.plugins.aps.openAPSAIMI.utils

import android.content.Context
import android.content.SharedPreferences
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android half of [AimiKeyValueCache].
 *
 * Every named [store] is its own `SharedPreferences` file, exactly as each of the three original
 * callers had it (`"aimi_gemini_cache"`, `"AimiAdvisorHistory"`, ...). A `null` [store] resolves to
 * the same default preferences file `<packageName>_preferences` that
 * [app.aaps.core.interfaces.sharedPreferences.SP] already targets, so a value written through one
 * path is visible through the other.
 *
 * Every write commits synchronously (`commit()`, not `apply()`) so the reported success/failure is
 * real and an immediate read-back sees it, and every operation is wrapped: a broken cache must
 * never take down a caller.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiKeyValueCache @Inject constructor(
    private val context: Context
) : AimiKeyValueCache {

    private fun prefsOf(store: String?): SharedPreferences =
        if (store == null) context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        else context.getSharedPreferences(store, Context.MODE_PRIVATE)

    override fun getString(store: String?, key: String): String? =
        runCatching { prefsOf(store).getString(key, null) }.getOrNull()

    override fun putString(store: String?, key: String, value: String): Boolean =
        runCatching { prefsOf(store).edit().putString(key, value).commit() }.getOrDefault(false)

    override fun getLong(store: String?, key: String, default: Long): Long =
        runCatching { prefsOf(store).getLong(key, default) }.getOrDefault(default)

    override fun putLong(store: String?, key: String, value: Long): Boolean =
        runCatching { prefsOf(store).edit().putLong(key, value).commit() }.getOrDefault(false)

    override fun isFresh(store: String?, key: String, ttlMs: Long): Boolean =
        runCatching { aimiWallClockMs() - prefsOf(store).getLong(key, 0L) < ttlMs }.getOrDefault(false)
}
