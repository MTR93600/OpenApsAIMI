package app.aaps.wear.complications

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import app.aaps.wear.data.ComplicationData as Store
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal fun eatingModeColor(size: Int): Int = when (size) {
    0 -> Color.rgb(185, 245, 121)
    1 -> Color.rgb(69, 218, 177)
    2 -> Color.rgb(33, 152, 100)
    4 -> Color.rgb(255, 213, 86)
    else -> Color.LTGRAY
}

private const val HISTORY_OPACITY = 128 // 50% opacity, including glow and overlapping segments

internal object AimiDialGeometry {
    const val TBR_INNER = 136f
    const val TBR_OUTER = 158f
    const val GLUCOSE_INNER = TBR_OUTER
    const val GLUCOSE_OUTER = 221f
    const val GLUCOSE_MIN = 55.0
    const val GLUCOSE_MAX = 220.0
    fun angleDegrees(hour: Int, minute: Int): Double = ((((hour % 12) * 60) + minute) / 720.0) * 360.0 - 90.0
    fun rollingAngleDegrees(timestamp: Long, now: Long, currentMinute: Double): Double =
        currentMinute * 6.0 - 90.0 + ((timestamp - now) / 43_200_000.0) * 360.0
    fun glucoseRadius(bg: Double): Float = GLUCOSE_INNER + ((bg.coerceIn(GLUCOSE_MIN, GLUCOSE_MAX) - GLUCOSE_MIN) / (GLUCOSE_MAX - GLUCOSE_MIN) * (GLUCOSE_OUTER - GLUCOSE_INNER)).toFloat()
    fun tbrRadius(rate: Double, maxRate: Double): Float = TBR_INNER + (rate.coerceIn(0.0, maxRate) / maxRate.coerceAtLeast(0.1) * (TBR_OUTER - TBR_INNER)).toFloat()
    fun bandsDoNotOverlap(): Boolean = TBR_OUTER <= GLUCOSE_INNER
    fun smbLabel(bolus: Double): String = "%.2f ед".format(Locale.US, bolus)
    fun smbLabelRadius(closeLevel: Int): Float = 157f - closeLevel.coerceIn(0, 4) * 15f
}

