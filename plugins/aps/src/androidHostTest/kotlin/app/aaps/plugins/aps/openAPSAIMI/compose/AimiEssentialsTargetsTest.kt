package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.preference.LocalPreferenceTheme
import app.aaps.core.ui.compose.preference.preferenceTheme
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The profile targets block of the Essentials page: a tap opens the profile screen only when the
 * platform gave a link, and there is no dead link when it did not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AimiEssentialsTargetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
    }

    private fun show(onOpenProfile: (() -> Unit)?) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalPreferenceTheme provides preferenceTheme(),
                    LocalProfileUtil provides mock<ProfileUtil>(),
                ) {
                    // No profile is running, so no target value has to be formatted.
                    AimiEssentialsTargets(live = AimiEssentialsLive(), onOpenProfile = onOpenProfile)
                }
            }
        }
    }

    @Test
    fun tapOnTheTargetsRunsTheProfileLink() {
        var opened = 0
        show(onOpenProfile = { opened++ })

        compose.onNodeWithText(context.getString(R.string.aimi_essentials_targets_hint_open))
            .assertHasClickAction()
            .performClick()

        assertEquals(1, opened)
    }

    @Test
    fun withoutALinkTheTargetsCannotBeTappedAndTheHintDoesNotSayTap() {
        show(onOpenProfile = null)

        compose.onNodeWithText(context.getString(R.string.aimi_essentials_targets_hint)).assertHasNoClickAction()
        compose.onNodeWithText(context.getString(R.string.aimi_essentials_targets_hint_open)).assertDoesNotExist()
    }
}
