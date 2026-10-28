package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The rules behind [GlassHomeViewModel], as `dev_OAPSAIMI` `OverviewViewModel` applied them. */
class GlassHomeStatsTest {

    private fun steps(timestamp: Long, steps5min: Int, device: String) = SC(
        duration = 5 * 60_000L,
        timestamp = timestamp,
        steps5min = steps5min,
        steps10min = 0,
        steps15min = 0,
        steps30min = 0,
        steps60min = 0,
        steps180min = 0,
        device = device,
        utcOffset = 0L,
    )

    @Test
    fun `no readings or a young day give no ring`() {
        assertNull(GlassHomeStats.tir(emptyList(), yesterdayInRangePct = 70.0, hoursSinceDayStart = 12.0))
        assertNull(GlassHomeStats.tir(listOf(100.0), yesterdayInRangePct = 70.0, hoursSinceDayStart = 5.9))
    }

    @Test
    fun `ring splits readings into below and in range and above`() {
        val tir = assertNotNull(GlassHomeStats.tir(listOf(50.0, 69.0, 70.0, 120.0, 180.0, 181.0, 260.0, 100.0), yesterdayInRangePct = null, hoursSinceDayStart = 8.0))
        assertEquals(25f, tir.belowPct)
        assertEquals(50f, tir.inRangePct)
        assertEquals(25f, tir.abovePct)
        assertNull(tir.deltaVsYesterday)
    }

    @Test
    fun `change against yesterday is rounded points`() {
        val tir = assertNotNull(GlassHomeStats.tir(listOf(100.0, 100.0, 200.0), yesterdayInRangePct = 60.0, hoursSinceDayStart = 8.0))
        // 66.67 % today minus 60 % yesterday
        assertEquals(7, tir.deltaVsYesterday)
        assertEquals(50.0, GlassHomeStats.inRangePct(listOf(100.0, 250.0)))
        assertNull(GlassHomeStats.inRangePct(emptyList()))
    }

    @Test
    fun `steps take the highest device total and the largest value of each five minute slot`() {
        val rows = listOf(
            steps(timestamp = 0L, steps5min = 100, device = "Watch"),
            steps(timestamp = 60_000L, steps5min = 150, device = "Watch"),
            steps(timestamp = 5 * 60_000L, steps5min = 200, device = "Watch"),
            steps(timestamp = 0L, steps5min = 300, device = "PhoneSensor"),
        )
        // Watch: slot 0 -> 150, slot 1 -> 200 = 350. Phone: 300. Devices are not added together.
        assertEquals(350.0, GlassHomeStats.stepsToday(rows))
        assertNull(GlassHomeStats.stepsToday(emptyList()))
        assertEquals(0.0, GlassHomeStats.stepsToday(listOf(steps(0L, 50, device = ""))))
    }

    @Test
    fun `steps today take the larger of health store and database`() {
        // The health store lags behind the database: the database wins.
        assertEquals(5_000.0, GlassHomeStats.chooseStepsToday(healthStoreSteps = 4_000L, databaseSteps = 5_000.0))
        // The database cannot merge phone and watch: the health store wins.
        assertEquals(6_000.0, GlassHomeStats.chooseStepsToday(healthStoreSteps = 6_000L, databaseSteps = 5_000.0))
        // A health store total without database rows is shown.
        assertEquals(1_200.0, GlassHomeStats.chooseStepsToday(healthStoreSteps = 1_200L, databaseSteps = null))
    }

    @Test
    fun `steps today without a health store total is the database total or nothing`() {
        // iOS and desktop: no health store total, so no 0 is invented in its place.
        assertEquals(5_000.0, GlassHomeStats.chooseStepsToday(healthStoreSteps = null, databaseSteps = 5_000.0))
        assertNull(GlassHomeStats.chooseStepsToday(healthStoreSteps = null, databaseSteps = null))
    }

    @Test
    fun `loop pill labels follow the running mode`() {
        assertEquals(CoreUiStrings.closedloop, loopModeLabel(RM.Mode.CLOSED_LOOP))
        assertEquals(CoreUiStrings.uel_lgs_loop_mode, loopModeLabel(RM.Mode.CLOSED_LOOP_LGS))
        assertEquals(InterfacesStrings.loopsuspended, loopModeLabel(RM.Mode.SUSPENDED_BY_USER))
        assertEquals(InterfacesStrings.pumpsuspended, loopModeLabel(RM.Mode.SUSPENDED_BY_PUMP))
    }
}