/** AMOLED 12-hour AIMI dial. Geometry is clock-fixed: 00/03/06/09 never move. */
class AimiDialComplication : ModernBaseComplicationProviderService() {
    override fun buildComplicationData(type: ComplicationType, data: Store, complicationPendingIntent: PendingIntent): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val bitmap = render(data)
        val icon = Icon.createWithBitmap(bitmap)
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("AIMI 12h").build()
        ).setTapAction(complicationPendingIntent).build()
    }

    override fun getComplicationAction() = ComplicationAction.NONE

    private fun render(data: Store): Bitmap {
        val size = 450
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.BLACK)
        val cx = 225f; val cy = 225f
        val displays = getSystemService(android.hardware.display.DisplayManager::class.java)
        val rotation = displays.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
        c.rotate(-90f * rotation, cx, cy)
        fun paint(color: Int, stroke: Float = 2f, style: Paint.Style = Paint.Style.STROKE, text: Float = 20f, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = stroke; this.style = style; textSize = text; textAlign = Paint.Align.CENTER
            this.alpha = alpha; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun glucoseColor(sgv: Double): Int = when {
            sgv < 70.0 -> Color.rgb(255, 70, 90)
            sgv > 180.0 -> Color.rgb(255, 166, 45)
            else -> Color.rgb(0, 242, 150)
        }
        c.drawCircle(cx, cy, AimiDialGeometry.TBR_INNER, paint(Color.rgb(30, 45, 56), 1f))
        c.drawCircle(cx, cy, AimiDialGeometry.GLUCOSE_INNER, paint(Color.rgb(70, 90, 98), 1f))
        val lowBoundary = AimiDialGeometry.glucoseRadius(70.0)
        val highBoundary = AimiDialGeometry.glucoseRadius(180.0)
        c.drawCircle(cx, cy, lowBoundary, paint(Color.rgb(255, 92, 112), 2.4f, alpha = 77))
        c.drawCircle(cx, cy, highBoundary, paint(Color.rgb(255, 184, 70), 2.4f, alpha = 77))
        fun radius(bg: Double): Float = AimiDialGeometry.glucoseRadius(bg)
        val nowMs = System.currentTimeMillis()
        val clock = Calendar.getInstance().apply { timeInMillis = nowMs }
        val currentMinute = clock.get(Calendar.MINUTE) + clock.get(Calendar.SECOND) / 60.0
        fun angle(ms: Long): Double = AimiDialGeometry.rollingAngleDegrees(ms, nowMs, currentMinute)
        val cutoff = nowMs - 12L * 60L * 60L * 1000L
        val glucose = data.graphData.entries.filter { it.timeStamp in cutoff..nowMs && it.sgv > 0 }.sortedBy { it.timeStamp }
        val glucoseLayer = c.saveLayerAlpha(null, HISTORY_OPACITY)
        for (index in 1 until glucose.size) {
            val from = glucose[index - 1]; val to = glucose[index]
            val a1 = Math.toRadians(angle(from.timeStamp)); val r1 = radius(from.sgv)
            val a2 = Math.toRadians(angle(to.timeStamp)); val r2 = radius(to.sgv)
            val x1 = cx + cos(a1).toFloat() * r1; val y1 = cy + sin(a1).toFloat() * r1
            val x2 = cx + cos(a2).toFloat() * r2; val y2 = cy + sin(a2).toFloat() * r2
            val color = glucoseColor(to.sgv)
            c.drawLine(x1, y1, x2, y2, paint(color, 12f, alpha = 50))
            c.drawLine(x1, y1, x2, y2, paint(color, 5.5f))
        }
        val currentAngle = Math.toRadians(currentMinute * 6.0 - 90.0)
        val currentRadius = radius(data.bgData.sgv)
        glucose.lastOrNull()?.let { last ->
            val a = Math.toRadians(angle(last.timeStamp)); val r = radius(last.sgv)
            val x1 = cx + cos(a).toFloat() * r; val y1 = cy + sin(a).toFloat() * r
            val x2 = cx + cos(currentAngle).toFloat() * currentRadius; val y2 = cy + sin(currentAngle).toFloat() * currentRadius
            val color = glucoseColor(data.bgData.sgv)
            c.drawLine(x1, y1, x2, y2, paint(color, 12f, alpha = 50))
            c.drawLine(x1, y1, x2, y2, paint(color, 5.5f))
        }
        c.restoreToCount(glucoseLayer)
        val currentX = cx + cos(currentAngle).toFloat() * currentRadius
        val currentY = cy + sin(currentAngle).toFloat() * currentRadius
        val currentColor = glucoseColor(data.bgData.sgv)
        c.drawCircle(currentX, currentY, 10f, paint(currentColor, style = Paint.Style.FILL, alpha = 85))
        c.drawCircle(currentX, currentY, 5.5f, paint(Color.WHITE, style = Paint.Style.FILL))

        data.treatmentData.boluses.filter { it.isValid && it.isSMB && it.date in cutoff..nowMs }.sortedBy { it.date }.forEach { smb ->
            val a = Math.toRadians(angle(smb.date)); val r = 201f
            val x = cx + cos(a).toFloat() * r; val y = cy + sin(a).toFloat() * r
            val tangentX = -sin(a).toFloat(); val tangentY = cos(a).toFloat()
            val inwardX = -cos(a).toFloat(); val inwardY = -sin(a).toFloat()
            val triangle = Path().apply {
                moveTo(x + inwardX * 10f, y + inwardY * 10f)
                lineTo(x + tangentX * 5f, y + tangentY * 5f)
                lineTo(x - tangentX * 5f, y - tangentY * 5f); close()
            }
            c.drawPath(triangle, paint(Color.rgb(45, 205, 255), style = Paint.Style.FILL))
        }
        val activeTemps = data.treatmentData.temps.filter { it.endTime >= cutoff && it.startTime <= nowMs }
        val maxTbr = activeTemps.maxOfOrNull { maxOf(it.amount, it.startBasal, it.endBasal) }?.coerceAtLeast(1.0) ?: 1.0
        var previousTbrRadius: Float? = null
        val tbrLayer = c.saveLayerAlpha(null, HISTORY_OPACITY)
        activeTemps.sortedBy { it.startTime }.forEach { temp ->
            val visibleStart = maxOf(temp.startTime, cutoff)
            val visibleEnd = minOf(temp.endTime, nowMs)
            if (visibleEnd <= visibleStart) return@forEach
            val start = angle(visibleStart).toFloat(); var sweep = ((visibleEnd - visibleStart) / 120_000.0).toFloat()
            sweep = min(360f, sweep.coerceAtLeast(1f))
            val color = Color.rgb(45, 205, 255)
            val rr = AimiDialGeometry.tbrRadius(temp.amount, maxTbr)
            previousTbrRadius?.let { previous ->
                val a = Math.toRadians(start.toDouble())
                c.drawLine(
                    cx + cos(a).toFloat() * previous, cy + sin(a).toFloat() * previous,
                    cx + cos(a).toFloat() * rr, cy + sin(a).toFloat() * rr,
                    paint(color, 4f)
                )
            }
            val ring = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(ring, start, sweep, false, paint(color, 9f, alpha = 45))
            c.drawArc(ring, start, sweep, false, paint(color, 4f))
            previousTbrRadius = rr
        }
        c.restoreToCount(tbrLayer)

        // Action and therapy status are background layers. Hands remain fully visible above them.
        c.drawCircle(cx, cy, 19f, paint(Color.rgb(134, 202, 238), 2f))
        val dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(clock.time)

        // Center the actual glyphs, including the decimal separator, inside the inner dial.
        val neon = paint(currentColor, style = Paint.Style.FILL, text = 160f)
        val reading = data.bgData.sgvString.replace(',', '.')
        val width = neon.measureText(reading)
        if (width > 235f) neon.textSize *= 235f / width
        neon.textAlign = Paint.Align.LEFT
        val digits = Path()
        neon.getTextPath(reading, 0, reading.length, 0f, 0f, digits)
        val bounds = RectF()
        digits.computeBounds(bounds, true)
        digits.offset(cx - bounds.centerX(), cy - bounds.centerY())
        digits.computeBounds(bounds, true)
        // Hands are continuous. Actual glyph pixels are rendered later and therefore win only
        // where letters or digits exist, without reserving rectangular dead zones.
        val minute = currentMinute.toFloat()
        val hour = (clock.get(Calendar.HOUR) + minute / 60f)
        fun hand(angleDeg: Double, length: Float, width: Float, tail: Float, color: Int) {
            val a = Math.toRadians(angleDeg - 90.0)
            val ux = cos(a).toFloat(); val uy = sin(a).toFloat(); val px = -uy; val py = ux
            val handPath = Path().apply {
                moveTo(cx - ux * tail + px * width * 0.45f, cy - uy * tail + py * width * 0.45f)
                lineTo(cx + ux * length, cy + uy * length)
                lineTo(cx - ux * tail - px * width * 0.45f, cy - uy * tail - py * width * 0.45f)
                close()
            }
            c.drawPath(handPath, paint(Color.BLACK, 10f))
            c.drawPath(handPath, paint(color, 9f, alpha = 75))
            c.drawPath(handPath, paint(color, style = Paint.Style.FILL, alpha = 245))
            c.drawPath(handPath, paint(Color.rgb(225, 190, 255), 1.8f, alpha = 235))
        }
        val purpleNeon = Color.rgb(166, 74, 255)
        val mode = getSharedPreferences("aimi_dial_mode", MODE_PRIVATE)
        if (nowMs < mode.getLong("until", 0L)) {
            val modeColor = eatingModeColor(mode.getInt("size", -1))
            fun outlinedModeDot(x: Float, color: Int) {
                c.drawCircle(x, 127f, 9f, paint(color, 7f, alpha = 55))
                c.drawCircle(x, 127f, 7f, paint(color, 2.8f))
            }
            outlinedModeDot(338f, modeColor)
            if (mode.getBoolean("fat", false)) outlinedModeDot(362f, Color.rgb(255, 213, 86))
        }
        hand(hour * 30.0, 112f, 20f, 18f, purpleNeon)
        hand(minute * 6.0, currentRadius, 13f, 25f, purpleNeon)

        c.drawText(dateText, cx, 138f, paint(Color.BLACK, 6f, Paint.Style.STROKE, 31f))
        c.drawText(dateText, cx, 138f, paint(Color.WHITE, style = Paint.Style.FILL, text = 31f))

        // Layered neon tube: a quiet glass core, a wide colored halo and a crisp hot edge.
        neon.alpha = 145
        c.drawPath(digits, neon)
        neon.style = Paint.Style.STROKE
        neon.strokeWidth = 8f
        neon.maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        neon.alpha = 125
        c.drawPath(digits, neon)
        neon.maskFilter = null
        neon.strokeWidth = 2.6f
        neon.color = Color.rgb(
            (Color.red(currentColor) + 255) / 2,
            (Color.green(currentColor) + 255) / 2,
            (Color.blue(currentColor) + 255) / 2
        )
        neon.alpha = 255
        c.drawPath(digits, neon)

        c.drawText("IOB  ${data.statusData.iobSum}", cx, 302f, paint(Color.WHITE, style = Paint.Style.FILL, text = 23f))
        c.drawText("TBR  ${data.statusData.currentBasal}", cx, 337f, paint(Color.WHITE, style = Paint.Style.FILL, text = 23f))

        // Moving time labels replace the fixed dial. Their glyphs stay horizontal while their
        // centers follow the corresponding hands and remain above graphs and hand shapes.
        fun movingTimeLabel(value: String, angleDeg: Double, labelRadius: Float, color: Int) {
            val a = Math.toRadians(angleDeg - 90.0)
            val x = cx + cos(a).toFloat() * labelRadius
            val label = paint(color, style = Paint.Style.FILL, text = 19f)
            val y = cy + sin(a).toFloat() * labelRadius - (label.ascent() + label.descent()) / 2f
            c.drawText(value, x, y, paint(Color.BLACK, 5f, Paint.Style.STROKE, 19f))
            c.drawText(value, x, y, label)
        }
        val displayedHour = clock.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        movingTimeLabel("%02d".format(displayedHour), hour * 30.0, 178f, Color.WHITE)
        movingTimeLabel("%02d".format(clock.get(Calendar.MINUTE)), minute * 6.0, 207f, Color.rgb(225, 190, 255))
        c.drawCircle(cx, cy, 9f, paint(Color.rgb(0, 242, 150), style = Paint.Style.FILL, alpha = 65))
        c.drawCircle(cx, cy, 5f, paint(Color.WHITE, style = Paint.Style.FILL))

        return bitmap
    }
}
