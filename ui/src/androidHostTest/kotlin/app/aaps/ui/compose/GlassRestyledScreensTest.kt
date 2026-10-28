package app.aaps.ui.compose

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.TB
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.ui.UiStringIds
import app.aaps.ui.compose.stats.StatsScreen
import app.aaps.ui.compose.stats.viewmodels.StatsViewModel
import app.aaps.ui.compose.treatments.TreatmentsScreen
import app.aaps.ui.compose.treatments.viewmodels.TreatmentsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import app.aaps.core.ui.R as CoreUiR
import app.aaps.ui.R as UiR

/**
 * Stats and Treatments are drawn in the Glass look for every skin. These smoke tests render both
 * screens in dark and in light mode, check that the Glass gradient is behind them (the top pixel
 * has the Glass colour of that mode) and that the header title is shown.
 *
 * The view models are real, but their loads never run: Main is a StandardTestDispatcher that is
 * never advanced, so each screen stays in its loading state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassRestyledScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences: Preferences = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val dateUtil: DateUtil = mock()
    private val rh: ResourceHelper = mock()
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        // MainApp does this in production; without it a UiStrings ref renders as its raw name.
        TextRefIdRegistry.register("ui") { name -> UiStringIds.idOf(name) }
        ctx = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(rh.gs(any<TextRef>())).thenReturn("")
        whenever(persistenceLayer.observeChanges(BS::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(CA::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(TB::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(EB::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(EPS::class)).thenReturn(emptyFlow())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun darkMode(isDark: Boolean) {
        whenever(preferences.observe(StringKey.GeneralDarkMode)).thenReturn(MutableStateFlow(if (isDark) "dark" else "light"))
    }

    private fun statsViewModel() = StatsViewModel(
        mock(), mock(), mock(), persistenceLayer, rh, mock(), dateUtil, mock(), preferences
    )

    private fun treatmentsViewModel(): TreatmentsViewModel {
        val activePlugin: ActivePlugin = mock()
        val pump: PumpWithConcentration = mock()
        val pumpDescription: PumpDescription = mock()
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(pumpDescription)
        return TreatmentsViewModel(
            persistenceLayer, mock(), mock(), activePlugin, mock(), rh, mock(), dateUtil, mock(), mock(), mock(),
            mock(), mock(), mock(), mock(), mock(), mock()
        )
    }

    private fun showStats() {
        val viewModel = statsViewModel()
        compose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences, LocalDateUtil provides dateUtil) {
                StatsScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
    }

    private fun showTreatments() {
        val viewModel = treatmentsViewModel()
        compose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences, LocalDateUtil provides dateUtil) {
                TreatmentsScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
    }

    /** The gradient starts at the top of the screen, so the first pixel row has its start colour. */
    private fun assertGlassBackground(isDark: Boolean) {
        val pixel = compose.onRoot().captureToImage().toPixelMap()[1, 1]
        assertSameColor(pixel, GlassColors.screenBgTop(isDark))
    }

    private fun assertSameColor(actual: Color, expected: Color) {
        val tolerance = 0.02f
        assertThat(abs(actual.red - expected.red)).isLessThan(tolerance)
        assertThat(abs(actual.green - expected.green)).isLessThan(tolerance)
        assertThat(abs(actual.blue - expected.blue)).isLessThan(tolerance)
    }

    @Test
    fun statsScreenRendersOnTheDarkGlassBackground() {
        darkMode(true)
        showStats()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.statistics)).assertIsDisplayed()
        assertGlassBackground(isDark = true)
    }

    @Test
    fun statsScreenRendersOnTheLightGlassBackground() {
        darkMode(false)
        showStats()
        compose.onNodeWithText(ctx.getString(CoreUiR.string.statistics)).assertIsDisplayed()
        assertGlassBackground(isDark = false)
    }

    @Test
    fun treatmentsScreenRendersOnTheDarkGlassBackground() {
        darkMode(true)
        showTreatments()
        compose.onNodeWithText(ctx.getString(UiR.string.carbs_and_bolus)).assertIsDisplayed()
        assertGlassBackground(isDark = true)
    }

    @Test
    fun treatmentsScreenRendersOnTheLightGlassBackground() {
        darkMode(false)
        showTreatments()
        compose.onNodeWithText(ctx.getString(UiR.string.carbs_and_bolus)).assertIsDisplayed()
        assertGlassBackground(isDark = false)
    }
}
