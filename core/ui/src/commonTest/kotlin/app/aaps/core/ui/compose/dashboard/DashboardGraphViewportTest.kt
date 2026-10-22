package app.aaps.core.ui.compose.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardGraphViewportTest {

    private fun viewport() = DashboardGraphViewport(
        fromTimeEpochMs = 1_200_000L,
        toTimeEpochMs = 2_400_000L,
        dataStartEpochMs = 0L,
        dataEndEpochMs = 3_600_000L,
        followingLive = false,
    )

    @Test
    fun draggingRightMovesTheVisibleWindowIntoEarlierData() {
        val moved = viewport().panByPlot(deltaXPx = 21f, canvasWidthPx = 100f)

        assertEquals(900_000L, moved.fromTimeEpochMs)
        assertEquals(2_100_000L, moved.toTimeEpochMs)
        assertFalse(moved.followingLive)
    }

    @Test
    fun panCannotMovePastTheAvailableData() {
        val atOldest = viewport().copy(fromTimeEpochMs = 0L, toTimeEpochMs = 1_200_000L)
            .panBy(deltaXPx = 100f, widthPx = 100f)
        val atNewest = viewport().copy(fromTimeEpochMs = 2_400_000L, toTimeEpochMs = 3_600_000L)
            .panBy(deltaXPx = -100f, widthPx = 100f)

        assertEquals(0L to 1_200_000L, atOldest.fromTimeEpochMs to atOldest.toTimeEpochMs)
        assertEquals(2_400_000L to 3_600_000L, atNewest.fromTimeEpochMs to atNewest.toTimeEpochMs)
    }

    @Test
    fun zoomKeepsTheTimestampUnderTheGestureAnchorFixed() {
        val zoomed = viewport().zoomBy(scaleFactor = 2f, anchorFraction = 0.25f)

        assertEquals(1_350_000L, zoomed.fromTimeEpochMs)
        assertEquals(1_950_000L, zoomed.toTimeEpochMs)
    }

    @Test
    fun returningToLivePreservesTheCurrentWindowWidth() {
        val live = viewport().followLive()

        assertEquals(2_400_000L to 3_600_000L, live.fromTimeEpochMs to live.toTimeEpochMs)
        assertTrue(live.followingLive)
    }

    @Test
    fun renderInputUsesViewportTimeBoundsWithoutChangingClinicalData() {
        val original = DashboardGraphRenderInput(
            fromTimeEpochMs = 1_200_000L,
            toTimeEpochMs = 2_400_000L,
            nowEpochMs = 3_600_000L,
            points = listOf(DashboardGraphPoint(0L, 100.0), DashboardGraphPoint(3_600_000L, 120.0)),
        )

        val rendered = viewport().applyTo(original)

        assertEquals(1_200_000L, rendered.fromTimeEpochMs)
        assertEquals(2_400_000L, rendered.toTimeEpochMs)
        assertEquals(original.points, rendered.points)
        assertEquals(original.targetLowMgdl, rendered.targetLowMgdl)
    }

    @Test
    fun gestureAndRendererSharePlotWidthExcludingReservedFabInset() {
        assertEquals(84f, dashboardGraphPlotWidthPx(100f))

        val moved = viewport().panByPlot(deltaXPx = 42f, canvasWidthPx = 100f)

        // 42 px is half of the 84 px plot (not half of the full canvas), so the
        // visible time window moves by half its duration.
        assertEquals(600_000L to 1_800_000L, moved.fromTimeEpochMs to moved.toTimeEpochMs)
    }

    @Test
    fun incomingViewportAndDomainCanReplaceAStaleGestureViewport() {
        val refreshed = DashboardGraphViewport(
            fromTimeEpochMs = 2_000_000L,
            toTimeEpochMs = 3_200_000L,
            dataStartEpochMs = 1_000_000L,
            dataEndEpochMs = 3_200_000L,
            followingLive = true,
        )

        assertFalse(refreshed.hasSameDataDomainAs(viewport()))
        assertTrue(refreshed.hasSameDataDomainAs(refreshed.copy(fromTimeEpochMs = 2_200_000L, toTimeEpochMs = 3_200_000L)))
    }

    @Test
    fun domainRefreshKeepsPannedWindowButLiveWindowTracksNewData() {
        val oldPanned = viewport()
        val pannedAfterRefresh = oldPanned.withDataDomain(
            dataStartEpochMs = 0L,
            dataEndEpochMs = 4_200_000L,
        )
        val oldLive = oldPanned.followLive()
        val liveAfterRefresh = oldLive.withDataDomain(
            dataStartEpochMs = 0L,
            dataEndEpochMs = 4_200_000L,
        )

        assertEquals(oldPanned.fromTimeEpochMs to oldPanned.toTimeEpochMs, pannedAfterRefresh.fromTimeEpochMs to pannedAfterRefresh.toTimeEpochMs)
        assertEquals(3_000_000L to 4_200_000L, liveAfterRefresh.fromTimeEpochMs to liveAfterRefresh.toTimeEpochMs)
        assertTrue(liveAfterRefresh.followingLive)
    }
}
