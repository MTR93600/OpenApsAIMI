package app.aaps.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.chips.SensitivityUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardV2OverviewSummaryTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun presentsOverviewGlucoseAndInsulinValuesAndKeepsIobAction() {
        var iobClicked = false
        var contextClicked = false
        compose.setContent {
            MaterialTheme {
                DashboardV2OverviewSummary(
                    bgInfo = BgInfoData(
                        bgValue = 120.0,
                        bgText = "120",
                        bgRange = BgRange.IN_RANGE,
                        isOutdated = false,
                        timestamp = 1_700_000_000_000L,
                        trendArrow = TrendArrow.FLAT,
                        trendDescription = "Flat",
                        delta = 2.0,
                        deltaText = "+2",
                        shortAvgDelta = null,
                        shortAvgDeltaText = null,
                        longAvgDelta = null,
                        longAvgDeltaText = null,
                    ),
                    timeAgoText = "2 min ago",
                    iob = IobUiState(text = "2.66 U", iobTotal = 2.66),
                    cob = CobUiState(text = "43 g", cobValue = 43.0),
                    sensitivity = SensitivityUiState(),
                    onIobClick = { iobClicked = true },
                    contextActionLabel = "Open AIMI Context",
                    onOpenContext = { contextClicked = true },
                )
            }
        }

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithText("+2").assertIsDisplayed()
        compose.onNodeWithText("2 min ago").assertIsDisplayed()
        compose.onNodeWithText("2.66 U").assertIsDisplayed().performClick()
        compose.onNodeWithText("43 g").assertIsDisplayed()
        compose.onNodeWithText("Open AIMI Context").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assert(iobClicked)
            assert(contextClicked)
        }
    }

    @Test
    fun hidesContextActionWhenThereIsNoPlatformDestination() {
        compose.setContent {
            MaterialTheme {
                DashboardV2OverviewSummary(
                    bgInfo = null,
                    timeAgoText = "",
                    iob = IobUiState(),
                    cob = CobUiState(),
                    sensitivity = SensitivityUiState(),
                    onIobClick = {},
                )
            }
        }

        compose.onNodeWithText("Open AIMI Context").assertDoesNotExist()
    }
}
