package app.aaps.core.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import app.aaps.core.ui.R

/**
 * GlucoseRingView - Cercle avec "nose pointer" (feature/circle-top)
 * 
 * Affiche :
 * - Arc coloré selon BG range (vert/jaune/orange/rouge)
 * - "Nose pointer" (triangle) qui pointe selon delta (-90° à +90°)
 * - BG value au centre (bold, large)
 * - 2 subtexts en bas : time ago + delta
 * 
 * Source : https://github.com/RBarth-DE/OpenApsAIMI/tree/feature/circle-top
 */
class GlucoseRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var strokeWidthPx: Float = dp(6f)
    private var backgroundColor: Int = Color.TRANSPARENT
    private var mainTextColor: Int = Color.WHITE
    private var subTextColor: Int = Color.WHITE

    private var useSteppedColors: Boolean = true
    /** Below this mg/dL (exclusive) → step4 — typically level 1 hypo (e.g. 54). */
    private var severeHypoMaxMgdl: Float = 54f
    /** Below this mg/dL (exclusive) but ≥ [severeHypoMaxMgdl] → step3 — often aligned with profile target low. */
    private var hypoMaxMgdlAttr: Float = 70f

    private var step1MaxMgdl: Float = 100f
    private var step2MaxMgdl: Float = 160f
    private var step3MaxMgdl: Float = 220f

    private var stepColor1: Int = ContextCompat.getColor(context, R.color.glucose_ring_step1)
    private var stepColor2: Int = ContextCompat.getColor(context, R.color.glucose_ring_step2)
    private var stepColor3: Int = ContextCompat.getColor(context, R.color.glucose_ring_step3)
    private var stepColor4: Int = ContextCompat.getColor(context, R.color.glucose_ring_step4)

    private var mainTextBold: Boolean = true

    private var noseLengthPx: Float = dp(14f)
    private var noseWidthPx: Float = dp(16f)

    private var mainText: String = "--"
    private var subLeftText: String = ""
    private var subRightText: String = ""
    private var bgMgdl: Int? = null
    private var noseAngleDeg: Float? = null

    /** If set on [update], tints the main BG text (ring/cercle use [computeRingColor] when [overrideColor] is null). */
    private var centerGlucoseTextColorOverride: Int? = null

    /** Optional inner telemetry arc (0..1), e.g. blended AIMI relevance / health. */
    private var telemetryArcProgress: Float? = null

    @ColorInt
    private var telemetryArcColor: Int = ContextCompat.getColor(context, R.color.iob)

    private val arcBounds = RectF()

    // Derived each update
    private var currentRingColor: Int = Color.GREEN

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val telemetryTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val telemetryArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val mainTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    init {
        // Read custom attrs if available
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlucoseRingView)
        try {
            strokeWidthPx = a.getDimension(R.styleable.GlucoseRingView_ringStrokeWidth, strokeWidthPx)
            backgroundColor = a.getColor(R.styleable.GlucoseRingView_ringBackgroundColor, backgroundColor)
            mainTextColor = a.getColor(R.styleable.GlucoseRingView_ringTextColor, mainTextColor)
            subTextColor = a.getColor(R.styleable.GlucoseRingView_ringSubTextColor, subTextColor)

            // Nose sizing (optional attrs; still clamped to stroke width below)
            noseLengthPx = a.getDimension(R.styleable.GlucoseRingView_ringNoseLength, noseLengthPx)
            noseWidthPx = a.getDimension(R.styleable.GlucoseRingView_ringNoseWidth, noseWidthPx)

            // Stepped colors (optional)
            useSteppedColors = a.getBoolean(R.styleable.GlucoseRingView_glucoseRingUseSteppedColors, useSteppedColors)
            severeHypoMaxMgdl = a.getFloat(R.styleable.GlucoseRingView_glucoseRingSevereHypoMaxMgdl, severeHypoMaxMgdl)
            hypoMaxMgdlAttr = a.getFloat(R.styleable.GlucoseRingView_glucoseRingHypoMaxMgdl, hypoMaxMgdlAttr)
            step1MaxMgdl = a.getFloat(R.styleable.GlucoseRingView_glucoseRingStep1MaxMgdl, step1MaxMgdl)
            step2MaxMgdl = a.getFloat(R.styleable.GlucoseRingView_glucoseRingStep2MaxMgdl, step2MaxMgdl)
            step3MaxMgdl = a.getFloat(R.styleable.GlucoseRingView_glucoseRingStep3MaxMgdl, step3MaxMgdl)

            stepColor1 = a.getColor(R.styleable.GlucoseRingView_glucoseRingStepColor1, stepColor1)
            stepColor2 = a.getColor(R.styleable.GlucoseRingView_glucoseRingStepColor2, stepColor2)
            stepColor3 = a.getColor(R.styleable.GlucoseRingView_glucoseRingStepColor3, stepColor3)
            stepColor4 = a.getColor(R.styleable.GlucoseRingView_glucoseRingStepColor4, stepColor4)
        } finally {
            a.recycle()
        }
        // Nose scales with stroke width
        noseLengthPx = maxOf(noseLengthPx, strokeWidthPx * 1.6f)
        noseWidthPx = maxOf(noseWidthPx, strokeWidthPx * 1.4f)
    }

    fun update(
        bgMgdl: Int?,
        mainText: String,
        subLeftText: String,
        subRightText: String,
        noseAngleDeg: Float?,
        overrideColor: Int? = null,
        centerGlucoseTextColor: Int? = null,
        hypoMaxMgdlForComputation: Float? = null,
        telemetryArcProgress: Float? = null,
        @ColorInt telemetryArcColor: Int? = null,
    ) {
        this.bgMgdl = bgMgdl
        this.mainText = mainText
        this.subLeftText = subLeftText
        this.subRightText = subRightText
        this.noseAngleDeg = noseAngleDeg
        this.centerGlucoseTextColorOverride = centerGlucoseTextColor
        this.telemetryArcProgress = telemetryArcProgress?.coerceIn(0f, 1f)
        if (telemetryArcColor != null) {
            this.telemetryArcColor = telemetryArcColor
        }

        currentRingColor = overrideColor ?: computeRingColor(bgMgdl, hypoMaxMgdlForComputation)

        invalidate()
    }

    private fun computeRingColor(bgMgdl: Int?, hypoMaxOverride: Float?): Int =
        GlucoseRingColorComputer.compute(
            bgMgdl = bgMgdl,
            hypoMaxFromProfile = hypoMaxOverride,
            severeHypoMaxMgdl = severeHypoMaxMgdl,
            hypoMaxMgdlAttr = hypoMaxMgdlAttr,
            useSteppedColors = useSteppedColors,
            step1MaxMgdl = step1MaxMgdl,
            step2MaxMgdl = step2MaxMgdl,
            step3MaxMgdl = step3MaxMgdl,
            stepColor1 = stepColor1,
            stepColor2 = stepColor2,
            stepColor3 = stepColor3,
            stepColor4 = stepColor4,
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f

        // keep the nose inside view bounds
        val noseExtra = if (noseAngleDeg != null) (noseLengthPx + strokeWidthPx * 0.25f) else 0f
        val radius = (min(w, h) / 2f) - (strokeWidthPx / 2f) - noseExtra
        if (radius <= 0f) return

        // background fill
        bgPaint.color = backgroundColor
        canvas.drawCircle(cx, cy, radius, bgPaint)

        drawTelemetryArcIfNeeded(canvas, cx, cy, radius)

        // ring
        ringPaint.strokeWidth = strokeWidthPx
        ringPaint.color = currentRingColor
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // nose
        drawNose(canvas, cx, cy, radius)

        // main text (bold)
        mainTextPaint.color = centerGlucoseTextColorOverride ?: mainTextColor
        mainTextPaint.isFakeBoldText = mainTextBold
        mainTextPaint.textSize = radius * 0.7f //0.6 to 0.7 to make it bigger

        val mainY = cy - (mainTextPaint.descent() + mainTextPaint.ascent()) / 2f - dp(16f) // push BG higher (more gap to subtext)
        canvas.drawText(mainText, cx, mainY, mainTextPaint)

        // sub texts
        subTextPaint.color = subTextColor
        subTextPaint.textSize = radius * 0.2f
        subTextPaint.isFakeBoldText = false

        val lineH = subTextPaint.fontSpacing
        val y1 = cy + radius * 0.45f // subtext lower (bigger gap from BG)
        val y2 = y1 + lineH * 0.95f

        val x = cx
        canvas.drawText(subLeftText,  x - subTextPaint.measureText(subLeftText)/2f,  y1, subTextPaint)
        canvas.drawText(subRightText, x - subTextPaint.measureText(subRightText)/2f, y2, subTextPaint)
    }

    private fun drawTelemetryArcIfNeeded(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val p = telemetryArcProgress ?: return
        if (p <= 0f) return
        val trackW = (strokeWidthPx * 0.38f).coerceIn(dp(1.4f), dp(3f))
        val inset = strokeWidthPx + trackW * 0.55f
        val innerR = (radius - inset).coerceAtLeast(radius * 0.62f)
        if (innerR <= dp(4f)) return
        arcBounds.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        telemetryTrackPaint.strokeWidth = trackW
        telemetryTrackPaint.color = 0x44888888
        canvas.drawArc(arcBounds, -90f, 360f, false, telemetryTrackPaint)

        telemetryArcPaint.strokeWidth = trackW
        telemetryArcPaint.color = telemetryArcColor
        canvas.drawArc(arcBounds, -90f, 360f * p, false, telemetryArcPaint)
    }

    private fun drawNose(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // angleDeg: -90..+90 ; 0=RIGHT ; +90=UP ; -90=DOWN
        val angleDeg = (noseAngleDeg ?: return).coerceIn(-90f, 90f)
        val rad = Math.toRadians(angleDeg.toDouble())

        val ux = cos(rad).toFloat()
        val uy = (-sin(rad)).toFloat()   // Android Y-Achse nach unten

        val baseR = radius + strokeWidthPx * 0.15f
        val tipR = radius + noseLengthPx

        val baseCx = cx + ux * baseR
        val baseCy = cy + uy * baseR
        val tipX = cx + ux * tipR
        val tipY = cy + uy * tipR

        // perpendicular
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

        val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = currentRingColor
        }
        canvas.drawPath(path, nosePaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
