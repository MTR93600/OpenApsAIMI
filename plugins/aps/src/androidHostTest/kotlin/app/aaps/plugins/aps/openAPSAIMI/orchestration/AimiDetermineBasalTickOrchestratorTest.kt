package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the P3b contract: the orchestrator only calls `DetermineBasalaimiSMB2.runDetermineBasalTick`
 * with the [AimiTickContext] it was given and returns that [RT] unchanged.
 *
 * It must stay a pure hand over. Any extra step added here would sit between the loop and the
 * dosing decision without being covered by the tick recovery wrapper.
 */
class AimiDetermineBasalTickOrchestratorTest {

    @Test
    fun `run delegates to plugin with same context and returns RT`() {
        val plugin = mock<DetermineBasalaimiSMB2>()
        val expectedRt = RT(runningDynamicIsf = false)
        val ctx = AimiTickContext(
            glucoseStatus = mock<GlucoseStatusAIMI>(),
            currentTemp = mock<CurrentTemp>(),
            iobDataArray = arrayOf(mock<IobTotal>()),
            profile = mock<OapsProfileAimi>(),
            autosensData = mock<AutosensResult>(),
            mealData = mock<MealData>(),
            microBolusAllowed = true,
            currentTime = 1_700_000_000_000L,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mock<UiInteraction>(),
            extraDebug = "",
        )
        whenever(plugin.runDetermineBasalTick(ctx)).thenReturn(expectedRt)

        val actual = AimiDetermineBasalTickOrchestrator.run(plugin, ctx)

        assertThat(actual).isSameInstanceAs(expectedRt)
        verify(plugin, times(1)).runDetermineBasalTick(ctx)
    }
}
