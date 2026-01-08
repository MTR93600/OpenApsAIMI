package app.aaps.core.ui.elements

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.aaps.core.ui.R

/**
 * Modern Glucose Circle View
 * 
 * Displays a circular progress indicator around glucose value
 * with dynamic colors based on glucose range
 * 
 * Features:
 * - Animated arc drawing
 * - Color transitions based on BG
 * - Customizable stroke width
 * - Support for light/dark mode
 */
class GlucoseCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Paint objects
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f // dp, will be converted
        strokeCap = Paint.Cap.ROUND
    }
    
    private val bounds = RectF()
    
    // State
    private var circleColor: Int = ContextCompat.getColor(context, R.color.glucose_in_range)
    private var progress: Float = 0.75f // 0.0 to 1.0 (arc completion)
    private var animator: ValueAnimator? = null
    
    // Glucose ranges (mg/dL for default, will be converted by caller)
    enum class GlucoseRange {
        VERY_LOW,    // < 54 mg/dL
        LOW,         // 54-70 mg/dL
        IN_RANGE,    // 70-180 mg/dL
        HIGH,        // 180-250 mg/dL
        VERY_HIGH    // > 250 mg/dL
    }
    
    init {
        // Convert dp to px for stroke width
        val density = resources.displayMetrics.density
        circlePaint.strokeWidth = 8f * density
    }
    
    /**
     * Set glucose value and update circle color/animation
     * @param glucoseMgDl Glucose value in mg/dL
     * @param targetLow Target low (e.g. 70)
     * @param targetHigh Target high (e.g. 180)
     * @param animate Whether to animate the transition
     */
    fun setGlucose(
        glucoseMgDl: Double,
        targetLow: Double = 70.0,
        targetHigh: Double = 180.0,
        animate: Boolean = true
    ) {
        val range = when {
            glucoseMgDl < 54 -> GlucoseRange.VERY_LOW
            glucoseMgDl < targetLow -> GlucoseRange.LOW
            glucoseMgDl <= targetHigh -> GlucoseRange.IN_RANGE
            glucoseMgDl <= 250 -> GlucoseRange.HIGH
            else -> GlucoseRange.VERY_HIGH
        }
        
        // Set color based on range
        val newColor = when (range) {
            GlucoseRange.VERY_LOW -> ContextCompat.getColor(context, R.color.critical_low)
            GlucoseRange.LOW -> ContextCompat.getColor(context, R.color.low)
            GlucoseRange.IN_RANGE -> ContextCompat.getColor(context, R.color.glucose_in_range)
            GlucoseRange.HIGH -> ContextCompat.getColor(context, R.color.high)
            GlucoseRange.VERY_HIGH -> ContextCompat.getColor(context, R.color.critical_high)
        }
        
        // Calculate progress (0.0 = incomplete circle, 1.0 = full circle)
        // Map BG to arc completion: higher BG = more complete circle
        val normalizedBg = (glucoseMgDl - 40.0) / 360.0 // 40-400 mg/dL range
        val newProgress = normalizedBg.coerceIn(0.25, 1.0).toFloat()
        
        if (animate && newColor != circleColor) {
            // Animate color transition
            animateColorChange(newColor)
            animateProgressChange(newProgress)
        } else {
            circleColor = newColor
            progress = newProgress
            invalidate()
        }
    }
    
    /**
     * Animate circle color change
     */
    private fun animateColorChange(targetColor: Int) {
        // Simple approach: just set color (ValueAnimator for color is complex)
        // TODO: Implement ArgbEvaluator if smooth color transition needed
        circleColor = targetColor
        invalidate()
    }
    
    /**
     * Animate progress change (arc completion)
     */
    private fun animateProgressChange(targetProgress: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, targetProgress).apply {
            duration = 500 // ms
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        // Calculate bounds for arc (centered, with padding for stroke)
        val padding = circlePaint.strokeWidth / 2f + 4f
        bounds.set(
            padding,
            padding,
            width.toFloat() - padding,
            height.toFloat() - padding
        )
        
        // Set paint color
        circlePaint.color = circleColor
        
        // Draw arc (start at top -90°, sweep based on progress)
        val startAngle = -90f  // Start at 12 o'clock
        val sweepAngle = 360f * progress
        
        canvas.drawArc(
            bounds,
            startAngle,
            sweepAngle,
            false, // useCenter = false (donut, not pie)
            circlePaint
        )
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force square aspect ratio
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}
