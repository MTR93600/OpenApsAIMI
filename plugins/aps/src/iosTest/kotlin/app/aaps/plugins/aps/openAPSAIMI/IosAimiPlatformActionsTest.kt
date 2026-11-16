package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActionSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The iOS side of the AIMI platform actions: every slot is answered and does what it says. */
class IosAimiPlatformActionsTest {

    @Test
    fun `every action slot is answered on iOS`() {
        val actions = IosAimiPlatformActions()
        AimiPlatformActionSlot.entries.forEach { slot -> assertNotNull(actions.action(slot), "slot $slot") }
    }

    @Test
    fun `the profile screen action opens the shared profile screen through the navigator`() {
        PendingNavigation.consume()

        assertNotNull(IosAimiPlatformActions().action(AimiPlatformActionSlot.PROFILE_SCREEN)).invoke()

        assertEquals(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT), PendingNavigation.consume())
    }
}
