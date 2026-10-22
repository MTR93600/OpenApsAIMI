package app.aaps.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassOverviewMetricsCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysSuppliedOverviewValuesAndRoutesOnlyToExistingDestinations() {
        var statsOpened = false
        var treatmentsOpened = false
        compose.setContent {
            MaterialTheme {
                GlassOverviewMetricsCard(
                    glucoseLabel = "Glucose",
                    bgInfo = BgInfoData(
                        bgValue = 6.4,
                        bgText = "6.4",
                        bgRange = BgRange.IN_RANGE,
                        isOutdated = false,
                        timestamp = 1_700_000_000_000L,
                        trendArrow = TrendArrow.FLAT,
                        trendDescription = "Flat",
                        delta = 0.2,
                        deltaText = "+0.2",
                        shortAvgDelta = null,
                        shortAvgDeltaText = null,
                        longAvgDelta = null,
                        longAvgDeltaText = null,
                    ),
                    timeAgoText = "3 min ago",
                    iobLabel = "IOB",
                    iobText = "1.25 U",
                    cobLabel = "COB",
                    cobText = "18 g",
                    sensitivityLabel = "Sensitivity",
                    sensitivityText = "1.0",
                    statisticsLabel = "Statistics",
                    treatmentsLabel = "Treatments",
                    onStatistics = { statsOpened = true },
                    onTreatments = { treatmentsOpened = true },
                )
            }
        }

        listOf("6.4", "+0.2", "3 min ago", "1.25 U", "18 g", "1.0").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Statistics").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Treatments").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assert(statsOpened)
            assert(treatmentsOpened)
        }
    }
}
