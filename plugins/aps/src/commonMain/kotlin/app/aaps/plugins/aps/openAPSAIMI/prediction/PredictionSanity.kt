package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import kotlin.math.roundToInt

internal data class PredictionSanityResult(
    val predBg: Double,
    val eventualBg: Double,
    val label: String,
)

/**
 * Clamps and repairs prediction traces when series are short, non-finite, or inconsistent with BG/delta.
 * Extracted from `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2` without behaviour change.
 */
internal fun sanitizePredictionValues(
    bg: Double,
    delta: Float,
    predBgRaw: Double?,
    eventualBgRaw: Double?,
    series: Predictions?,
    log: MutableList<String>? = null,
): PredictionSanityResult {
    val baseBg = bg.coerceIn(25.0, 600.0)
    var predBg = (predBgRaw ?: baseBg).coerceIn(25.0, 600.0)
    var eventualBg = (eventualBgRaw ?: predBg).coerceIn(25.0, 600.0)
    val anomalies = mutableListOf<String>()

    val lengths = listOfNotNull(series?.IOB, series?.COB, series?.ZT, series?.UAM)
    val minSize = lengths.minOfOrNull { it.size } ?: 0
    if (minSize in 1..5) {
        anomalies.add("series<$minSize")
    }

    if (!predBg.isFinite() || !eventualBg.isFinite()) {
        anomalies.add("nonFinite")
        predBg = baseBg
        eventualBg = baseBg
    }

    val largeDrop = baseBg - predBg
    val rising = delta >= 0f
    if (rising && baseBg > 140 && largeDrop > 80) {
        predBg = (baseBg + delta * 6).coerceIn(25.0, 600.0)
        // Same repair as the moderate branch: a drop deemed unrealistic for predBg is just as
        // unrealistic for eventualBg; leaving it raw suppressed SMBs while high and rising.
        eventualBg = maxOf(eventualBg, predBg)
        anomalies.add("jumpClamp")
    } else if (rising && baseBg > 110 && largeDrop > 60) {
        predBg = (baseBg + delta * 4).coerceIn(25.0, 600.0)
        eventualBg = maxOf(eventualBg, predBg)
        anomalies.add("jumpClampModerate")
    }

    if (eventualBg < 25 || eventualBg > 600) {
        anomalies.add("eventualRange")
        eventualBg = predBg
    }

    val label = if (anomalies.isEmpty()) "ok" else anomalies.joinToString("+")
    if (anomalies.isNotEmpty()) {
        log?.add(
            "PRED_SANITY_FAIL: $label bg=${baseBg.roundToInt()} pred=${predBg.roundToInt()} ev=${eventualBg.roundToInt()} delta=${aimiFmt1(delta.toDouble())}",
        )
    }

    return PredictionSanityResult(predBg, eventualBg, label)
}
