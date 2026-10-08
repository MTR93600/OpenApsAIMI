package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.overview.PluginStatusLevel
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the auditor state to chip level mapping in [AuditorStatusBadgeSource].
 *
 * [AuditorUIState] used to carry its own colour resources. Nothing read them, so they are gone and
 * the colour is now chosen in the UI layer from [PluginStatusLevel], which the Overview chip gives
 * a separate colour pair per level. That only keeps the five auditor states apart while this
 * mapping stays one to one, and nothing else in the build checks it - so it is checked here.
 */
class AuditorStatusBadgeSourceMappingTest {

    @Test
    fun everyStateTypeMapsToItsOwnStatusLevel() {
        val levels = AuditorUIState.StateType.entries.map { type ->
            AuditorStatusBadgeSource.toBadge(stateFor(type)).level
        }

        assertEquals(
            "every StateType must map to a distinct PluginStatusLevel, or two auditor states " +
                "would render with the same chip colour",
            AuditorUIState.StateType.entries.size,
            levels.toSet().size
        )
    }

    @Test
    fun eachStateTypeMapsToTheMatchingStatusLevel() {
        assertEquals(PluginStatusLevel.IDLE, levelOf(AuditorUIState.StateType.IDLE))
        assertEquals(PluginStatusLevel.PROCESSING, levelOf(AuditorUIState.StateType.PROCESSING))
        assertEquals(PluginStatusLevel.READY, levelOf(AuditorUIState.StateType.READY))
        assertEquals(PluginStatusLevel.WARNING, levelOf(AuditorUIState.StateType.WARNING))
        assertEquals(PluginStatusLevel.ERROR, levelOf(AuditorUIState.StateType.ERROR))
    }

    /**
     * The chip hides itself for [PluginStatusLevel.IDLE] with nothing to count, which is what the
     * old `badgeVisible = false` on the idle state meant. Keep the two in step.
     */
    @Test
    fun idleStateProducesNothingForTheChipToShow() {
        val badge = AuditorStatusBadgeSource.toBadge(AuditorUIState.idle())

        assertEquals(PluginStatusLevel.IDLE, badge.level)
        assertEquals(0, badge.badgeCount)
    }

    @Test
    fun badgeCarriesTheInsightCountAndMessage() {
        val badge = AuditorStatusBadgeSource.toBadge(AuditorUIState.ready(insightCount = 3))

        assertEquals(PluginStatusLevel.READY, badge.level)
        assertEquals(3, badge.badgeCount)
        assertEquals("3 insights available", badge.statusMessage)
    }

    private fun levelOf(type: AuditorUIState.StateType): PluginStatusLevel =
        AuditorStatusBadgeSource.toBadge(stateFor(type)).level

    /** Builds each state through the real factory, so the test follows the shipped path. */
    private fun stateFor(type: AuditorUIState.StateType): AuditorUIState = when (type) {
        AuditorUIState.StateType.IDLE       -> AuditorUIState.idle()
        AuditorUIState.StateType.PROCESSING -> AuditorUIState.processing()
        AuditorUIState.StateType.READY      -> AuditorUIState.ready(insightCount = 1)
        AuditorUIState.StateType.WARNING    -> AuditorUIState.warning()
        AuditorUIState.StateType.ERROR      -> AuditorUIState.error()
    }
}
