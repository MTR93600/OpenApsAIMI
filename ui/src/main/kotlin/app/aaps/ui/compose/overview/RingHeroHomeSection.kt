package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.dashboard.GlucoseHeroRing
import app.aaps.core.ui.compose.dashboard.GlucoseHeroUiState
import app.aaps.core.ui.views.GlucoseRingColorComputer
import kotlin.math.roundToInt

/**
 * Compose-only glucose hero for the main overview when the hybrid dashboard layout is enabled:
 * same data as [BgInfoSection], ring palette aligned with [GlucoseRingColorComputer] / hybrid dashboard.
 */
@Composable
fun RingHeroHomeSection(
    bgInfo: BgInfoData?,
    timeAgoText: String,
    modifier: Modifier = Modifier,
    size: Dp = AapsSpacing.bgCircleSize,
) {
    if (bgInfo == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(size)
        ) {
            Text(
                text = "---",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val context = LocalContext.current
    val profileUtil = LocalProfileUtil.current
    val bgMgdl = profileUtil.convertToMgdlDetect(bgInfo.bgValue).roundToInt()

    val centerTextColorArgb = when (bgInfo.bgRange) {
        BgRange.HIGH -> AapsTheme.generalColors.bgHigh.toArgb()
        BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange.toArgb()
        BgRange.LOW -> AapsTheme.generalColors.bgLow.toArgb()
    }
    val subTextColorArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    val heroState = remember(
        bgInfo,
        timeAgoText,
        bgMgdl,
        centerTextColorArgb,
        subTextColorArgb,
        profileUtil.units,
    ) {
        val step1 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step1)
        val step2 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step2)
        val step3 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step3)
        val step4 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step4)
        val ringArgb = GlucoseRingColorComputer.compute(
            bgMgdl = bgMgdl,
            hypoMaxFromProfile = null,
            severeHypoMaxMgdl = 54f,
            hypoMaxMgdlAttr = 70f,
            useSteppedColors = true,
            step1MaxMgdl = 120f,
            step2MaxMgdl = 160f,
            step3MaxMgdl = 220f,
            stepColor1 = step1,
            stepColor2 = step2,
            stepColor3 = step3,
            stepColor4 = step4,
        )
        GlucoseHeroUiState(
            mainText = bgInfo.bgText,
            subLeftText = bgInfo.deltaText.orEmpty(),
            subRightText = timeAgoText,
            noseAngleDeg = noseAngleDegForTrend(bgInfo.trendArrow),
            ringColorArgb = ringArgb,
            centerTextColorArgb = centerTextColorArgb,
            subTextColorArgb = subTextColorArgb,
            surfaceColorArgb = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_surface),
            telemetryProgress = null,
            telemetryColorArgb = null,
            strokeWidthDp = 4f,
        )
    }

    val a11yDescription = buildString {
        append("BG ${bgInfo.bgText}")
        append(", ${bgInfo.trendDescription}")
        bgInfo.deltaText?.let { append(", delta $it") }
        if (timeAgoText.isNotEmpty()) append(", $timeAgoText ago")
        if (bgInfo.isOutdated) append(", outdated")
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = a11yDescription }
    ) {
        GlucoseHeroRing(state = heroState, modifier = Modifier.fillMaxSize())
    }
}

/** Same angles as the trend arc in [BgInfoSection] (nose on [GlucoseHeroRing]). */
private fun noseAngleDegForTrend(trend: TrendArrow?): Float? =
    when (trend) {
        null, TrendArrow.NONE -> null
        TrendArrow.FLAT -> 0f
        TrendArrow.FORTY_FIVE_UP -> -45f
        TrendArrow.FORTY_FIVE_DOWN -> 45f
        TrendArrow.SINGLE_UP, TrendArrow.DOUBLE_UP, TrendArrow.TRIPLE_UP -> -90f
        TrendArrow.SINGLE_DOWN, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_DOWN -> 90f
    }
