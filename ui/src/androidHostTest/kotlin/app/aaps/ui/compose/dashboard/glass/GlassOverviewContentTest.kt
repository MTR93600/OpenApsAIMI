package app.aaps.ui.compose.dashboard.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.ui.UiStringIds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Glass home body: the status card renders, the glucose opens the loop, and Tools then a tile runs
 * that tile. The view models are not involved; [GlassOverviewContent] is stateless.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassOverviewContentTest {

    @get:Rule
    val compose = createComposeRule()

    private class RecordingCommands : GlassHeroCommands {

        val calls = mutableListOf<String>()
        override fun openLoop() { calls += "loop" }
        override fun openLoopDashboard() { calls += "loopDashboard" }
        override fun openTarget() { calls += "target" }
        override fun openInsulin() { calls += "insulin" }
        override fun openPump() { calls += "pump" }
        override fun openCannula() { calls += "cannula" }
        override fun openBattery() { calls += "battery" }
        override fun openBasal() { calls += "basal" }
        override fun openSensorInsert() { calls += "sensorInsert" }
        override fun openSensorQuality() { calls += "sensorQuality" }
        override fun openStatsScreen() { calls += "stats" }
        override fun openTreatmentsScreen() { calls += "treatments" }
    }

    private val commands = RecordingCommands()
    private val toolActions = mutableListOf<DashboardV2ToolAction>()

    @Before
    fun setUp() {
        // MainApp does this in production; without it the UiStrings would render as their names.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
    }

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGlassHeroCommands provides commands) {
                    GlassOverviewContent(
                        state = GlassUiState(currentBg = "123", deltaText = "Δ +2", loopStatusText = "Closed Loop", stepsText = "4200", hrText = "72"),
                        chartState = GlassChartState(),
                        isDark = true,
                        nowMs = 0L,
                        onSelectRange = {},
                        selectedPills = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id },
                        onTogglePill = { _, _ -> },
                        selectedTools = DashboardV2ToolAction.entries.toSet(),
                        onToggleTool = { _, _ -> },
                        availablePluginClassNames = emptySet(),
                        onToolAction = { toolActions += it },
                        formatBgValue = { it.toInt().toString() },
                        notifications = emptyList(),
                        onDismissNotification = {},
                        onNotificationActionClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun statusCardRendersAndGlucoseOpensTheLoop() {
        setContent()
        compose.onNodeWithText("123").assertIsDisplayed().performClick()
        compose.onNodeWithText("Closed Loop").assertIsDisplayed()
        compose.onNodeWithText("4200 · 72 bpm").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("loop"), commands.calls) }
    }

    @Test
    fun toolsThenTileRunsTheTileAndClosesTheOverlay() {
        setContent()
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("Personalize").assertIsDisplayed()
        compose.onNodeWithText("Profile").performClick()
        compose.runOnIdle { assertEquals(listOf(DashboardV2ToolAction.PROFILE), toolActions) }
        compose.onNodeWithText("Personalize").assertDoesNotExist()
    }

    @Test
    fun personalizeOpensFromToolsAndOkGoesBackToTools() {
        setContent()
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("Personalize").performClick()
        compose.onNodeWithText("Main screen").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Main screen").assertDoesNotExist()
        compose.onNodeWithText("Personalize").assertIsDisplayed()
    }
}
