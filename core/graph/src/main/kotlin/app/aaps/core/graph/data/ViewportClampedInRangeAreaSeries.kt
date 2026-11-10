package app.aaps.core.graph.data

/**
 * In-range (target) band using the same fill rendering as [AreaGraphSeries].
 *
 * GraphView's [AreaGraphSeries] uses two corner points spanning a fixed X interval. If that
 * interval is wider than the visible viewport, clipping can make the band look "stuck" while
 * scrolling. A naïve fix is to intersect the band with the viewport each draw — but if the range
 * is limited to the **data load window** (`fromTime`…`endTime`), panning **before** that window
 * yields an empty intersection and the green band disappears even though low/high marks still apply.
 *
 * Here, [getValues] always returns the **visible** X span (`from`…`until` from the viewport), with
 * constant [lowLine] / [highLine]. The band therefore follows the time axis when panning the
 * dashboard or overview graph.
 */
class ViewportClampedInRangeAreaSeries(
    private val lowLine: Double,
    private val highLine: Double,
) : AreaGraphSeries<DoubleDataPoint>(
    arrayOf(
        DoubleDataPoint(0.0, lowLine, highLine),
        DoubleDataPoint(1.0, lowLine, highLine),
    )
) {

    override fun getValues(from: Double, until: Double): Iterator<DoubleDataPoint> {
        if (from >= until) {
            return emptyList<DoubleDataPoint>().iterator()
        }
        return listOf(
            DoubleDataPoint(from, lowLine, highLine),
            DoubleDataPoint(until, lowLine, highLine),
        ).iterator()
    }
}
