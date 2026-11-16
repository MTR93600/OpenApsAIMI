package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiEssentialsContent
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiEssentialsLive
import app.aaps.plugins.aps.openAPSAIMI.compose.aimiTargetsHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The link from the Essentials targets to the profile screen. Shared code, so it also runs on
 * `iosSimulatorArm64Test`.
 */
class AimiPlatformActionsTest {

    @Test
    fun `opening the profile screen asks the app navigator for the profile management element`() {
        PendingNavigation.consume()

        requestProfileScreen()

        // The navigator then asks for the same protection as the drawer entry.
        assertEquals(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT), PendingNavigation.consume())
        assertNull(PendingNavigation.consume())
    }

    @Test
    fun `the targets hint says tap only when the platform can open the profile screen`() {
        assertEquals(ApsStrings.aimi_essentials_targets_hint_open, aimiTargetsHint(canOpenProfile = true))
        assertEquals(ApsStrings.aimi_essentials_targets_hint, aimiTargetsHint(canOpenProfile = false))
    }

    @Test
    fun `the Essentials page gets the profile link of the platform`() {
        val open: () -> Unit = {}
        val actions = RecordingActions(open)

        val content = AimiEssentialsContent(preferences = MapPreferences(), loadLive = { AimiEssentialsLive() }, platformActions = actions)

        // The page is given exactly the platform's action, so a tap on the targets runs it.
        assertSame(open, content.onOpenProfile)
        assertEquals(listOf(AimiPlatformActionSlot.PROFILE_SCREEN), actions.asked)
    }

    @Test
    fun `the Essentials page gets no profile link when the platform cannot open the screen`() {
        val content = AimiEssentialsContent(preferences = MapPreferences(), loadLive = { AimiEssentialsLive() }, platformActions = RecordingActions(null))

        assertNull(content.onOpenProfile)
    }

    /** Answers every slot with [answer] and records which slots were asked. */
    private class RecordingActions(private val answer: (() -> Unit)?) : AimiPlatformActions {

        val asked = mutableListOf<AimiPlatformActionSlot>()

        override fun action(slot: AimiPlatformActionSlot): (() -> Unit)? {
            asked += slot
            return answer
        }
    }
}
