package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EssentialSeriesTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun basal_is_the_step_in_force() {
        val basal = listOf(GraphDataPoint(now - 30 * minute, 0.8), GraphDataPoint(now - 10 * minute, 1.4))
        assertEquals(0.8, EssentialSeries.basalAt(basal, now - 20 * minute))
        assertEquals(1.4, EssentialSeries.basalAt(basal, now))
        assertNull(EssentialSeries.basalAt(basal, now - 40 * minute))
    }

    @Test
    fun smb_is_found_near_the_time_and_normal_boluses_are_not() {
        val boluses = listOf(
            BolusGraphPoint(now - 20 * minute, 0.4, BolusType.SMB, isValid = true, label = "0.40 U"),
            BolusGraphPoint(now - 20 * minute, 3.0, BolusType.NORMAL, isValid = true, label = "3.00 U"),
            BolusGraphPoint(now - 5 * minute, 0.3, BolusType.SMB, isValid = false, label = "0.30 U"),
        )
        assertEquals(0.4, EssentialSeries.smbAt(boluses, now - 21 * minute))
        assertNull(EssentialSeries.smbAt(boluses, now - 5 * minute))
        assertNull(EssentialSeries.smbAt(boluses, now - 40 * minute))
    }

    @Test
    fun chart_scale_keeps_room_around_a_day_in_range() {
        assertEquals(40.0..250.0, EssentialSeries.chartScale(listOf(95.0, 140.0, 170.0), 40.0, 250.0))
    }

    @Test
    fun chart_scale_widens_only_for_values_outside() {
        assertEquals(35.0..310.0, EssentialSeries.chartScale(listOf(35.0, 120.0, 310.0), 40.0, 250.0))
        assertEquals(40.0..250.0, EssentialSeries.chartScale(emptyList(), 40.0, 250.0))
    }

    @Test
    fun basal_bars_take_the_rate_at_the_start_of_each_slot() {
        val basal = listOf(GraphDataPoint(now - 30 * minute, 0.8), GraphDataPoint(now - 10 * minute, 1.4))
        val bars = EssentialSeries.basalBars(basal, now - 40 * minute, now, 4)
        assertEquals(listOf(null, 0.8, 0.8, 1.4), bars)
    }
}
