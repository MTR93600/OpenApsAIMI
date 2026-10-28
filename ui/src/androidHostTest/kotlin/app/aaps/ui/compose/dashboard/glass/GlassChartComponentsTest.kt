package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke test: the Glass BG and IOB charts render with sample data (the canvas draws text through
 * `TextMeasurer`, so a broken text path would fail here) and show their header values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassChartComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = GlassChartState(
        bgReadings = listOf(
            BgReadingPoint(progress = 0f, value = 95f),
            BgReadingPoint(progress = 0.5f, value = 190f),
            BgReadingPoint(progress = 1f, value = 142f),
        ),
        iobReadings = listOf(
            IobReadingPoint(progress = 0f, iob = 0.4f),
            IobReadingPoint(progress = 1f, iob = 1.25f),
        ),
        treatments = listOf(
            TreatmentPoint(progress = 0.4f, isCarb = true, label = "45 g", timestamp = 1_000L),
            TreatmentPoint(progress = 0.45f, isCarb = false, label = "3.00 U", timestamp = 2_000L),
        ),
        predictions = listOf(
            PredictionPoint(progress = 0f, value = 142f, type = PredictionType.IOB),
            PredictionPoint(progress = 1f, value = 110f, type = PredictionType.IOB),
        ),
        iobPredictions = listOf(
            IobReadingPoint(progress = 0f, iob = 1.25f),
            IobReadingPoint(progress = 1f, iob = 0.3f),
        ),
        basalReadings = listOf(BasalReadingPoint(progress = 0f, rateUh = 0.8f), BasalReadingPoint(progress = 0.6f, rateUh = 1.6f)),
        profileBasalReadings = listOf(BasalReadingPoint(progress = 0f, rateUh = 0.8f)),
        maxBasalRateUh = 1.6f,
        historyFraction = 0.75f,
        currentBgValue = 142f,
        currentIob = 1.25f,
    )

    @Test
    fun bgAndIobChartsRenderWithSampleData() {
        compose.setContent {
            MaterialTheme {
                Column {
                    BgChartCard(
                        readings = sample.bgReadings,
                        treatments = sample.treatments,
                        timeRangeHours = sample.rangeHours,
                        currentBgValue = sample.currentBgValue,
                        lowLine = sample.lowLine,
                        highLine = sample.highLine,
                        predictions = sample.predictions,
                        historyFraction = sample.historyFraction,
                        axisMinValue = sample.axisMinValue,
                        axisHeadroom = sample.axisHeadroom,
                        basalReadings = sample.basalReadings,
                        profileBasalReadings = sample.profileBasalReadings,
                        maxBasalRateUh = sample.maxBasalRateUh,
                        formatValue = { it.toInt().toString() },
                        isDark = true,
                    )
                    IobChartCard(
                        iobReadings = sample.iobReadings,
                        predictions = sample.iobPredictions,
                        currentIob = sample.currentIob,
                        timeRangeHours = sample.rangeHours,
                        historyFraction = sample.historyFraction,
                        isDark = true,
                    )
                }
            }
        }

        compose.onNodeWithText("BG").assertIsDisplayed()
        compose.onNodeWithText("142").assertIsDisplayed()
        compose.onNodeWithText("IOB").assertIsDisplayed()
        compose.onNodeWithText("1.25 U").assertIsDisplayed()
    }

    @Test
    fun bgChartShowsPlaceholderWithoutReadingsInLightMode() {
        compose.setContent {
            MaterialTheme {
                BgChartCard(
                    readings = emptyList(),
                    treatments = emptyList(),
                    timeRangeHours = 6,
                    currentBgValue = 0f,
                    lowLine = 70f,
                    highLine = 180f,
                    predictions = emptyList(),
                    historyFraction = 0.75f,
                    axisMinValue = 30f,
                    axisHeadroom = 40f,
                    formatValue = { it.toInt().toString() },
                    isDark = false,
                )
            }
        }

        compose.onNodeWithText("BG").assertIsDisplayed()
        compose.onNodeWithText("--").assertIsDisplayed()
    }
}
