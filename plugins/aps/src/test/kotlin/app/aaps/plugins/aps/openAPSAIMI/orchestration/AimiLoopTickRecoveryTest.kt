package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.ui.UiInteraction
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimiLoopTickRecoveryTest {

    private fun sampleCtx(): AimiTickContext {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        return AimiTickContext(
            glucoseStatus = GlucoseStatusAIMI(glucose = 181.0, delta = 0.2, shortAvgDelta = 0.1, longAvgDelta = 0.0, date = 1L),
            currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
            iobDataArray = arrayOf(IobTotal(time = 1L, iob = 2.5)),
            profile = profile,
            autosensData = AutosensResult(),
            mealData = MealData(mealCOB = 8.0),
            microBolusAllowed = true,
            currentTime = 1_700_000_000_000L,
            flatBGsDetected = false,
            dynIsfMode = true,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "",
        )
    }

    @Test
    fun `lock skip returns zero insulin RT`() {
        val rt = AimiLoopTickRecovery.skippedPriorTickStillRunning(sampleCtx())
        assertEquals(0.0, rt.units ?: 0.0, 0.0)
        assertEquals(0.0, rt.insulinReq ?: 0.0, 0.0)
        assertTrue(rt.reason.toString().contains("safe skip"))
    }

    @Test
    fun `unhandled error recovery preserves bg and does not throw`() {
        val rt = AimiLoopTickRecovery.safeResultAfterUnhandledError(
            ctx = sampleCtx(),
            error = IllegalStateException("test boom"),
            consoleLog = mutableListOf("line1"),
            consoleError = mutableListOf(),
        )
        assertEquals(181.0, rt.bg ?: 0.0, 0.0)
        assertEquals(2.5, rt.IOB ?: 0.0, 0.0)
        assertFalse(rt.reason.toString().contains("test boom"))
        assertTrue(rt.consoleError?.any { it.contains("IllegalStateException") } == true)
        assertTrue(rt.reason.toString().contains("safe hold ["))
    }

    @Test
    fun `unhandled error recovery includes loop phase and aimi frame in reason`() {
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.CORE_DECISION, null)
        val error = NullPointerException("missing field").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2",
                    "coreDecisionBranch",
                    "DetermineBasalAIMI2.kt",
                    4287,
                ),
            )
        }
        val rt = AimiLoopTickRecovery.safeResultAfterUnhandledError(
            ctx = sampleCtx(),
            error = error,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf(),
        )
        val reason = rt.reason.toString()
        assertTrue(reason.contains("CORE_DECISION"))
        assertTrue(reason.contains("SMB & basal decision tree"))
        assertTrue(reason.contains("DetermineBasalAIMI2.coreDecisionBranch:4287"))
        assertTrue(rt.consoleError?.any { it.contains("DetermineBasalAIMI2.coreDecisionBranch:4287") } == true)
    }
}
