package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.model.AimiSettings
import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.LoopProfile
import app.aaps.plugins.aps.openAPSAIMI.model.ModeState
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.smb.BypassHeuristics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BypassHeuristicsTest {

    private fun loopContext(
        bg: Double,
        delta: Double,
        combined: Double? = null,
        mealMode: Boolean = false,
        iob: Double = 0.0,
        maxSmb: Double = 2.0
    ): LoopContext {
        val snapshot = BgSnapshot(
            mgdl = bg,
            delta5 = delta,
            shortAvgDelta = null,
            longAvgDelta = null,
            accel = null,
            r2 = null,
            parabolaMinutes = null,
            combinedDelta = combined,
            epochMillis = 0L
        )
        return LoopContext(
            bg = snapshot,
            iobU = iob,
            cobG = 0.0,
            profile = LoopProfile(targetMgdl = 100.0, isfMgdlPerU = 40.0, basalProfileUph = 1.0),
            pump = PumpCaps(
                basalStep = 0.05,
                bolusStep = 0.05,
                minDurationMin = 30,
                maxBasal = 3.0,
                maxSmb = maxSmb
            ),
            modes = ModeState(meal = mealMode),
            settings = AimiSettings(smbIntervalMin = 30, wCycleEnabled = false),
            tdd24hU = 40.0,
            eventualBg = 140.0,
            nowEpochMillis = 0L
        )
    }

    @Test
    fun `meal mode always bypasses damping`() {
        val ctx = loopContext(bg = 110.0, delta = 0.5, mealMode = true)
        assertTrue(BypassHeuristics.computeBypass(ctx, hypoRisk = false))
    }

    @Test
    fun `rising hyperglycemia bypasses when safe`() {
        val ctx = loopContext(bg = 165.0, delta = 2.0, combined = 4.5, iob = 0.2)
        assertTrue(BypassHeuristics.computeBypass(ctx, hypoRisk = false))
    }

    @Test
    fun `hypo risk suppresses bypass despite hyperglycemia`() {
        val ctx = loopContext(bg = 165.0, delta = 2.0, combined = 4.5, iob = 0.2)
        assertFalse(BypassHeuristics.computeBypass(ctx, hypoRisk = true))
    }
}
