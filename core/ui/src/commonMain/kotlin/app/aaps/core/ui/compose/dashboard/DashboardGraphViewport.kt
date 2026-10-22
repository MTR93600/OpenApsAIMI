package app.aaps.core.ui.compose.dashboard

import kotlin.math.roundToLong

/**
 * Canvas reserves the same right inset for the floating graph action in both rendering and
 * coordinate-sensitive interactions. Use this plot width for timestamp gestures and hit tests.
 */
internal fun dashboardGraphPlotWidthPx(canvasWidthPx: Float): Float {
    if (canvasWidthPx <= 0f) return 0f
    val reservedFabInset = minOf(72f, canvasWidthPx * 0.16f)
    return (canvasWidthPx - reservedFabInset).coerceAtLeast(1f)
}

/**
 * Platform-neutral horizontal viewport for dashboard graph data.
 *
 * Bounds are limited to the timestamps already present in the render snapshot. A caller that
 * loads a wider time range can create a viewport from that snapshot; this type never fabricates
 * glucose points or changes any clinical values.
 */
data class DashboardGraphViewport(
    val fromTimeEpochMs: Long,
    val toTimeEpochMs: Long,
    val dataStartEpochMs: Long,
    val dataEndEpochMs: Long,
    val followingLive: Boolean,
) {

    init {
        require(dataEndEpochMs > dataStartEpochMs)
        require(toTimeEpochMs > fromTimeEpochMs)
        require(fromTimeEpochMs >= dataStartEpochMs)
        require(toTimeEpochMs <= dataEndEpochMs)
    }

    /** Positive horizontal finger motion reveals older data, matching the existing dashboard. */
    fun panBy(deltaXPx: Float, widthPx: Float): DashboardGraphViewport {
        if (widthPx <= 0f || deltaXPx == 0f) return this
        val duration = toTimeEpochMs - fromTimeEpochMs
        val deltaMs = (deltaXPx.toDouble() / widthPx.toDouble() * duration).roundToLong()
        val latestStart = dataEndEpochMs - duration
        val newStart = (fromTimeEpochMs - deltaMs).coerceIn(dataStartEpochMs, latestStart)
        val newEnd = newStart + duration
        return copy(
            fromTimeEpochMs = newStart,
            toTimeEpochMs = newEnd,
            followingLive = newEnd == dataEndEpochMs,
        )
    }

    /** Pans using the drawable plot width rather than the wider Canvas that contains its inset. */
    fun panByPlot(deltaXPx: Float, canvasWidthPx: Float): DashboardGraphViewport =
        panBy(deltaXPx = deltaXPx, widthPx = dashboardGraphPlotWidthPx(canvasWidthPx))

    /**
     * Scales the time span around [anchorFraction] (0 at the left edge, 1 at the right edge).
     * A scale factor greater than one zooms in.
     */
    fun zoomBy(scaleFactor: Float, anchorFraction: Float): DashboardGraphViewport {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return this
        val domainDuration = dataEndEpochMs - dataStartEpochMs
        val oldDuration = toTimeEpochMs - fromTimeEpochMs
        val minimumDuration = MIN_WINDOW_DURATION_MS.coerceAtMost(domainDuration)
        val newDuration = (oldDuration / scaleFactor.toDouble())
            .coerceIn(minimumDuration.toDouble(), domainDuration.toDouble())
            .roundToLong()
            .coerceIn(minimumDuration, domainDuration)
        val anchor = anchorFraction.coerceIn(0f, 1f).toDouble()
        val anchorTime = fromTimeEpochMs + oldDuration * anchor
        val proposedStart = (anchorTime - newDuration * anchor).roundToLong()
        val newStart = proposedStart.coerceIn(dataStartEpochMs, dataEndEpochMs - newDuration)
        val newEnd = newStart + newDuration
        return copy(
            fromTimeEpochMs = newStart,
            toTimeEpochMs = newEnd,
            followingLive = newEnd == dataEndEpochMs,
        )
    }

    /** Moves the current window to the newest available edge without changing its width. */
    fun followLive(): DashboardGraphViewport {
        val duration = toTimeEpochMs - fromTimeEpochMs
        val end = dataEndEpochMs
        return copy(
            fromTimeEpochMs = end - duration,
            toTimeEpochMs = end,
            followingLive = true,
        )
    }

    fun applyTo(input: DashboardGraphRenderInput): DashboardGraphRenderInput =
        input.copy(fromTimeEpochMs = fromTimeEpochMs, toTimeEpochMs = toTimeEpochMs)

    /** True when both viewports describe the same available timestamp domain. */
    fun hasSameDataDomainAs(other: DashboardGraphViewport): Boolean =
        dataStartEpochMs == other.dataStartEpochMs && dataEndEpochMs == other.dataEndEpochMs

    /**
     * Reconciles a viewport with a newer available-data domain. A live viewport stays pinned to the
     * new right edge; a user-panned viewport keeps its time range where possible and is clamped
     * only when that range no longer fits.
     */
    fun withDataDomain(dataStartEpochMs: Long, dataEndEpochMs: Long): DashboardGraphViewport {
        require(dataEndEpochMs > dataStartEpochMs)
        val domainDuration = dataEndEpochMs - dataStartEpochMs
        val duration = (toTimeEpochMs - fromTimeEpochMs).coerceAtMost(domainDuration)
        val latestStart = dataEndEpochMs - duration
        val newStart = if (followingLive) {
            latestStart
        } else {
            fromTimeEpochMs.coerceIn(dataStartEpochMs, latestStart)
        }
        val newEnd = newStart + duration
        return DashboardGraphViewport(
            fromTimeEpochMs = newStart,
            toTimeEpochMs = newEnd,
            dataStartEpochMs = dataStartEpochMs,
            dataEndEpochMs = dataEndEpochMs,
            followingLive = followingLive && newEnd == dataEndEpochMs,
        )
    }

    companion object {
        private const val MIN_WINDOW_DURATION_MS = 60_000L

        /**
         * Builds a valid viewport from a render snapshot. When there is no temporal span to explore,
         * the caller must keep rendering the original input and should not offer gestures.
         */
        fun from(input: DashboardGraphRenderInput): DashboardGraphViewport? {
            val timestamps = buildList {
                input.points.forEach { add(it.timestampEpochMs) }
                input.predictionPoints.forEach { add(it.timestampEpochMs) }
                input.smbMarkers.forEach { add(it.timestampEpochMs) }
                addAll(input.tbrMarkerEpochMs)
                input.tbrSegments.forEach {
                    add(it.startEpochMs)
                    add(it.endEpochMs)
                }
                if (input.fromTimeEpochMs > 0L) add(input.fromTimeEpochMs)
                if (input.toTimeEpochMs > 0L) add(input.toTimeEpochMs)
            }
            val domainStart = timestamps.minOrNull() ?: return null
            val domainEnd = timestamps.maxOrNull() ?: return null
            if (domainEnd <= domainStart) return null

            val start = input.fromTimeEpochMs.takeIf { it >= domainStart && it < domainEnd } ?: domainStart
            val end = input.toTimeEpochMs.takeIf { it > start && it <= domainEnd } ?: domainEnd
            return DashboardGraphViewport(
                fromTimeEpochMs = start,
                toTimeEpochMs = end,
                dataStartEpochMs = domainStart,
                dataEndEpochMs = domainEnd,
                followingLive = end == domainEnd,
            )
        }
    }
}
