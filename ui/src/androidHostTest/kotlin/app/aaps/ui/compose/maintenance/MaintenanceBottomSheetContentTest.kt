package app.aaps.ui.compose.maintenance

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.ui.R as CoreUiR

/** Robolectric composable test for [MaintenanceBottomSheetContent] (all-default params): renders the
 *  file-management section + rows and fires the Log-settings row callback — headless JVM, no emulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaintenanceBottomSheetContentTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fileManagement: String
    private lateinit var logSettings: String
    private lateinit var stopAaps: String
    private lateinit var resumeAaps: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        fileManagement = ctx.getString(CoreUiR.string.file_management)
        logSettings = ctx.getString(CoreUiR.string.nav_logsettings)
        stopAaps = ctx.getString(CoreUiR.string.maintenance_stop_aaps)
        resumeAaps = ctx.getString(CoreUiR.string.maintenance_resume_aaps)
    }

    @Test
    fun rendersSectionsAndFiresLogSettingsRow() {
        var logSettingsClicked = false
        compose.setContent {
            MaterialTheme {
                MaintenanceBottomSheetContent(onLogSettingsClick = { logSettingsClicked = true })
            }
        }

        // The sheet scrolls, and the stop and transfer rows come first, so later rows may be below the fold.
        compose.onNodeWithText(fileManagement).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(logSettings).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(logSettings).performClick()
        assertThat(logSettingsClicked).isTrue()
    }

    @Test
    fun offersStopWhileRunningAndStartAgainWhileStopped() {
        var stopClicked = false
        var resumeClicked = false
        var stopped by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                MaintenanceBottomSheetContent(
                    isAapsStopped = stopped,
                    onStopAapsClick = { stopClicked = true },
                    onResumeAapsClick = { resumeClicked = true }
                )
            }
        }

        compose.onNodeWithText(stopAaps).assertIsDisplayed().performClick()
        assertThat(stopClicked).isTrue()
        compose.onNodeWithText(resumeAaps).assertDoesNotExist()

        stopped = true
        compose.onNodeWithText(resumeAaps).assertIsDisplayed().performClick()
        assertThat(resumeClicked).isTrue()
        compose.onNodeWithText(stopAaps).assertDoesNotExist()
    }
}
