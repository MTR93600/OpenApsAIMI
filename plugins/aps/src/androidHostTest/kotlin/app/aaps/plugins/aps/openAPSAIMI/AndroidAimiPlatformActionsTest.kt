package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActionSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** The Android side of the AIMI platform actions: every slot is answered and does what it says. */
class AndroidAimiPlatformActionsTest {

    @Test
    fun everyActionSlotIsAnsweredOnAndroid() {
        val actions = AndroidAimiPlatformActions()
        AimiPlatformActionSlot.entries.forEach { slot -> assertNotNull(actions.action(slot), "slot $slot") }
    }

    @Test
    fun profileScreenActionOpensTheProfileScreenThroughTheNavigator() {
        PendingNavigation.consume()

        val open = AndroidAimiPlatformActions().action(AimiPlatformActionSlot.PROFILE_SCREEN)
        assertNotNull(open)
        open?.invoke()

        assertEquals(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT), PendingNavigation.consume())
    }
}
