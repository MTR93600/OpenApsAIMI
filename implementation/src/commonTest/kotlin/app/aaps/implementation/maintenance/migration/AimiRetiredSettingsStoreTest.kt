package app.aaps.implementation.maintenance.migration

import app.aaps.core.keys.AimiRetiredSettings
import app.aaps.core.keys.DoubleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step S3: the stored values of the deleted AIMI keys are removed, on the live store at start-up
 * (Android) and on a backup file before an import. A file holds every value as text.
 */
class AimiRetiredSettingsStoreTest {

    private val contextOpenAiKey = "aimi_context_llm_openai_key"
    private val kickerStep = "OApsAIMIKickerStep"
    private val lunch = DoubleKey.OApsAIMILunchFactor.key

    @Test
    fun theDeletedNamesAreKnown() {
        // The five context credentials are the reason this runs at all: a stored API key must not stay.
        assertTrue(contextOpenAiKey in AimiRetiredSettings.storageNames)
        assertTrue("aimi_context_llm_claude_key" in AimiRetiredSettings.storageNames)
        assertTrue(kickerStep in AimiRetiredSettings.storageNames)
    }

    @Test
    fun anOldBackupLosesTheDeletedValuesAndKeepsTheOthers() {
        val file = FileKeyValueStore(mapOf(contextOpenAiKey to "sk-secret", kickerStep to "0.2", lunch to "60.0"))

        val removed = removeRetiredAimiSettings(file)

        assertFalse(file.contains(contextOpenAiKey))
        assertFalse(file.contains(kickerStep))
        assertEquals("60.0", file.getAll()[lunch])
        assertEquals(setOf(contextOpenAiKey, kickerStep), removed.toSet())
    }

    @Test
    fun aLiveStoreLosesEveryDeletedValue() {
        val store = FileKeyValueStore(AimiRetiredSettings.storageNames.associateWith { "1" } + mapOf(lunch to 60.0))

        removeRetiredAimiSettings(store)

        assertEquals(mapOf<String, Any?>(lunch to 60.0), store.getAll())
    }

    @Test
    fun aStoreWithoutDeletedValuesIsNotTouched() {
        val store = FileKeyValueStore(mapOf(lunch to "60.0"))
        assertTrue(removeRetiredAimiSettings(store).isEmpty())
        assertEquals(mapOf<String, Any?>(lunch to "60.0"), store.getAll())
    }

    // ---------------------------------------------------------------- step S3b

    private val dueDateMs = "oa_aimi_pregnancy_due_date_ms"
    private val dueDateText = "aimi_pregnancy_due_date_string"

    /** 2026-01-01T00:00:00Z. */
    private val newYear2026Utc = 1_767_225_600_000L

    @Test
    fun theProtectedKeysTheOwnerRetiredAreRemoved() {
        val names = listOf("aimi_physio_debug_logs", "aimi_physio_llm_provider", "aimi_endo_suppression")
        assertTrue(AimiRetiredSettings.storageNames.containsAll(names + dueDateMs))
        val store = FileKeyValueStore(names.associateWith { "1" } + mapOf(lunch to "60.0"))

        removeRetiredAimiSettings(store)

        assertEquals(mapOf<String, Any?>(lunch to "60.0"), store.getAll())
    }

    @Test
    fun theOldDueDateIsCopiedIntoTheDueDateTextThenRemoved() {
        val store = FileKeyValueStore(mapOf(dueDateMs to newYear2026Utc))

        val removed = removeRetiredAimiSettings(store)

        assertEquals("2026-01-01", store.getAll()[dueDateText])
        assertFalse(store.contains(dueDateMs))
        assertEquals(listOf(dueDateMs), removed)
    }

    @Test
    fun aDueDateInABackupFileIsCopiedToo() {
        // A file holds every value as text.
        val file = FileKeyValueStore(mapOf(dueDateMs to newYear2026Utc.toString(), dueDateText to ""))
        removeRetiredAimiSettings(file)
        assertEquals("2026-01-01", file.getAll()[dueDateText])
        assertFalse(file.contains(dueDateMs))
    }

    @Test
    fun aLocalMidnightGivesTheSameDayInEveryTimeZone() {
        // Midnight of 1 January in Paris in winter (UTC+1) and in New York (UTC-5).
        assertEquals("2026-01-01", AimiRetiredSettings.dueDateText(newYear2026Utc - 1 * 3_600_000L))
        assertEquals("2026-01-01", AimiRetiredSettings.dueDateText(newYear2026Utc + 5 * 3_600_000L))
        assertEquals("2024-02-29", AimiRetiredSettings.dueDateText(1_709_164_800_000L))
    }

    @Test
    fun aDueDateTextThePersonTypedIsKept() {
        val store = FileKeyValueStore(mapOf(dueDateMs to newYear2026Utc, dueDateText to "2026-03-15"))
        removeRetiredAimiSettings(store)
        assertEquals("2026-03-15", store.getAll()[dueDateText])
        assertFalse(store.contains(dueDateMs))
    }

    @Test
    fun anEmptyOldDueDateCopiesNothing() {
        val store = FileKeyValueStore(mapOf(dueDateMs to 0L))
        removeRetiredAimiSettings(store)
        assertFalse(store.contains(dueDateText))
        assertFalse(store.contains(dueDateMs))
    }

    // ---------------------------------------------------------------- step D1

    @Test
    fun theXdripOneMinuteSwitchIsRemoved() {
        // Owner decision of step D1: the switch had no reader since the port, and every xDrip user keeps
        // all readings. Its stored value goes, the other values stay.
        val xdripOneMinute = "key_use_Aimi_xdripOM"
        val store = FileKeyValueStore(mapOf(xdripOneMinute to "true", lunch to "60.0"))

        val removed = removeRetiredAimiSettings(store)

        assertEquals(listOf(xdripOneMinute), removed)
        assertEquals(mapOf<String, Any?>(lunch to "60.0"), store.getAll())
    }
}
