package app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAimiModeDurationStoreTest {

    /** Keeps values in memory, keyed by store and key, like the real cache. */
    private class MemoryCache : AimiKeyValueCache {

        val longs = mutableMapOf<Pair<String, String>, Long>()

        override fun getString(store: String, key: String): String? = null
        override fun putString(store: String, key: String, value: String): Boolean = false
        override fun getLong(store: String, key: String, default: Long): Long = longs[store to key] ?: default
        override fun putLong(store: String, key: String, value: Long): Boolean {
            longs[store to key] = value
            return true
        }

        override fun isFresh(store: String, key: String, ttlMs: Long): Boolean = false
    }

    @Test
    fun `a mode with no saved duration lasts 60 minutes as on dev`() {
        val store = IosAimiModeDurationStore(MemoryCache())
        assertEquals(60, store.minutes("aimi_mode_lunch_duration"))
    }

    @Test
    fun `a saved duration is read back from the aimi_mode_activity store`() {
        val cache = MemoryCache()
        val store = IosAimiModeDurationStore(cache)
        store.save("aimi_mode_dinner_duration", 90)
        assertEquals(90, store.minutes("aimi_mode_dinner_duration"))
        assertEquals(90L, cache.longs["aimi_mode_activity" to "aimi_mode_dinner_duration"])
        assertEquals(60, store.minutes("aimi_mode_lunch_duration"))
    }
}
