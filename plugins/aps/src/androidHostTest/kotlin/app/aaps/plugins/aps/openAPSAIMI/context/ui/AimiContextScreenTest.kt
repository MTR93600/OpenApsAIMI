package app.aaps.plugins.aps.openAPSAIMI.context.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextStoreMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AimiContextScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var contextManager: ContextManager
    private lateinit var preferences: Preferences
    private lateinit var healthContextRepository: HealthContextRepository
    private lateinit var physioContextStore: AIMIPhysioContextStoreMTR
    private lateinit var dateUtil: DateUtil

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
        contextManager = mock()
        preferences = mock()
        healthContextRepository = mock()
        physioContextStore = mock()
        dateUtil = mock()

        whenever(contextManager.getAllIntents()).thenReturn(emptyMap())
        whenever(preferences.get(BooleanKey.OApsAIMIContextEnabled)).thenReturn(true)
        whenever(preferences.get(BooleanKey.OApsAIMIContextLLMEnabled)).thenReturn(false)
        whenever(preferences.getSyncKeys()).thenReturn(emptyList())
        whenever(dateUtil.now()).thenReturn(1_700_000_000_000L)
    }

    @Test
    fun emptyContextScreenShowsItsMainControls() {
        compose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                MaterialTheme {
                    AimiContextScreen(
                        contextManager = contextManager,
                        preferences = preferences,
                        healthContextRepository = healthContextRepository,
                        physioContextStore = physioContextStore,
                        aapsLogger = mock<AAPSLogger>(),
                        dateUtil = dateUtil,
                        textResolver = mock<TextResolver>(),
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.context_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_context_intro)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_context_send_button)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_context_clear_all_button)).assertIsNotEnabled()
    }
}
