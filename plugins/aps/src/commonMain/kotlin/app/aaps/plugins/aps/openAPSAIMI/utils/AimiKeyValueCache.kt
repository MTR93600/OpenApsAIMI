package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * A small, time-boxed key/value cache for AIMI shared code.
 *
 * AIMI keeps a few runtime values that are not settings a person chose: a 24 hour list of which
 * Gemini models currently exist ([app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver]),
 * an advisor action log kept to avoid ping-ponging tuning advice
 * ([app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository]), and one activity-source
 * preference read by a caller that has no path to
 * [app.aaps.core.interfaces.sharedPreferences.SP] ([app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR.getMode]).
 * On Android, all three were nothing but a `Context.getSharedPreferences(...)` call, which is why
 * eight other files were carrying a `Context` around purely to reach one of these three. This is
 * the shared half: name a store, put and get a value in it, and ask whether an entry has gone
 * stale.
 *
 * **Deliberately not the app's typed `Preferences`/`StringKey` system.** That system is for
 * settings a person configured, expected to persist and to sync. This is a cache the code itself
 * fills in, may recompute at any time, and that carries its own time-to-live per entry.
 *
 * **Deliberately not [AimiStorage].** That is files - names, bytes, journals that only ever grow.
 * This is small key/value pairs with an expiry, the shape `SharedPreferences` already has on
 * Android.
 *
 * Both platforms implement it: `AndroidAimiKeyValueCache` over `SharedPreferences`,
 * `IosAimiKeyValueCache` over `NSUserDefaults`. AIMI has to behave the same on either phone, so a
 * stub that silently cached nothing is not an option - it would leave a caller like
 * [app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver] looking alive while re-fetching
 * the model list on every single call.
 *
 * (This KDoc used to say there was deliberately no iOS implementation and that a future iOS graph
 * should fail loudly at wiring time. That was the earlier plan; it was dropped on 2026-10-10.)
 *
 * Reads answer `null`, `false` or the given default rather than throwing - a broken cache must
 * never take down a caller. Writes answer `false` on failure, for the same reason.
 */
interface AimiKeyValueCache {

    /**
     * [key] from [store], or `null` when [store] has no such key or is unreadable.
     *
     * [store] names one of AIMI's own caches (for example `"aimi_gemini_cache"`). It cannot name the
     * settings: those live in the encrypted settings store, read through `Preferences`. The old
     * `null` meaning "the platform's default preference file" was removed with that store, because
     * that file is only read once to move the settings and then stays frozen.
     */
    fun getString(store: String, key: String): String?

    /**
     * Writes [key] into [store]. `false` on failure.
     *
     * See [getString] for what [store] means. The write is synchronous: a caller that immediately
     * reads its own write back (as [app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository]
     * does) must see it.
     */
    fun putString(store: String, key: String, value: String): Boolean

    /** [key] from [store], or [default] when missing or unreadable. See [getString] for [store]. */
    fun getLong(store: String, key: String, default: Long): Long

    /** Writes [key] into [store]. `false` on failure. See [putString] for the write guarantee. */
    fun putLong(store: String, key: String, value: Long): Boolean

    /**
     * Whether the timestamp [putLong] last wrote to [key] in [store] is younger than [ttlMs].
     *
     * A [store]/[key] that was never written answers `false` - a cold cache is not fresh. This is
     * the one decision [app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver] makes
     * before it decides whether to hit the network.
     */
    fun isFresh(store: String, key: String, ttlMs: Long): Boolean
}
