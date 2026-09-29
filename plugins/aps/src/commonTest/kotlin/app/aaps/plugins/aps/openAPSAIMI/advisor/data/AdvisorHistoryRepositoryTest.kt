package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The advisor history has to survive the move off Gson.
 *
 * This history is already on the owner's phone: Gson wrote it, and whatever reads it next has to
 * read it. A format break here does not crash - the reader answers an empty list and the history
 * quietly disappears, which nobody notices until they go looking for it. So the JSON literals below
 * are not invented; each one is the exact output of a plain `Gson().toJson(...)` over the old
 * `AdvisorActionLog` shape, captured from a real Gson 2.14.0 run, and they are compared both ways:
 * the new reader must read them, and the new writer must produce them again byte for byte.
 */
class AdvisorHistoryRepositoryTest {

    /** An in-memory stand-in for the platform key/value store. */
    private class FakeCache : AimiKeyValueCache {

        val strings = mutableMapOf<String, String>()
        private val longs = mutableMapOf<String, Long>()

        private fun id(store: String?, key: String) = "$store/$key"

        override fun getString(store: String?, key: String): String? = strings[id(store, key)]

        override fun putString(store: String?, key: String, value: String): Boolean {
            strings[id(store, key)] = value
            return true
        }

        override fun getLong(store: String?, key: String, default: Long): Long = longs[id(store, key)] ?: default

        override fun putLong(store: String?, key: String, value: Long): Boolean {
            longs[id(store, key)] = value
            return true
        }

        override fun isFresh(store: String?, key: String, ttlMs: Long): Boolean = false
    }

    private companion object {

        /** The store and key the repository has always used. A port must not move them. */
        const val CACHE_ID = "AimiAdvisorHistory/history_log"

        /**
         * Gson output for two ordinary entries, newest first. Captured from
         * `new Gson().toJson(list, new TypeToken<List<AdvisorActionLog>>(){}.getType())`.
         */
        val GSON_TWO_ENTRIES =
            """[{"timestamp":1757942400000,"type":"PREFERENCE_CHANGE","description":"Raised max SMB","key":"OApsAIMIMaxSMB","oldValue":"1.0","newValue":"1.2"},{"timestamp":1757856000000,"type":"TPO_SESSION_REVERT","description":"Reverted session","key":"tpo_session","oldValue":"active","newValue":"reverted"}]"""

        /** The second entry of [GSON_TWO_ENTRIES] on its own, for seeding a store before a write. */
        val GSON_SECOND_ENTRY_ONLY =
            """[{"timestamp":1757856000000,"type":"TPO_SESSION_REVERT","description":"Reverted session","key":"tpo_session","oldValue":"active","newValue":"reverted"}]"""

        /**
         * Gson output when the text holds the five characters its default "HTML safe" writer
         * escapes. Advisor reasons really do contain these - `ISF > 90`, `target = 90`.
         */
        val GSON_HTML_ESCAPED =
            """[{"timestamp":1,"type":"TUNING_BUNDLE","description":"ISF \u003c 45 \u0026 target \u003d 90 \u0027tight\u0027","key":"k\u003e1","oldValue":"a\u003cb","newValue":"c\u0027d"}]"""

        /** Gson output for accents, a quote, a backslash, a newline and a tab. */
        val GSON_ACCENTS_AND_CONTROLS =
            """[{"timestamp":2,"type":"PROFILE_CHANGE","description":"Réglage élevé \"x\" \\y\nz\tw","key":"clé","oldValue":"é","newValue":"è"}]"""

        /** The plain text [GSON_ACCENTS_AND_CONTROLS] encodes. */
        const val ACCENTS_AND_CONTROLS_TEXT = "Réglage élevé \"x\" \\y\nz\tw"

        /** The timestamp of the entry the repository has just stamped with the wall clock. */
        fun stampOf(written: String): String =
            Regex("""^\[\{"timestamp":(\d+),""").find(written)?.groupValues?.get(1)
                ?: fail("no leading timestamp in $written")
    }

    private val cache = FakeCache()
    private val repository = AdvisorHistoryRepository(cache)

    private fun seed(json: String) {
        cache.strings[CACHE_ID] = json
    }

    private fun written(): String = cache.strings[CACHE_ID] ?: fail("nothing was written")

    /**
     * Everything stored, whatever it is stamped with.
     *
     * `getRecentActions` is the only public reader, so "all" has to be a window wide enough to
     * reach back past the epoch - some fixtures below are stamped 0, 1 or 2 because that is what
     * the captured Gson output holds. 24000 days is about 66 years, so the cutoff is negative.
     *
     * It cannot simply be a huge number: `getRecentActions` multiplies `days * 24 * 60 * 60` in
     * `Int` before it ever reaches a `Long`, so anything from 24856 days up silently overflows and
     * the window turns into a short or negative one. No caller passes more than a few weeks, so
     * that is not worth changing in a port, but a test must stay under it.
     */
    private fun allEntries(): List<AdvisorHistoryRepository.AdvisorActionLog> = repository.getRecentActions(24000)

