package app.aaps.ui.compose.dashboard.essential

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.AapsTheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which pull the shape answers right now. */
enum class ShapeGesture {

    /** No pull does anything: the Boost is not offered. The shape can still be tapped. */
    NONE,

    /** Pulling up past the threshold and releasing starts the Boost. */
    START,

    /** Pulling down past the threshold and releasing cancels the running Boost. */
    CANCEL,
}

/** Sizes of the shape. Local to this skin: the theme has no size for a hero shape this large. */
internal object EssentialShapeDimens {

    val box = 236.dp
    val diameter = 184.dp
    val pullThreshold = 56.dp
    val pullMax = 84.dp
    val attractor = 12.dp
}

/**
 * The living glucose shape: a closed path drawn from a few numbers ([ShapeParams]) that animate from
 * one mood to the next, with the glucose inside.
 *
 * The shape takes **vertical** drags only, so the page and the chart keep their own gestures. A pull
 * only acts when it passes [EssentialShapeDimens.pullThreshold] and is released; a shorter pull springs
 * back and does nothing. A tap never acts on insulin, it only calls [onTap].
 *
 * @param onPullProgress 0..1 while pulling in the direction that acts, for the gauge under the shape
 */
@Composable
fun LivingShape(
    mood: EssentialMood,
    bgText: String,
    unitText: String,
    trendSymbol: String,
    deltaText: String,
    description: String,
    gesture: ShapeGesture,
    onPull: () -> Unit,
    onPullProgress: (Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = mood.shapeParams()
    val easing = tween<Float>(durationMillis = 500, easing = FastOutSlowInEasing)
    val stretchY by animateFloatAsState(params.stretchY, easing, label = "stretchY")
    val lean by animateFloatAsState(params.lean, easing, label = "lean")
    val angularity by animateFloatAsState(params.angularity, easing, label = "angularity")
    val scale by animateFloatAsState(params.scale, easing, label = "scale")
    val moodColors = mood.colors()
    val core by animateColorAsState(moodColors.core, tween(500), label = "core")
    val accent by animateColorAsState(moodColors.accent, tween(500), label = "accent")
    val breath = breathing(params.breathPeriodMs)

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val thresholdPx = with(density) { EssentialShapeDimens.pullThreshold.toPx() }
    val maxPullPx = with(density) { EssentialShapeDimens.pullMax.toPx() }
    val pull = remember { Animatable(0f) }
    var crossed by remember { mutableStateOf(false) }

    // The pull counts only in the direction that acts: up to start, down to cancel
    fun actingProgress(offset: Float): Float = when (gesture) {
        ShapeGesture.START  -> (-offset / thresholdPx).coerceIn(0f, 1f)
        ShapeGesture.CANCEL -> (offset / thresholdPx).coerceIn(0f, 1f)
        ShapeGesture.NONE   -> 0f
    }

    val dragModifier = if (gesture == ShapeGesture.NONE) Modifier else Modifier.pointerInput(gesture) {
        detectVerticalDragGestures(
            onDragStart = { crossed = false },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val next = (pull.value + dragAmount).coerceIn(-maxPullPx, maxPullPx)
                scope.launch { pull.snapTo(next) }
                val progress = actingProgress(next)
                onPullProgress(progress)
                if (progress >= 1f && !crossed) {
                    crossed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else if (progress < 1f) crossed = false
            },
            onDragEnd = {
                if (actingProgress(pull.value) >= 1f) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPull()
                }
                onPullProgress(0f)
                scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.55f)) }
            },
            onDragCancel = {
                onPullProgress(0f)
                scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.55f)) }
            },
        )
    }

    Box(
        modifier = modifier
            .size(EssentialShapeDimens.box)
            .semantics { contentDescription = description }
            .focusable()
            .onKeyEvent { event ->
                // Keyboard path to the same actions, never the only one: the sheet has buttons too
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    event.key == Key.DirectionUp && gesture == ShapeGesture.START    -> onPull().let { true }
                    event.key == Key.DirectionDown && gesture == ShapeGesture.CANCEL -> onPull().let { true }
                    else                                                             -> false
                }
            }
            .then(dragModifier)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (gesture == ShapeGesture.START) Attractor(accent, Modifier.align(Alignment.TopCenter))
        val pullPx = pull.value
        val highlight = AapsTheme.generalColors.onBadge
        Canvas(Modifier.fillMaxSize()) {
            val pullStretch = 1f + (abs(pullPx) / maxPullPx) * 0.16f
            drawLivingShape(
                stretchY = stretchY * pullStretch,
                lean = lean,
                angularity = angularity,
                scale = scale * (1f + params.breathAmplitude * breathShape(breath.value, params.pulses)),
                offsetY = pullPx * 0.6f,
                core = core,
                accent = accent,
                highlight = highlight,
                radiusPx = EssentialShapeDimens.diameter.toPx() / 2f,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset { IntOffset(0, (pullPx * 0.6f).roundToInt()) },
        ) {
            val onShape = AapsTheme.generalColors.onBadge
            Text(bgText, color = onShape, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
            Text(unitText, color = onShape.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trendSymbol, color = onShape, style = MaterialTheme.typography.titleMedium)
                Text(deltaText, color = onShape.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.offset(x = 6.dp))
            }
        }
    }
}

