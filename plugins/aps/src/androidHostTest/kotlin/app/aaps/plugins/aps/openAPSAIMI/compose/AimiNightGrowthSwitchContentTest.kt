package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.PreferenceSectionState
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.SectionLevel
import app.aaps.core.ui.compose.preference.addPreferenceContent
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.kotlin.doReturn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.keys.BooleanKey
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.preference.LocalPreferenceTheme
import app.aaps.core.ui.compose.preference.preferenceTheme
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Step A1: the night growth switch row draws the state it is given (the state the loop uses) and the
 * line that says why, and a tap reaches the row's tap action. The night growth page draws this row
 * both in AIMI's own page and in the page that the app search opens (shared renderer). The rule for the state and for the tap
 * is tested in `AimiNightGrowthSwitchRowTest` (shared code).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Fully qualified: the app `Config` interface is imported under the same simple name.
@org.robolectric.annotation.Config(sdk = [35])
class AimiNightGrowthSwitchContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
    }

    private fun show(state: NightGrowthState, onTap: (Boolean) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalPreferenceTheme provides preferenceTheme(),
                    // A master phone: the title shows no sync badge.
                    LocalConfig provides mock<Config>(),
                ) {
                    AimiNightGrowthSwitchContent(
                        key = BooleanKey.OApsAIMINightGrowthEnabled,
                        state = state,
                        enabled = true,
                        onTap = onTap,
                    )
                }
            }
        }
    }

    @Test
    fun aChildWhoseSwitchWasNeverSetSeesTheSwitchOnSetByTheAge() {
        val taps = mutableListOf<Boolean>()
        show(NightGrowthState.ON_BY_AGE) { taps += it }

        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.aimi_ngr_switch_reason_age)).assertExists()

        compose.onNode(isToggleable()).performClick()
        // The tap carries the state that was on screen (on), so the row stores off.
        assertEquals(listOf(true), taps)
    }

    @Test
    fun withNoAgeTheSwitchIsOffAndSaysTheAgeIsNotSet() {
        show(NightGrowthState.OFF_NO_AGE)

        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText(context.getString(R.string.aimi_ngr_switch_reason_no_age)).assertExists()
    }

    @Test
    fun t3cBrittleModeShowsOnAndSaysWhy() {
        show(NightGrowthState.ON_BY_T3C)

        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.aimi_ngr_switch_reason_t3c)).assertExists()
    }

    /** A store with age 12 stored, the switch never set and T3C off: the loop runs NGR (ON_BY_AGE). */
    private fun childWithoutSwitch(): Preferences = mock(
        // The other rows of the page read their values too: a string row needs a string, not null.
        defaultAnswer = { invocation -> if (invocation.method.returnType == String::class.java) "" else RETURNS_DEFAULTS.answer(invocation) },
    ) {
        on { getIfExists(BooleanKey.OApsAIMINightGrowthEnabled) } doReturn null
        on { getIfExists(IntKey.OApsAIMINightGrowthAgeYears) } doReturn 12
    }

    private fun nightGrowthPage(): PreferenceSubScreenDef {
        val emptyScreen = ComposeScreenContent { }
        val root = AimiPreferenceTree(
            platformItems = object : AimiPlatformPreferenceItems {
                override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = null
            },
            essentials = emptyScreen,
            learned = emptyScreen,
            settingsAudit = emptyScreen,
            nightGrowthState = emptyScreen,
        ).build(icon = null)
        fun find(screen: PreferenceSubScreenDef): PreferenceSubScreenDef? =
            if (screen.key == "aimi_compose_ngr") screen
            else screen.items.firstNotNullOfOrNull { item ->
                when (item) {
                    is AimiScreenLink         -> find(item.screen)
                    is PreferenceSubScreenDef -> find(item)
                    else                      -> null
                }
            }
        return checkNotNull(find(root)) { "no night growth page" }
    }

    private fun showPage(preferences: Preferences, page: @Composable (PreferenceSubScreenDef) -> Unit) {
        val def = nightGrowthPage()
        compose.setContent {
            CompositionLocalProvider(
                LocalPreferences provides preferences,
                // The row is drawn only on a platform its key names.
                LocalConfig provides mock<Config> { on { platform } doReturn AppPlatform.Android },
            ) {
                MaterialTheme {
                    // The app search page provides it in PreferenceScreenView; the AIMI page provides its own.
                    ProvidePreferenceTheme {
                        page(def)
                    }
                }
            }
        }
    }

    private fun assertTheChildSeesOnSetByTheAge() {
        // The switch is the only toggle on the page.
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(context.getString(R.string.aimi_ngr_switch_reason_age)).assertExists()
    }

    @Test
    fun theAimiNightGrowthPageShowsTheChildCaseOn() {
        showPage(childWithoutSwitch()) { def -> AimiPreferenceSubScreen(screen = def, onBack = {}) }
        assertTheChildSeesOnSetByTheAge()
    }

    @Test
    fun thePageThatTheAppSearchOpensShowsTheChildCaseOn() {
        // The app search opens the same page through the shared renderer (PreferenceScreenView, which
        // uses addPreferenceContent with the main section open).
        showPage(childWithoutSwitch()) { def ->
            val sectionState = remember { PreferenceSectionState().apply { toggle("${def.key}_main", SectionLevel.TOP_LEVEL) } }
            LazyColumn { addPreferenceContent(def, onShowMessage = {}, sectionState = sectionState) }
        }
        assertTheChildSeesOnSetByTheAge()
    }
}
