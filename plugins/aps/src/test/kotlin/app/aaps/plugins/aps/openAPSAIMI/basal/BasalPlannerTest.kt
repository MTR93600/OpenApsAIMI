package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.LoopProfile
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BasalPlannerTest {

    // Dummy implementations to avoid mockk
    class DummyLogger : AAPSLogger {
        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(tag: LTag, message: String) {}
        override fun error(tag: LTag, message: String, throwable: Throwable) {}
        override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(message: String) {}
        override fun error(message: String, throwable: Throwable) {}
        override fun error(format: String, vararg arguments: Any?) {}
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    }

    class DummyFormatter : DecimalFormatter {
        override fun to0Decimal(value: Double): String = String.format("%.0f", value)
        override fun to1Decimal(value: Double): String = String.format("%.1f", value)
        override fun to2Decimal(value: Double): String = String.format("%.2f", value)
        override fun to3Decimal(value: Double): String = String.format("%.3f", value)
    }

    private val logger = DummyLogger()
    private val formatter = DummyFormatter()
    private val adaptiveBasal = AIMIAdaptiveBasal(logger, formatter)
    private val planner = BasalPlanner(adaptiveBasal, logger)

    @Before
    fun setUp() {
        // Reset history provider to empty
        BasalHistoryUtils.installHistoryProvider(BasalHistoryUtils.EmptyProvider)
    }

    @Test
    fun `test plan hard limit`() {
        // BG = 55 <= 60 -> Suspend
        val ctx = createLoopContext(bg = 55.0, delta = 0.0)
        val plan = planner.plan(ctx)
        assertNotNull(plan)
        assertEquals(0.0, plan!!.rateUph, 0.01)
        assert(plan.reason.startsWith("Hard Hypo guard"))
    }

    @Test
    fun `test plan soft limit dropping`() {
        // BG = 70 <= 75, Delta = -2.0 -> Suspend
        val ctx = createLoopContext(bg = 70.0, delta = -2.0)
        val plan = planner.plan(ctx)
        assertNotNull(plan)
        assertEquals(0.0, plan!!.rateUph, 0.01)
        assert(plan.reason.startsWith("Soft Hypo guard"))
    }

    @Test
    fun `test plan soft limit rising`() {
        // BG = 70 <= 75, Delta = +2.0 -> Micro-resume (50%)
        // Profile basal = 1.0 -> 0.5 U/h
        val ctx = createLoopContext(bg = 70.0, delta = 2.0, profileBasal = 1.0)
        val plan = planner.plan(ctx)
        assertNotNull(plan)
        assertEquals(0.5, plan!!.rateUph, 0.01)
        assert(plan.reason.startsWith("Soft Hypo guard (rising)"))
    }

    @Test
    fun `test plan micro resume`() {
        // Use a custom history provider for this test
        val historyProvider = object : BasalHistoryUtils.BasalHistoryProvider {
            override fun zeroBasalDurationMinutes(lookBackHours: Int): Int = 10
            override fun lastTempIsZero(): Boolean = true
            override fun minutesSinceLastChange(): Int = 20
        }
        BasalHistoryUtils.installHistoryProvider(historyProvider)

        val ctx = createLoopContext(bg = 100.0, profileBasal = 1.0)
        val plan = planner.plan(ctx)
        
        assertNotNull(plan)
        // Rate should be max(0.2, 1.0 * 0.5) = 0.5
        assertEquals(0.5, plan!!.rateUph, 0.01)
        assert(plan.reason.startsWith("Micro-resume"))
    }

    @Test
    fun `test plan no action`() {
        val ctx = createLoopContext(bg = 100.0)
        val plan = planner.plan(ctx)
        assertNull(plan)
    }

    private fun createLoopContext(
        bg: Double = 100.0,
        delta: Double = 0.0,
        profileBasal: Double = 1.0
    ): LoopContext {
        val bgSnap = BgSnapshot(
            mgdl = bg,
            delta5 = delta,
            shortAvgDelta = delta,
            longAvgDelta = delta,
            accel = 0.0,
            r2 = 0.0,
            parabolaMinutes = 0.0,
            combinedDelta = delta,
            epochMillis = 0L
        )
        val profile = LoopProfile(
            targetMgdl = 100.0,
            isfMgdlPerU = 50.0,
            basalProfileUph = profileBasal
        )
        val pump = PumpCaps(
            basalStep = 0.05,
            bolusStep = 0.05,
            minDurationMin = 30,
            maxBasal = 5.0,
            maxSmb = 3.0
        )
        // Manual mock of LoopContext data class (it's a data class so we just instantiate it)
        // But LoopContext has 'modes' and 'settings' which are non-nullable.
        // I need to check LoopContext definition to construct it properly.
        // Assuming I can construct it or use a helper if it was available.
        // The previous test used mockk(relaxed=true) which bypassed this.
        // I need to see LoopContext definition.
        return LoopContext(
            bg = bgSnap,
            iobU = 0.0,
            cobG = 0.0,
            profile = profile,
            pump = pump,
            modes = app.aaps.plugins.aps.openAPSAIMI.model.ModeState(false, false, false, false, false, false, false, false),
            settings = app.aaps.plugins.aps.openAPSAIMI.model.AimiSettings(5, true),
            tdd24hU = 40.0,
            eventualBg = bg,
            nowEpochMillis = 0L
        )
    }
}
