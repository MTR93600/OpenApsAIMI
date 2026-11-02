package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiContextLlm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [AimiRemoteContextImpl] with a real [ContextManager]: the whole master path, from the note text
 * to the stored context, including the PIN check inside the manager.
 */
class AimiRemoteContextImplTest {

    private val nowMs = System.currentTimeMillis()
    private val json = """{"type":"Activity","act":"CARDIO","int":"HIGH","dur":3600000,"start":$nowMs,"conf":0.9}"""

    private fun newSink(configuredPin: String): Pair<AimiRemoteContextImpl, ContextManager> {
        val values = mutableMapOf<String, Any>()
        val preferences: Preferences = mock<Preferences>().also { prefs ->
            whenever(prefs.get(any<StringPreferenceKey>())).thenAnswer { invocation ->
                val key = invocation.getArgument<StringPreferenceKey>(0)
                if (key.key == AimiStringKey.RemoteControlPin.key) configuredPin
                else values[key.key] as? String ?: key.defaultValue
            }
            whenever(prefs.get(any<BooleanPreferenceKey>())).thenAnswer { invocation ->
                invocation.getArgument<BooleanPreferenceKey>(0).defaultValue
            }
            whenever(prefs.put(any<StringPreferenceKey>(), any())).thenAnswer { invocation ->
                values[invocation.getArgument<StringPreferenceKey>(0).key] = invocation.getArgument<String>(1)
                Unit
            }
        }
        val dateUtil: DateUtil = mock()
        whenever(dateUtil.now()).thenReturn(nowMs)
        val logger: AAPSLogger = mock()
        val manager = ContextManager(
            contextLLMClient = mock<AimiContextLlm>(),
            contextParser = mock(),
            preferences = preferences,
            aapsLogger = logger,
            persistenceLayer = mock<PersistenceLayer>(),
            dateUtil = dateUtil,
        )
        return AimiRemoteContextImpl(manager, logger) to manager
    }

    @Test
    fun `note with the right PIN is stored once`() {
        val (sink, manager) = newSink("4242")
        assertTrue(sink.applyNightscoutNote("AIMI_CONTEXT:CTX_A:PIN:4242:$json"))
        assertNotNull(manager.getIntent("CTX_A"))
        // The same note again, as on the next Nightscout load: known already.
        assertFalse(sink.applyNightscoutNote("AIMI_CONTEXT:CTX_A:PIN:4242:$json"))
        assertEquals(1, manager.getAllIntents().size)
    }

    @Test
    fun `note with a wrong PIN is not stored`() {
        val (sink, manager) = newSink("4242")
        assertFalse(sink.applyNightscoutNote("AIMI_CONTEXT:CTX_A:PIN:0000:$json"))
        assertNull(manager.getIntent("CTX_A"))
    }

    @Test
    fun `legacy note without PIN is not stored`() {
        val (sink, manager) = newSink("4242")
        assertFalse(sink.applyNightscoutNote("AIMI_CONTEXT:CTX_A:$json"))
        assertNull(manager.getIntent("CTX_A"))
    }

    @Test
    fun `nothing is stored when the master has no PIN`() {
        val (sink, manager) = newSink("")
        assertFalse(sink.applyNightscoutNote("AIMI_CONTEXT:CTX_A:PIN::$json"))
        assertTrue(manager.getAllIntents().isEmpty())
    }
}
