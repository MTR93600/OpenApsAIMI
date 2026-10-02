package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Pins the contract of [AimiLoopTickRecovery]: every AIMI determine_basal tick returns exactly one
 * well formed [RT], including the ticks that threw or were skipped because the previous tick was
 * still running.
 *
 * Both recovery paths are on the dosing path (`DetermineBasalAIMI2.determine_basal`, the
 * `onLockTimeout` and `recoverFromError` handlers of
 * `AimiLoopTelemetry.traceDetermineBasalTick`). Without them the exception climbs past
 * `LoopPlugin`, which has no try/catch around `usedAPS.invoke`, so nothing is persisted or uploaded
 * and the failure never reaches the AAPS log. The pump stays safe, but the incident becomes
 * invisible.
 *
 * These tests run on the JVM because the stack frame test sets `Throwable.stackTrace`, which is a
 * JVM only API. The two "no change" tests and the console tail test use no JVM only API and could
 * live in `commonTest` (and would then also run on the iOS simulator), but they are kept here with
 * the rest of the contract so the whole guarantee reads as one file.
 */
class AimiLoopTickRecoveryTest {

    private fun sampleCtx(): AimiTickContext {
        val profile = mock<OapsProfileAimi>()
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
            uiInteraction = mock<UiInteraction>(),
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
        // The exception message is deliberately kept out of `reason`. `reason` is user facing: it is
        // copied verbatim into the APS result and shown on the overview and in Nightscout, so a raw
        // message could leak internal detail or read like a therapy statement. The message goes to
        // consoleLog and consoleError instead, which are the developer log channels.
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

    /**
     * This is what protects the patient. A recovery [RT] must command nothing at all.
     *
     * `rate` and `duration` both null and `units` null is the only shape that keeps the downstream
     * result inert. In `DetermineBasalResult.with` (`:implementation`):
     *  - `isTempBasalRequested` is set to true only inside
     *    `if (result.rate != null && result.duration != null)`, so with either one null it keeps its
     *    `false` default and `rate` and `duration` keep their "no request" defaults;
     *  - `smb = result.units ?: 0.0`, and
     *    [app.aaps.core.interfaces.aps.APSResult.isBolusRequested] is `smb > 0.0`.
     *
     * `isChangeRequested()` then returns false in both loop modes: closed loop returns
     * `isTempBasalRequested || isBolusRequested`, and open loop returns early on
     * `!isTempBasalRequested && !isBolusRequested`.
     *
     * Do not delete this as a duplicate of the "zero insulin" test above. That one checks `units`
     * and `insulinReq` only, and reads a null as a zero. A recovery RT that set a `rate` and a
     * `duration` would pass it and would still send a new temp basal to the pump after a failed
     * tick.
     */
    @Test
    fun `lock skip commands no temp basal and no bolus`() {
        val rt = AimiLoopTickRecovery.skippedPriorTickStillRunning(sampleCtx())
        assertNull(rt.rate)
        assertNull(rt.duration)
        assertNull(rt.units)
    }

    /** Same guarantee as the lock skip test above, on the unhandled error path. */
    @Test
    fun `unhandled error recovery commands no temp basal and no bolus`() {
        val rt = AimiLoopTickRecovery.safeResultAfterUnhandledError(
            ctx = sampleCtx(),
            error = IllegalStateException("test boom"),
            consoleLog = mutableListOf("line1"),
            consoleError = mutableListOf(),
        )
        assertNull(rt.rate)
        assertNull(rt.duration)
        assertNull(rt.units)
    }

    /**
     * The recovery RT carries only the tail of the console log, so one failing tick cannot push a
     * whole run of log lines into the stored result.
     */
    @Test
    fun `console log tail is capped`() {
        val longLog = (1..100).map { "line$it" }.toMutableList()
        val rt = AimiLoopTickRecovery.safeResultAfterUnhandledError(
            ctx = sampleCtx(),
            error = IllegalStateException("test boom"),
            consoleLog = longLog,
            consoleError = mutableListOf(),
        )
        val merged = rt.consoleLog
        assertNotNull(merged)
        // 64 kept lines plus the one recovery line the helper appends.
        assertEquals(65, merged!!.size)
        assertEquals("line37", merged.first())
        assertEquals("line100", merged[63])
        assertTrue(merged.last().contains("AIMI tick recovered"))
    }
}