    // ---------------------------------------------------------------------------------------
    // Reading what Gson wrote
    // ---------------------------------------------------------------------------------------

    @Test fun `reads every field of a history Gson wrote`() {
        seed(GSON_TWO_ENTRIES)

        val entries = allEntries()

        assertEquals(2, entries.size)
        assertEquals(1757942400000L, entries[0].timestamp)
        assertEquals(AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE, entries[0].type)
        assertEquals("Raised max SMB", entries[0].description)
        assertEquals("OApsAIMIMaxSMB", entries[0].key)
        assertEquals("1.0", entries[0].oldValue)
        assertEquals("1.2", entries[0].newValue)
        assertEquals(1757856000000L, entries[1].timestamp)
        assertEquals(AdvisorHistoryRepository.ActionType.TPO_SESSION_REVERT, entries[1].type)
        assertEquals("Reverted session", entries[1].description)
        assertEquals("tpo_session", entries[1].key)
        assertEquals("active", entries[1].oldValue)
        assertEquals("reverted", entries[1].newValue)
    }

    @Test fun `reads the escaped form Gson writes for html characters`() {
        seed(GSON_HTML_ESCAPED)

        val entries = allEntries()

        assertEquals(1, entries.size)
        assertEquals("ISF < 45 & target = 90 'tight'", entries[0].description)
        assertEquals("k>1", entries[0].key)
        assertEquals("a<b", entries[0].oldValue)
        assertEquals("c'd", entries[0].newValue)
    }

    @Test fun `reads accents and quotes and backslash and newline and tab as Gson wrote them`() {
        seed(GSON_ACCENTS_AND_CONTROLS)

        val entries = allEntries()

        assertEquals(1, entries.size)
        assertEquals(ACCENTS_AND_CONTROLS_TEXT, entries[0].description)
        assertEquals("clé", entries[0].key)
        assertEquals("é", entries[0].oldValue)
        assertEquals("è", entries[0].newValue)
    }

    /** Gson ignores a field it does not know, so a history from a newer build must still read. */
    @Test fun `ignores a field this build does not know`() {
        seed("""[{"timestamp":1,"type":"PROFILE_CHANGE","description":"d","key":"k","oldValue":"o","newValue":"n","brandNewField":42}]""")

        val entries = allEntries()

        assertEquals(1, entries.size)
        assertEquals("d", entries[0].description)
    }

    /** Gson coerces a quoted number to a number. Keep that, or a hand edited store loses its time. */
    @Test fun `reads a quoted timestamp as a number`() {
        seed("""[{"timestamp":"7","type":"PROFILE_CHANGE","description":"d","key":"k","oldValue":"o","newValue":"n"}]""")

        assertEquals(7L, allEntries().single().timestamp)
    }

    // ---------------------------------------------------------------------------------------
    // Writing what Gson wrote
    // ---------------------------------------------------------------------------------------

    @Test fun `writes the same bytes Gson wrote for ordinary entries`() {
        seed(GSON_SECOND_ENTRY_ONLY)

        repository.logAction(
            AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
            "OApsAIMIMaxSMB",
            "Raised max SMB",
            "1.0",
            "1.2"
        )

        val written = written()
        val expected = GSON_TWO_ENTRIES.replace("1757942400000", stampOf(written))
        assertEquals(expected, written)
    }

    @Test fun `writes html characters escaped the way Gson escaped them`() {
        repository.logAction(
            AdvisorHistoryRepository.ActionType.TUNING_BUNDLE,
            "k>1",
            "ISF < 45 & target = 90 'tight'",
            "a<b",
            "c'd"
        )

        val written = written()
        val expected = GSON_HTML_ESCAPED.replace("\"timestamp\":1,", "\"timestamp\":${stampOf(written)},")
        assertEquals(expected, written)
    }

    @Test fun `writes accents and quotes and backslash and newline and tab the way Gson wrote them`() {
        repository.logAction(
            AdvisorHistoryRepository.ActionType.PROFILE_CHANGE,
            "clé",
            ACCENTS_AND_CONTROLS_TEXT,
            "é",
            "è"
        )

        val written = written()
        val expected = GSON_ACCENTS_AND_CONTROLS.replace("\"timestamp\":2,", "\"timestamp\":${stampOf(written)},")
        assertEquals(expected, written)
    }

