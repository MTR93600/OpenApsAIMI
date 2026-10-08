package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable UI model for the dashboard glucose hero (Compose).
 * Colors are Android [android.graphics.Color] ARGB ints — converted locally for Canvas.
 */
@Immutable
data class GlucoseHeroUiState(
    val mainText: String = "--",
    val subLeftText: String = "",
    val subRightText: String = "",
    val noseAngleDeg: Float? = null,
    val ringColorArgb: Int,
    val centerTextColorArgb: Int,
    val subTextColorArgb: Int,
    val surfaceColorArgb: Int,
    /** Inner arc fill (0..1): trajectory relevance + health ± sensor-quality proxy — not a generic “ML %”. */
    val telemetryProgress: Float? = null,
    val telemetryColorArgb: Int? = null,
    val strokeWidthDp: Float = 4f,
)

@Composable
fun GlucoseHeroRing(
    state: GlucoseHeroUiState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val ring = Color(state.ringColorArgb)
    val surface = Color(state.surfaceColorArgb)
    val centerText = Color(state.centerTextColorArgb)
    val subTextC = Color(state.subTextColorArgb)
    val noseAngle = state.noseAngleDeg
    val mainStyle = AapsTheme.typography.bgValue.copy(color = centerText)
    val subLineStyle = AapsTheme.typography.bgTimeAgo.copy(color = subTextC)

    BoxWithConstraints(modifier) {
        val minSidePx = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val strokePx = with(density) { state.strokeWidthDp.dp.toPx() }
        val noseLengthPx = with(density) { 14.dp.toPx() }
        val noseWidthPx = with(density) { 16.dp.toPx() }
        val noseExtra = if (noseAngle != null) noseLengthPx + strokePx * 0.25f else 0f
        val ringRadius: Float = (minSidePx / 2f) - (strokePx / 2f) - noseExtra
        // BG + delta: scale with ring; time-under-BG stays slightly smaller so the hero stays readable.
        val mainSp = with(density) { (ringRadius * 1.02f / fontScale).coerceIn(24f, 78f).toSp() }
        val deltaSp = with(density) { (ringRadius * 0.50f / fontScale).coerceIn(17f, 34f).toSp() }
        val subSp = with(density) { (ringRadius * 0.24f / fontScale).coerceIn(11f, 19f).toSp() }

        Box(Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                if (ringRadius > 0f) {
                    drawCircle(color = surface, radius = ringRadius, center = Offset(cx, cy))
                    drawTelemetryTrack(
                        center = Offset(cx, cy),
                        ringRadius = ringRadius,
                        strokePx = strokePx,
                        progress = state.telemetryProgress,
                        arcColorArgb = state.telemetryColorArgb,
                    )
                    drawCircle(
                        color = ring,
                        radius = ringRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    if (noseAngle != null && ringRadius > strokePx) {
                        drawNosePointer(
                            cx = cx,
                            cy = cy,
                            radius = ringRadius,
                            strokePx = strokePx,
                            noseLengthPx = noseLengthPx,
                            noseWidthPx = noseWidthPx,
                            angleDeg = noseAngle,
                            color = ring,
                        )
                    }
                }
            }

            // Same vertical order as [app.aaps.ui.compose.overview.BgInfoSection]: delta on top, BG, time below.
            // Hybrid dashboard places metrics below the mode toggle with normal spacing; putting delta under the
            // big number left it in the obscured band — moving it above matches Overview and keeps it visible.
            val liftTime = with(density) { (ringRadius * 0.032f).toDp().coerceIn(4.dp, 9.dp) }
            val deltaTopInset = with(density) { (ringRadius * 0.085f).toDp().coerceIn(8.dp, 16.dp) }
            val deltaStyle = AapsTheme.typography.bgSecondary.copy(color = centerText)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -liftTime)
                    .padding(top = deltaTopInset),
            ) {
                if (state.subLeftText.isNotBlank()) {
                    Text(
                        text = state.subLeftText,
                        style = deltaStyle.copy(fontSize = deltaSp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                Text(
                    text = state.mainText,
                    style = mainStyle.copy(fontSize = mainSp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (state.subRightText.isNotBlank()) {
                    Text(
                        text = state.subRightText,
                        style = subLineStyle.copy(fontSize = subSp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawTelemetryTrack(
    center: Offset,
    ringRadius: Float,
    strokePx: Float,
    progress: Float?,
    arcColorArgb: Int?,
) {
    val p = progress ?: return
    if (p <= 0f) return
    val trackW = (strokePx * 0.38f).coerceIn(4f, 10f)
    val inset = strokePx + trackW * 0.55f
    val innerR = (ringRadius - inset).coerceAtLeast(ringRadius * 0.62f)
    if (innerR <= 4f) return
    val topLeft = Offset(center.x - innerR, center.y - innerR)
    val arcSize = Size(innerR * 2f, innerR * 2f)
    drawArc(
        color = Color(0x44888888),
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = trackW, cap = StrokeCap.Round),
    )
    val arc = arcColorArgb?.let { Color(it) } ?: Color(0xFF1E88E5)
    drawArc(
        color = arc,
        startAngle = -90f,
        sweepAngle = 360f * p,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = trackW, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawNosePointer(
    cx: Float,
    cy: Float,
    radius: Float,
    strokePx: Float,
    noseLengthPx: Float,
    noseWidthPx: Float,
    angleDeg: Float,
    color: Color,
) {
    val angle = angleDeg.coerceIn(-90f, 90f)
    val rad = angle * PI / 180.0
    val ux = cos(rad).toFloat()
    val uy = (-sin(rad)).toFloat()
    val baseR = radius + strokePx * 0.15f
    val tipR = radius + noseLengthPx
    val baseCx = cx + ux * baseR
    val baseCy = cy + uy * baseR
    val tipX = cx + ux * tipR
    val tipY = cy + uy * tipR
    val px = -uy
    val py = ux
    val halfW = noseWidthPx / 2f
    val p1x = baseCx + px * halfW
    val p1y = baseCy + py * halfW
    val p2x = baseCx - px * halfW
    val p2y = baseCy - py * halfW
    val path = Path().apply {
        moveTo(tipX, tipY)
        lineTo(p1x, p1y)
        lineTo(p2x, p2y)
        close()
    }
    drawPath(path, color = color)
}
