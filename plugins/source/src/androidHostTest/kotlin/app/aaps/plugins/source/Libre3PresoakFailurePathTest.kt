package app.aaps.plugins.source

import android.app.Service
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ServiceCompat
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.source.activities.Libre3PresoakAction
import app.aaps.plugins.source.activities.Libre3StatusScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Failure paths of the three `runCatching` sites P5.2 could not execute.
 *
 * `cancelStaging` when `shutdown` throws is in [Libre3PresoakPluginTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Libre3PresoakFailurePathTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun installMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        // Clear while the test dispatcher is still installed. The action object captures
        // Dispatchers.Main the first time it is touched.
        Libre3PresoakAction.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a throw inside a pre-soak action leaves the previous message`() {
        Libre3PresoakAction.clear()
        Libre3PresoakAction.run { "kept" }
        assertThat(Libre3PresoakAction.message.value).isEqualTo("kept")

        Libre3PresoakAction.run { throw IllegalStateException("promote") }

        assertThat(Libre3PresoakAction.message.value).isEqualTo("kept")
    }

    @Test
    fun `onStartCommand stops the service when startForeground throws`() {
        Mockito.mockStatic(ServiceCompat::class.java).use { mocked ->
            mocked.`when`<Unit> {
                ServiceCompat.startForeground(any(), any(), any(), any())
            }.thenThrow(SecurityException("foreground type"))
            val service = Robolectric.buildService(Libre3SessionService::class.java).create().get()

            val result = service.onStartCommand(Intent(), 0, 1)

            assertThat(result).isEqualTo(Service.START_NOT_STICKY)
            assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "h2000dp")
    fun `the promote dialog maps a throw to the other rejection and does not claim success`() {
        var shown: String? = null
        compose.setContent {
            MaterialTheme {
                Libre3StatusScreen(
                    onBack = {},
                    onOpenLog = {},
                    onOpenStart = {},
                    presoakEnabled = true,
                    stagingStateFlow = MutableStateFlow(StagingState.READY),
                    stagingEvidenceFlow = MutableStateFlow(null),
                    stagingLifecycleFlow = MutableStateFlow(null),
                    stagingCurveFlow = MutableStateFlow(emptyList()),
                    formatGlucose = { it.toString() },
                    formatTime = { it.toString() },
                    formatAge = { it.toString() },
                    onPromote = { throw IllegalStateException("promote") },
                    onCancelStaging = {},
                    onSensorForgotten = {},
                    presoakMessageFlow = MutableStateFlow(null),
                    runPresoakAction = { work -> shown = runBlocking { work() } },
                )
            }
        }

        // The status list is taller than a phone window. A tall test window keeps the
        // promote button on screen, because touch injection misses a node that is off screen.
        compose.onNodeWithText("Promote this sensor").performClick()
        compose.onNodeWithText("Promote", substring = false).performClick()

        assertThat(shown).isEqualTo("The pre-soak sensor could not be promoted. Nothing was changed.")
        assertThat(shown).isNotEqualTo("Done. The pre-soak sensor now feeds the loop.")
    }
}
