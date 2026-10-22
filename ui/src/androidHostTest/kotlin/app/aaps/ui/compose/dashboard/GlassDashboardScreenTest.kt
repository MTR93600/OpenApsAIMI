package app.aaps.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class GlassDashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersGlassTitleAndKeepsSuppliedSharedHomeContentAndNavigation() {
        var navigationClicked = false
        compose.setContent {
            MaterialTheme {
                GlassDashboardScreen(
                    title = "GLASS",
                    settingsLabel = "Settings",
                    onOpenSettings = { navigationClicked = true },
                    dashboardContent = { androidx.compose.material3.Text("shared dashboard data") },
                )
            }
        }

        compose.onNodeWithText("GLASS").assertIsDisplayed()
        compose.onNodeWithText("shared dashboard data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assert(navigationClicked) }
    }
}
