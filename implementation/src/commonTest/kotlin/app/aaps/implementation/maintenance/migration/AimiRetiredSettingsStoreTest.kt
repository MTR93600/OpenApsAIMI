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
}
