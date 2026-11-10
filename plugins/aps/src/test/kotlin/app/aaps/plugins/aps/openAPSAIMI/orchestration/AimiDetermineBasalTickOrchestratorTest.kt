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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Verrouille le contrat **P3b** : l’orchestrateur ne fait qu’appeler [DetermineBasalaimiSMB2.runDetermineBasalTick]
 * avec le [AimiTickContext] fourni et retourne son [RT].
 */
class AimiDetermineBasalTickOrchestratorTest {

    @Test
    fun `run delegates to plugin with same context and returns RT`() {
        val plugin = mockk<DetermineBasalaimiSMB2>(relaxed = true)
        val expectedRt = mockk<RT>(relaxed = true)
        val ctx = AimiTickContext(
            glucoseStatus = mockk<GlucoseStatusAIMI>(relaxed = true),
            currentTemp = mockk<CurrentTemp>(relaxed = true),
            iobDataArray = arrayOf(mockk<IobTotal>(relaxed = true)),
            profile = mockk<OapsProfileAimi>(relaxed = true),
            autosensData = mockk<AutosensResult>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            microBolusAllowed = true,
            currentTime = 1_700_000_000_000L,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "",
        )
        every { plugin.runDetermineBasalTick(ctx) } returns expectedRt

        val actual = AimiDetermineBasalTickOrchestrator.run(plugin, ctx)

        assertThat(actual).isSameInstanceAs(expectedRt)
        verify(exactly = 1) { plugin.runDetermineBasalTick(ctx) }
    }
}
