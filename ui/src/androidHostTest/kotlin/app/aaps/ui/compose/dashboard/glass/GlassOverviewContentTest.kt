package app.aaps.ui.compose.dashboard.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.ui.UiStringIds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Glass home body: the status card renders, the glucose opens the loop, Tools then a tile runs
 * that tile, and the notifications are behind the floating bell. The view models are not involved; [GlassOverviewContent] is stateless.
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
    private var showIobChart by mutableStateOf(true)
    private val iobChartChanges = mutableListOf<Boolean>()
    /** The notification sheet shows the time of each notification; the app provides this. */
    private val dateUtil: DateUtil = mock { on { timeString(any<Long>()) } doReturn "14:05" }
    private var notifications by mutableStateOf<List<AapsNotification>>(emptyList())

    private val urgent = AapsNotification(
        id = NotificationId.entries.first(),
        instanceKey = 0,
        text = "Pump not reachable",
        level = NotificationLevel.URGENT,
        date = 0L,
    )

    @Before
    fun setUp() {
        // MainApp does this in production; without it the UiStrings would render as their names.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
    }

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGlassHeroCommands provides commands, LocalDateUtil provides dateUtil) {
                    GlassOverviewContent(
                        state = GlassUiState(currentBg = "123", deltaText = "Δ +2", loopStatusText = "Closed Loop", stepsText = "4200", hrText = "72"),
                        chartState = GlassChartState(),
                        isDark = true,
                        nowMs = 0L,
                        onSelectRange = {},
                        selectedPills = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id },
                        onTogglePill = { _, _ -> },
                        showIobChart = showIobChart,
                        onShowIobChartChange = { iobChartChanges += it; showIobChart = it },
                        selectedTools = DashboardV2ToolAction.entries.toSet(),
                        onToggleTool = { _, _ -> },
                        availablePluginClassNames = emptySet(),
                        onToolAction = { toolActions += it },
                        formatBgValue = { it.toInt().toString() },
                        notifications = notifications,
                        onDismissNotification = {},
                        onNotificationActionClick = {},
                    )
                }
            }
        }
    }

    /** The bell replaces the old list at the bottom of the page, which was off screen on every phone. */
    @Test
    fun noNotificationShowsNoBell() {
        setContent()
        compose.onNodeWithContentDescription("Notifications: 0").assertDoesNotExist()
        compose.onNodeWithText("Pump not reachable").assertDoesNotExist()
    }

    @Test
    fun aNotificationShowsTheBellAndATapOpensTheList() {
        notifications = listOf(urgent)
        setContent()
        // Not on the page itself any more: only in the sheet the bell opens.
        compose.onNodeWithText("Pump not reachable").assertDoesNotExist()
        compose.onNodeWithContentDescription("Notifications: 1").assertIsDisplayed().performClick()
        compose.onNodeWithText("Pump not reachable").assertIsDisplayed()
    }

    @Test
    fun theBellGoesAwayWhenTheLastNotificationIsGone() {
        notifications = listOf(urgent)
        setContent()
        compose.onNodeWithContentDescription("Notifications: 1").assertIsDisplayed()
        notifications = emptyList()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Notifications: 1").assertDoesNotExist()
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

    @Test
    fun personalizeIobChartBoxTurnsTheChartOff() {
        setContent()
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("Personalize").performClick()
        // The section is below the pills, so it is scrolled to first.
        compose.onNodeWithText("Charts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("IOB chart").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), iobChartChanges) }
    }

    @Test
    fun iobChartIsLeftOutWhenTurnedOff() {
        setContent()
        val withChart = compose.onAllNodesWithText("IOB").fetchSemanticsNodes().size
        showIobChart = false
        compose.waitForIdle()
        val withoutChart = compose.onAllNodesWithText("IOB").fetchSemanticsNodes().size
        assertEquals(withChart - 1, withoutChart)
    }
}
