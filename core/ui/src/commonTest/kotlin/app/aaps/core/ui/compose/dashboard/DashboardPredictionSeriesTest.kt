package app.aaps.core.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgType
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardPredictionSeriesTest {

    @Test
    fun predictionTypesRemainSeparateRenderableSeries() {
        val iobFirst = DashboardGraphPoint(10L, 100.0, BgType.IOB_PREDICTION)
        val iobSecond = DashboardGraphPoint(20L, 110.0, BgType.IOB_PREDICTION)
        val uamFirst = DashboardGraphPoint(11L, 101.0, BgType.UAM_PREDICTION)
        val uamSecond = DashboardGraphPoint(21L, 120.0, BgType.UAM_PREDICTION)

        val series = dashboardPredictionSeries(listOf(iobFirst, uamFirst, iobSecond, uamSecond))

        assertEquals(2, series.size)
        assertEquals(
            listOf(
                BgType.IOB_PREDICTION to listOf(iobFirst, iobSecond),
                BgType.UAM_PREDICTION to listOf(uamFirst, uamSecond),
            ),
            series.map { it.type to it.points },
        )
    }
}
