package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.LoopProfile
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BasalPlannerTest {

    private val adaptiveBasal: AIMIAdaptiveBasal = mockk(relaxed = true)
    private val log: AAPSLogger = mockk(relaxed = true)
    private val planner = BasalPlanner(adaptiveBasal, log)

    @Before
    fun setUp() {
        // Reset history provider to empty
        BasalHistoryUtils.installHistoryProvider(BasalHistoryUtils.EmptyProvider)
    }

    @After
    fun tearDown() {
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
        // Mock history provider to return zero basal duration > 5 min and last temp is zero
        val historyProvider = mockk<BasalHistoryUtils.BasalHistoryProvider>()
        every { historyProvider.zeroBasalDurationMinutes(any()) } returns 10
        every { historyProvider.lastTempIsZero() } returns true
        every { historyProvider.minutesSinceLastChange() } returns 20
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
        return mockk<LoopContext>(relaxed = true) {
            every { this@mockk.bg } returns bgSnap
            every { this@mockk.profile } returns profile
            every { this@mockk.pump } returns pump
        }
    }
}
