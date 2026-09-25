package app.aaps.plugins.calibration

import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T

/**
 * Whether a break in the stored readings is worth asking the user about.
 *
 * The plugin reads the database and posts the notification. This object only decides.
 * [now] and [lastScanAt] are supplied by the caller (`dateUtil.now()` in the plugin).
 *
 * Ref `LinearCalibrationPlugin.detectAndNotifyGap` L381–398 @ `6598201d`:
 * - L382 scan spacing `GAP_SCAN_INTERVAL_MS` (L489, L498: half of 30 min)
 * - L385–389 stored readings over `GAP_SCAN_WINDOW_MS` (L504: 6 h)
 * - L390–393 [newestGapMidpoint], `notBefore = sessionStart`
     * - L397 the break already told this process
     * - L398 `CalibrationLongKey.IgnoredSensorGapAt`, read only after a midpoint exists
     *   (the value the ignore action writes, L416)
     */
object CalibrationGap {

    /** Ref companion L489. */
    const val THRESHOLD_MIN = 30L

    /** Ref L498: half of [THRESHOLD_MIN], so a fresh break is still found. */
    val SCAN_INTERVAL_MS = T.mins(THRESHOLD_MIN / 2).msecs()

    /** Ref L504. A break older than this is not asked about again. */
    val SCAN_WINDOW_MS = T.hours(6).msecs()

    /** Ref L382: `if (now - lastGapScanAt < GAP_SCAN_INTERVAL_MS) return`. */
    fun shouldScan(now: Long, lastScanAt: Long): Boolean =
        now - lastScanAt >= SCAN_INTERVAL_MS

    /**
     * Midpoint to ask about, or null when there is no new break.
     *
     * Does not look at a nearby `SENSOR_CHANGE`: that read stays in the plugin (ref L400–404),
     * after this decision, so an already ignored break does not query therapy events.
     */
    fun gapWorthAsking(
        readings: List<GV>,
        sessionStart: Long?,
        lastNotifiedGapAt: Long,
        ignoredGapAt: () -> Long,
    ): Long? {
        val detectedAt = newestGapMidpoint(
            readings = readings,
            gapThresholdMs = T.mins(THRESHOLD_MIN).msecs(),
            notBefore = sessionStart
        ) ?: return null
        // Ref L397, then L398. The key is not read when there is no midpoint.
        if (detectedAt == lastNotifiedGapAt) return null
        if (detectedAt == ignoredGapAt()) return null
        return detectedAt
    }
}
