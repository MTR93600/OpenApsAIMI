package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusStateHolder
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The service half of the auditor status wiring.
 *
 * [AuditorAIService] takes [AuditorStatusNotifier], not the holder, so it can live in shared code.
 * This checks that a call it gives up on still ends as a published state the Overview chip can
 * read, through the real [AuditorStatusStateHolder].
 *
 * It stays on the JVM because the service needs a [Preferences] and an [AuditorInput], and both are
 * built here with Mockito, which has no Kotlin/Native build. The holder's own half of the wiring is
 * in `commonTest` (`AuditorStatusNotifierWiringTest`) and does run on iOS.
 */
class AuditorAIServiceNotifiesTest {

    private val statusStateHolder = AuditorStatusStateHolder()

    private val preferences: Preferences = mock()

    private fun service(): AuditorAIService = AuditorAIService(
        preferences = preferences,
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = mock<GeminiModelResolver>(),
        auditorStatusNotifier = statusStateHolder,
        aimiHttp = mock<AimiHttp>()
    )

    @Before
    fun setUp() {
        AuditorVerdictCache.clear()
        whenever(preferences.get(StringKey.AimiAdvisorOpenAIKey)).thenReturn("")
    }

    @After
    fun tearDown() {
        AuditorVerdictCache.clear()
    }

    @Test
    fun `a notifyUpdate from the service reaches the published state`() {
        val verdict = runBlocking {
            service().getVerdict(mock<AuditorInput>(), AuditorAIService.Provider.OPENAI)
        }

        assertNull(verdict)
        assertEquals(AuditorUIState.StateType.ERROR, statusStateHolder.uiState.value.type)
        assertEquals("No API key configured", statusStateHolder.uiState.value.statusMessage)
        assertEquals(
            AuditorStatusTracker.Status.OFFLINE_NO_APIKEY,
            AuditorStatusTracker.getStatus().first
        )
    }
}
