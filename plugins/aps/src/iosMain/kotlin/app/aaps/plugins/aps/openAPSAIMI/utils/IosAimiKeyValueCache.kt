package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.Foundation.NSUserDefaults

/**
 * iOS half of [AimiKeyValueCache], over `NSUserDefaults`.
 *
 * A named [store] becomes a suite; a `null` [store] is `standardUserDefaults`, which is the same
 * store `IosSp` reads and writes. That mapping matters: `UnifiedActivityProviderMTR` reads the
 * user's activity source setting with a `null` store, so pointing this at a private suite would
 * quietly stop it seeing what the user chose.
 *
 * `NSUserDefaults` differs from `SharedPreferences` in two ways this class has to hide, because
 * [AimiKeyValueCache] is one contract and both phones must answer it the same:
 *
 * - **A missing key reads as zero, not as missing.** `integerForKey` answers `0` both for "absent"
 *   and for "present but not a number", so [getLong] asks `objectForKey` first and only then
 *   reports the stored value. Returning `0` where Android returns the caller's default would
 *   silently change every TTL decision.
 * - **A write reports nothing.** `setObject` returns `Unit`, while Android's `commit()` returns a
 *   real success flag that callers rely on. So a write here reads itself back and reports that,
 *   rather than always claiming success.
 *
 * Every operation is wrapped: a broken cache must never take down a caller, which is what the
 * interface promises.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiKeyValueCache @Inject constructor() : AimiKeyValueCache {

    /**
     * `null` is the standard store, a name is a suite.
     *
     * `NSUserDefaults(suiteName =)` answers null for the bundle id and for the global domain. No
     * store AIMI uses has either name, but a null here falls back to the standard store rather
     * than throwing, because losing a cache must not take a dosing tick down.
     */
    private fun defaultsOf(store: String): NSUserDefaults =
        NSUserDefaults(suiteName = store) ?: NSUserDefaults.standardUserDefaults

    override fun getString(store: String, key: String): String? =
        runCatching { defaultsOf(store).stringForKey(key) }.getOrNull()

    override fun putString(store: String, key: String, value: String): Boolean =
        runCatching {
            val defaults = defaultsOf(store)
            defaults.setObject(value, forKey = key)
            defaults.stringForKey(key) == value
        }.getOrDefault(false)

    override fun getLong(store: String, key: String, default: Long): Long =
        runCatching {
            val defaults = defaultsOf(store)
            // Ask whether the key is there at all: integerForKey cannot tell absent from zero.
            if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)
        }.getOrDefault(default)

    override fun putLong(store: String, key: String, value: Long): Boolean =
        runCatching {
            val defaults = defaultsOf(store)
            defaults.setInteger(value, forKey = key)
            defaults.objectForKey(key) != null && defaults.integerForKey(key) == value
        }.getOrDefault(false)

    /**
     * Matches Android exactly, edge cases included.
     *
     * A key that was never written reads as `0`, so the difference is the whole epoch and the
     * answer is `false` - a cold cache is not fresh, which is what the interface says. The
     * comparison is strict, so a timestamp exactly one TTL old is stale. A timestamp in the future
     * (the clock moved back) gives a negative difference and answers `true`; that is what Android
     * does, so it is kept rather than corrected here.
     */
    override fun isFresh(store: String, key: String, ttlMs: Long): Boolean =
        runCatching {
            val defaults = defaultsOf(store)
            val stored = if (defaults.objectForKey(key) == null) 0L else defaults.integerForKey(key)
            aimiWallClockMs() - stored < ttlMs
        }.getOrDefault(false)
}