/**
 * How much of the breath is shown: a soft wave for breathing, one short beat per period for a pulse
 * (the only pulsing mood is HYPO). [value] runs between -1 and 1.
 */
private fun breathShape(value: Float, pulses: Boolean): Float =
    if (pulses) ((value + 1f) / 2f).let { it * it * it } else value

/** -1..1 of one breath, or a still 0 when [periodMs] is 0. */
@Composable
private fun breathing(periodMs: Int): State<Float> {
    if (periodMs <= 0) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "breath")
    return key(periodMs) {
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs / 2, easing = LinearEasing), RepeatMode.Reverse),
            label = "breathValue",
        )
    }
}

/** The light point above the shape that shows where a pull would take it. */
@Composable
private fun Attractor(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "attractor")
    val glow by transition.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Canvas(modifier.size(EssentialShapeDimens.attractor * 3)) {
        drawCircle(color.copy(alpha = 0.25f * glow), radius = size.minDimension / 2f)
        drawCircle(color.copy(alpha = glow), radius = EssentialShapeDimens.attractor.toPx() / 2f)
    }
}

/**
 * Draws the blob: [POINTS] points on a circle, moved by the parameters, joined by smooth curves.
 * Angularity shortens the curve handles, which turns the round joins into corners.
 */
private fun DrawScope.drawLivingShape(
    stretchY: Float,
    lean: Float,
    angularity: Float,
    scale: Float,
    offsetY: Float,
    core: Color,
    accent: Color,
    highlight: Color,
    radiusPx: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f + offsetY)
    val radius = radiusPx * scale
    val points = List(POINTS) { i ->
        val angle = (2.0 * PI * i / POINTS).toFloat()
        val up = -sin(angle) // 1 at the top of the screen
        // Lean makes an egg (wider at the top) or a drop (wider at the bottom)
        val r = radius * (1f + 0.12f * lean * up)
        // The whole shape also moves a little toward where it leans
        Offset(center.x + r * cos(angle), center.y - r * stretchY * up - lean * radius * 0.08f)
    }
    val handle = HANDLE * (1f - 0.8f * angularity.coerceIn(0f, 1f))
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in points.indices) {
            val p0 = points[(i - 1 + POINTS) % POINTS]
            val p1 = points[i]
            val p2 = points[(i + 1) % POINTS]
            val p3 = points[(i + 2) % POINTS]
            val c1 = Offset(p1.x + (p2.x - p0.x) * handle, p1.y + (p2.y - p0.y) * handle)
            val c2 = Offset(p2.x - (p3.x - p1.x) * handle, p2.y - (p3.y - p1.y) * handle)
            cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
        close()
    }
    // A soft halo, then the body: core at the centre, accent toward the side the shape leans to
    drawCircle(accent.copy(alpha = 0.16f), radius = radius * 1.18f, center = center)
    val gradientEnd = if (lean >= 0f) center.y - radius * stretchY else center.y + radius * stretchY
    drawPath(path, Brush.verticalGradient(listOf(core, accent), startY = center.y, endY = gradientEnd))
    drawPath(
        path,
        Brush.radialGradient(
            listOf(highlight.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f),
            radius = radius * 0.9f,
        ),
    )
}

private const val POINTS = 8

/** Catmull-Rom handle length for 8 points, close to a circle when nothing moves the points. */
private const val HANDLE = 0.18f
