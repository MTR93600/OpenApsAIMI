package app.aaps.core.ui.compose.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the shared graph's existing time-to-canvas mapping before introducing a movable viewport.
 * SMB marker taps must continue to address the same timestamp after the plot's time window moves.
 */
class DashboardGraphViewportCharacterizationTest {

    @Test
    fun markerAtHalfOfVisibleTimeWindowMapsToHalfOfPlotWidth() {
        val marker = DashboardSmbMarker(timestampEpochMs = 50L)
        val input = DashboardGraphRenderInput(
            fromTimeEpochMs = 0L,
            toTimeEpochMs = 100L,
            nowEpochMs = 100L,
            points = listOf(
                DashboardGraphPoint(timestampEpochMs = 0L, value = 100.0),
                DashboardGraphPoint(timestampEpochMs = 100L, value = 120.0),
            ),
            smbMarkers = listOf(marker),
        )

        assertEquals(
            marker,
            findNearestSmbByX(tapX = 42f, widthPx = 100f, hitPx = 2f, input = input),
        )
    }

    @Test
    fun markerOutsideVisibleTimeWindowIsNotSelectable() {
        val input = DashboardGraphRenderInput(
            fromTimeEpochMs = 0L,
            toTimeEpochMs = 100L,
            nowEpochMs = 100L,
            points = listOf(
                DashboardGraphPoint(timestampEpochMs = 0L, value = 100.0),
                DashboardGraphPoint(timestampEpochMs = 100L, value = 120.0),
            ),
            smbMarkers = listOf(DashboardSmbMarker(timestampEpochMs = 200L)),
        )

        assertNull(findNearestSmbByX(tapX = 80f, widthPx = 100f, hitPx = 2f, input = input))
    }

    @Test
    fun markerHitTestingUsesTheSamePlotWidthAsRendering() {
        val marker = DashboardSmbMarker(timestampEpochMs = 50L)
        val input = DashboardGraphRenderInput(
            fromTimeEpochMs = 0L,
            toTimeEpochMs = 100L,
            nowEpochMs = 100L,
            points = listOf(
                DashboardGraphPoint(timestampEpochMs = 0L, value = 100.0),
                DashboardGraphPoint(timestampEpochMs = 100L, value = 120.0),
            ),
            smbMarkers = listOf(marker),
        )

        assertEquals(
            marker,
            findNearestSmbByX(tapX = 42f, widthPx = 100f, hitPx = 1f, input = input),
        )
    }
}
