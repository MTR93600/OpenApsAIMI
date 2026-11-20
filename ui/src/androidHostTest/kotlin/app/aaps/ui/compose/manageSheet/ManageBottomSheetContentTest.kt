package app.aaps.ui.compose.manageSheet

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.aaps.core.interfaces.configuration.Config as AppConfig
import app.aaps.core.ui.R as CoreUiR

/** Robolectric composable test for [ManageBottomSheetContent]: header renders (actions shown) + cancel-TB fires. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ManageBottomSheetContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val config: AppConfig = mock()

    private lateinit var manageHeader: String

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        manageHeader = ctx.getString(CoreUiR.string.manage)
    }

    /** The sheet reads the chosen home: its items are Glass cards over a Glass or Essential home. */
    private fun preferences(skin: String): Preferences = mock {
        on { observe(StringKey.GeneralDashboardSkin) } doReturn MutableStateFlow(skin)
        on { observe(StringKey.GeneralDarkMode) } doReturn MutableStateFlow("dark")
    }

    @Test
    fun rendersHeaderAndActions() = rendersHeaderAndActions(skin = "overview")

    @Test
    fun rendersHeaderAndActionsOverAGlassHome() = rendersHeaderAndActions(skin = "glass")

    private fun rendersHeaderAndActions(skin: String) {
        compose.setContent {
            CompositionLocalProvider(LocalConfig provides config, LocalPreferences provides preferences(skin)) {
                MaterialTheme {
                ManageBottomSheetContent(
                    showTempTarget = true,
                    showTempBasal = true,
                    showCancelTempBasal = true,
                    showExtendedBolus = true,
                    showCancelExtendedBolus = true,
                    showBatteryChange = true,
                    showFill = true,
                    cancelTempBasalText = "CancelTB",
                    cancelExtendedBolusText = "CancelEB",
                    customActions = emptyList(),
                    onCancelTempBasalClick = {}
                )
                }
            }
        }
        compose.onNodeWithText(manageHeader).assertIsDisplayed()
    }
}
