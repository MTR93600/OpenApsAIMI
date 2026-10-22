package app.aaps.core.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DashboardGraphComposeControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun statsAndTreatmentsControlsInvokeTheirExplicitHandlers() {
        var statsOpened = false
        var treatmentsOpened = false

        compose.setContent {
            MaterialTheme {
                DashboardGraphComposeControls(
                    state = DashboardGraphControlsUiState(
                        selectedRangeHours = 6,
                        onSelectRange = {},
                        onOpenStats = { statsOpened = true },
                        onOpenTreatments = { treatmentsOpened = true },
                    ),
                    strings = controlStrings,
                )
            }
        }

        compose.onNodeWithText("Statistics").assertIsDisplayed().performClick()
        compose.onNodeWithText("Treatments").assertIsDisplayed().performClick()

        assert(statsOpened)
        assert(treatmentsOpened)
    }

    @Test
    fun unavailableDestinationsAreNotPresentedAsClickableControls() {
        compose.setContent {
            MaterialTheme {
                DashboardGraphComposeControls(
                    state = DashboardGraphControlsUiState(
                        selectedRangeHours = 6,
                        onSelectRange = {},
                    ),
                    strings = controlStrings,
                )
            }
        }

        compose.onNodeWithText("Statistics").assertDoesNotExist()
        compose.onNodeWithText("Treatments").assertDoesNotExist()
    }

    private val controlStrings = DashboardGraphCardStrings(
        graphHeader = "Graph",
        bloodGlucoseA11y = "Glucose",
        scenarioFloorLabel = "Floor",
        scenarioBestLabel = "Best",
        predictionShort = "Prediction",
        predictionIobLabel = "IOB",
        predictionCobLabel = "COB",
        predictionAbsorbedCobLabel = "Absorbed COB",
        predictionUamLabel = "UAM",
        predictionZeroTempLabel = "Zero temp",
        smbLegend = "SMB",
        tbrLegend = "Temporary basal",
        scenarioNearBgHint = "Scenario",
        smbToast = { it },
        statsButton = "Statistics",
        range6h = "6h",
        range12h = "12h",
        range18h = "18h",
        range24h = "24h",
        treatmentsLabel = "Treatments",
    )
}
