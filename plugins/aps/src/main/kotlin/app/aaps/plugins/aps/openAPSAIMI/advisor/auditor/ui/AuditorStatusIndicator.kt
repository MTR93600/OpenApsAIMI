package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.aaps.core.ui.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState

/**
 * AIMI Auditor Status Indicator - Custom View
 * 
 * Displays Auditor status in toolbar with:
 * - Color-coded icon (grey/blue/green/orange/red)
 * - Badge overlay with count or symbol
 * - Pulse animation when processing
 * - Bounce animation when new insights available
 * 
 * Usage in MainActivity toolbar:
 * ```
 * val auditorIndicator = AuditorStatusIndicator(context)
 * toolbar.addView(auditorIndicator)
 * auditorIndicator.setState(uiState)
 * ```
 */
class AuditorStatusIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    private val iconView: ImageView
    private val badgeView: TextView
    
    private var currentState: AuditorUIState? = null
    private var isAnimating = false
    
    init {
        // Set view dimensions (24dp icon + badge margin)
        val size = (24 * resources.displayMetrics.density).toInt()
        layoutParams = LayoutParams(size, size)
        
        // Create icon ImageView
        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_audit_monitor)
            contentDescription = "AIMI Auditor Status"
        }
        addView(iconView)
        
        // Create badge TextView (overlay on top-right)
        badgeView = TextView(context).apply {
            val badgeSize = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            gravity = Gravity.CENTER
            textSize = 9f  // Small text
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.auditor_badge_background)
            elevation = 4f  // Above icon
            isVisible = false  // Hidden by default
        }
        addView(badgeView)
        
        // Default state
        setState(AuditorUIState.idle())
    }
    
    /**
     * Update indicator with new UI state
     * Handles icon color, badge, and animations
     */
    fun setState(newState: AuditorUIState) {
        val previousState = currentState
        currentState = newState
        
        // Update icon tint color
        iconView.setColorFilter(
            ContextCompat.getColor(context, newState.iconTintColor)
        )
        
        // Update badge
        updateBadge(newState, previousState)
        
        // Handle animations
        handleAnimations(newState, previousState)
        
        // Update content description for accessibility
        iconView.contentDescription = "AIMI Auditor: ${newState.statusMessage}"
    }
    
    /**
     * Update badge text and visibility
     */
    private fun updateBadge(newState: AuditorUIState, previousState: AuditorUIState?) {
        badgeView.text = newState.badgeText
        
        // Update badge background color
        if (newState.badgeVisible) {
            val badgeColor = ContextCompat.getColor(context, newState.badgeBackgroundColor)
            badgeView.background?.setTint(badgeColor)
        }
        
        // Show/hide badge with animation if state changed
        if (newState.badgeVisible != previousState?.badgeVisible) {
            if (newState.badgeVisible) {
                // Show badge with slide-in animation
                badgeView.isVisible = true
                val slideIn = AnimationUtils.loadAnimation(context, R.anim.auditor_badge_slide_in)
                badgeView.startAnimation(slideIn)
            } else {
                // Hide badge with fade out
                badgeView.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        badgeView.isVisible = false
                        badgeView.alpha = 1f
                    }
                    .start()
            }
        } else if (newState.badgeVisible) {
            // Badge stays visible, just update text (no animation)
            badgeView.isVisible = true
        }
    }
    
    /**
     * Handle icon animations based on state transitions
     */
    private fun handleAnimations(newState: AuditorUIState, previousState: AuditorUIState?) {
        // Stop any current animation
        if (isAnimating) {
            iconView.clearAnimation()
            isAnimating = false
        }
        
        // Start new animation if needed
        when {
            // PROCESSING → Pulse animation (repeating)
            newState.shouldAnimate && newState.type == AuditorUIState.StateType.PROCESSING -> {
                val pulse = AnimationUtils.loadAnimation(context, R.anim.auditor_pulse)
                pulse.repeatCount = -1  // Infinite
                iconView.startAnimation(pulse)
                isAnimating = true
            }
            
            // Transition to READY → Bounce once
            newState.type == AuditorUIState.StateType.READY &&
            previousState?.type != AuditorUIState.StateType.READY -> {
                val bounce = AnimationUtils.loadAnimation(context, R.anim.auditor_bounce)
                iconView.startAnimation(bounce)
                isAnimating = true
                
                // Stop animating flag after animation completes
                iconView.postDelayed({
                    isAnimating = false
                }, 700)  // Bounce duration
            }
        }
    }
    
    /**
     * Get current state
     */
    fun getCurrentState(): AuditorUIState? = currentState
    
    /**
     * Force stop all animations
     */
    fun stopAnimations() {
        iconView.clearAnimation()
        badgeView.clearAnimation()
        isAnimating = false
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }
}
