package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which slots iOS answers. Building the items needs the DI graph, so this checks the list the
 * class itself uses. Adding a slot to the list must be a decision, not an accident.
 */
class IosAimiPlatformPreferenceItemsTest {

    @Test
    fun `shared advisor and other supported screens are answered`() {
        assertEquals(
            setOf(
                AimiPlatformPreferenceSlot.CONTEXT,
                AimiPlatformPreferenceSlot.HORMONITOR_VIEWER,
                AimiPlatformPreferenceSlot.PKPD_SETUP,
                AimiPlatformPreferenceSlot.MEAL_ADVISOR,
                AimiPlatformPreferenceSlot.PROFILE_ADVISOR,
            ),
            IosAimiPlatformPreferenceItems.ANSWERED,
        )
    }

    @Test
    fun `the other slots are left out`() {
        val left = AimiPlatformPreferenceSlot.entries - IosAimiPlatformPreferenceItems.ANSWERED
        assertEquals(4, left.size)
    }
}