    @Test fun `a written entry reads back unchanged`() {
        repository.logAction(AdvisorHistoryRepository.ActionType.TPO_LLM_VETO, "PackA", "vetoed: risk > 0.8", "armed", "blocked")

        val entry = allEntries().single()

        assertEquals(AdvisorHistoryRepository.ActionType.TPO_LLM_VETO, entry.type)
        assertEquals("PackA", entry.key)
        assertEquals("vetoed: risk > 0.8", entry.description)
        assertEquals("armed", entry.oldValue)
        assertEquals("blocked", entry.newValue)
    }

    // ---------------------------------------------------------------------------------------
    // Documents that are not the happy case
    // ---------------------------------------------------------------------------------------

    /**
     * An action type written by a newer build, or one that was renamed.
     *
     * Gson kept such an entry and put a `null` in the non-null `type` field, so the next caller that
     * read `it.type` threw. Dropping the one entry and keeping the rest is the safer half of the
     * same trade, and the history around it survives.
     */
    @Test fun `drops only the entry whose action type is unknown`() {
        seed(
            """[{"timestamp":1,"type":"BRAND_NEW_KIND","description":"d1","key":"k1","oldValue":"o1","newValue":"n1"},""" +
                """{"timestamp":2,"type":"PROFILE_CHANGE","description":"d2","key":"k2","oldValue":"o2","newValue":"n2"}]"""
        )

        val entries = allEntries()

        assertEquals(1, entries.size)
        assertEquals(AdvisorHistoryRepository.ActionType.PROFILE_CHANGE, entries[0].type)
        assertEquals("d2", entries[0].description)
    }

    @Test fun `an empty store reads as no history`() {
        assertEquals(0, allEntries().size)
    }

    @Test fun `an empty array reads as no history`() {
        seed("[]")
        assertEquals(0, allEntries().size)
    }

    @Test fun `an empty string reads as no history`() {
        seed("")
        assertEquals(0, allEntries().size)
    }

    @Test fun `the text null reads as no history`() {
        seed("null")
        assertEquals(0, allEntries().size)
    }

    @Test fun `a malformed document reads as no history`() {
        seed("{not json")
        assertEquals(0, allEntries().size)
    }

    @Test fun `a truncated document reads as no history`() {
        seed("[{\"timestamp\":1,\"type\":\"PROFILE_CHANGE\"")
        assertEquals(0, allEntries().size)
    }

    @Test fun `an object where an array belongs reads as no history`() {
        seed("""{"timestamp":1}""")
        assertEquals(0, allEntries().size)
    }

    /** Gson left missing text fields null inside a non-null Kotlin field. Empty text is safer. */
    @Test fun `an entry with only its action type still reads`() {
        seed("""[{"type":"PROFILE_CHANGE"}]""")

        val entry = allEntries().single()

        assertEquals(0L, entry.timestamp)
        assertEquals("", entry.description)
        assertEquals("", entry.key)
        assertEquals("", entry.oldValue)
        assertEquals("", entry.newValue)
    }

    // ---------------------------------------------------------------------------------------
    // Order, retention and the cutoff
    // ---------------------------------------------------------------------------------------

    @Test fun `the newest action is first`() {
        repository.logAction(AdvisorHistoryRepository.ActionType.PROFILE_CHANGE, "k1", "first", "o", "n")
        repository.logAction(AdvisorHistoryRepository.ActionType.PROFILE_CHANGE, "k2", "second", "o", "n")

        val entries = allEntries()

        assertEquals("second", entries[0].description)
        assertEquals("first", entries[1].description)
    }

    @Test fun `only the newest fifty actions are kept`() {
        repeat(55) { index ->
            repository.logAction(AdvisorHistoryRepository.ActionType.PROFILE_CHANGE, "k$index", "d$index", "o", "n")
        }

        val entries = allEntries()

        assertEquals(50, entries.size)
        assertEquals("d54", entries[0].description)
        assertEquals("d5", entries[49].description)
    }

    @Test fun `actions older than the cutoff are left out`() {
        val now = aimiWallClockMs()
        val oneDayMs = 24 * 60 * 60 * 1000L
        seed(
            """[{"timestamp":${now - oneDayMs},"type":"PROFILE_CHANGE","description":"yesterday","key":"k","oldValue":"o","newValue":"n"},""" +
                """{"timestamp":${now - 10 * oneDayMs},"type":"PROFILE_CHANGE","description":"ten days ago","key":"k","oldValue":"o","newValue":"n"}]"""
        )

        val entries = repository.getRecentActions(3)

        assertEquals(1, entries.size)
        assertEquals("yesterday", entries[0].description)
    }
}
