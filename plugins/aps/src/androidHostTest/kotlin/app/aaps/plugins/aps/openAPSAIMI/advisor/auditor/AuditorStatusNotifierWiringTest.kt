package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import android.os.Looper
import androidx.lifecycle.Observer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusLiveData
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The wiring test for [AuditorStatusNotifier].
 *
 * `AuditorAIService` now takes the interface instead of the Android class, so it can live in shared
 * code. What that must not change is the chip: a `notifyUpdate()` from the service still has to
 * reach the real [AuditorStatusLiveData] and still has to call its observers, which is exactly what
 * `AuditorStatusBadgeSource` listens to.
 *
 * So this test uses the real [AuditorStatusLiveData], not a mock, and watches the `LiveData` the
 * Overview chip watches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuditorStatusNotifierWiringTest {

    private val statusLiveData = AuditorStatusLiveData()
    private val seen = mutableListOf<AuditorUIState>()
    private val observer = Observer<AuditorUIState> { seen += it }

    private val preferences: Preferences = mock()

    private fun service(): AuditorAIService = AuditorAIService(
        preferences = preferences,
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = mock<GeminiModelResolver>(),
        auditorStatusNotifier = statusLiveData,
        aimiHttp = mock<AimiHttp>()
    )

    @Before
    fun setUp() {
        AuditorVerdictCache.clear()
        whenever(preferences.get(StringKey.AimiAdvisorOpenAIKey)).thenReturn("")
        statusLiveData.uiState.observeForever(observer)
        idleMainLooper()
        seen.clear()
    }

    @After
    fun tearDown() {
        statusLiveData.uiState.removeObserver(observer)
        AuditorVerdictCache.clear()
    }

    @Test
    fun `a notifyUpdate from the service reaches the real LiveData observer`() {
        val verdict = runBlocking {
            service().getVerdict(mock<AuditorInput>(), AuditorAIService.Provider.OPENAI)
        }
        idleMainLooper()

        assertNull(verdict)
        assertEquals(1, seen.size)
        assertEquals(AuditorUIState.StateType.ERROR, seen.single().type)
        assertEquals("No API key configured", seen.single().statusMessage)
        assertEquals(
            AuditorStatusTracker.Status.OFFLINE_NO_APIKEY,
            AuditorStatusTracker.getStatus().first
        )
    }

    @Test
    fun `the notifier the service holds is the same LiveData the chip observes`() {
        val notifier: AuditorStatusNotifier = statusLiveData
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)

        notifier.notifyUpdate()
        idleMainLooper()

        assertEquals(1, seen.size)
        assertEquals(AuditorUIState.StateType.ERROR, seen.single().type)
        assertEquals("No network connection", seen.single().statusMessage)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
