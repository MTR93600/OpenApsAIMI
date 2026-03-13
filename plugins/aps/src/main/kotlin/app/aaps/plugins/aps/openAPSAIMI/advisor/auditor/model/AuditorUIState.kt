package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import app.aaps.core.ui.R as CoreR

/**
 * AIMI Auditor UI State
 * 
 * Represents the visual state of the Auditor status indicator in toolbar
 * Transformed from AuditorStatusTracker.Status for UI consumption
 */
data class AuditorUIState(
    val type: StateType,
    @ColorRes val iconTintColor: Int,
    @ColorRes val badgeBackgroundColor: Int,
    val badgeText: String,
    val badgeVisible: Boolean,
    val shouldAnimate: Boolean,
    val shouldNotify: Boolean,
    val insightCount: Int,
    val statusMessage: String,
    val timestampMs: Long
) {
    /**
     * Type-safe wrapper for a successfully validated AuditorUIState.
     */
    @JvmInline
    value class ValidatedAuditorUIState(val state: AuditorUIState)

    /**
     * Performs exhaustive validation of the UI state.
     * @return Result containing the validated state or an exception with details.
     */
    fun validate(): Result<ValidatedAuditorUIState> = runCatching {
        // 1. Resource Validations
        require(iconTintColor != 0) { "iconTintColor must be a valid @ColorRes (got 0)" }
        require(badgeBackgroundColor != 0) { "badgeBackgroundColor must be a valid @ColorRes (got 0)" }

        // 2. Textual Integrity
        if (badgeVisible) {
            require(badgeText.isNotEmpty()) { "badgeText cannot be empty when badgeVisible is true" }
        }

        // 3. Logic Consistency & Constraints
        when (type) {
            StateType.IDLE -> {
                require(!badgeVisible) { "IDLE state cannot have a visible badge" }
                require(!shouldAnimate) { "IDLE state should not animate" }
            }
            StateType.PROCESSING -> {
                require(shouldAnimate) { "PROCESSING state must be animated" }
                require(badgeVisible) { "PROCESSING state must have a visible badge (dots)" }
            }
            StateType.READY -> {
                require(insightCount >= 0) { "insightCount cannot be negative" }
                if (insightCount > 0) {
                    require(badgeVisible) { "READY state with insights must show a badge" }
                }
            }
            StateType.WARNING, StateType.ERROR -> {
                require(badgeVisible) { "${type.name} state must always show a badge icon" }
            }
        }

        // 4. Temporal Integrity
        require(timestampMs > 0) { "timestampMs must be valid" }

        ValidatedAuditorUIState(this)
    }
    
    /**
     * UI State Types matching visual design
     */
    enum class StateType {
        IDLE,           // Grey, no badge
        PROCESSING,     // Blue, animated dots badge
        READY,          // Green, count badge
        WARNING,        // Orange, "!" badge
        ERROR           // Red, "×" badge
    }
    
    /**
     * Check if state is active (has insights)
     */
    fun isActive(): Boolean = type == StateType.READY || type == StateType.WARNING
    
    /**
     * Check if state requires user attention
     */
    fun requiresAttention(): Boolean = type == StateType.WARNING || type == StateType.ERROR
    
    /**
     * Get age in milliseconds
     */
    fun getAgeMs(): Long = System.currentTimeMillis() - timestampMs
    
    companion object {
        
        /**
         * Create IDLE state (default)
         */
        @JvmStatic
        fun idle(): AuditorUIState = AuditorUIState(
            type = StateType.IDLE,
            iconTintColor = CoreR.color.deviationGrey,
            badgeBackgroundColor = CoreR.color.deviationGrey,
            badgeText = "",
            badgeVisible = false,
            shouldAnimate = false,
            shouldNotify = false,
            insightCount = 0,
            statusMessage = "Auditor idle",
            timestampMs = System.currentTimeMillis()
        )
        
        /**
         * Create PROCESSING state
         */
        @JvmStatic
        fun processing(): AuditorUIState = AuditorUIState(
            type = StateType.PROCESSING,
            iconTintColor = CoreR.color.examinedProfile,  // Blue
            badgeBackgroundColor = CoreR.color.examinedProfile,
            badgeText = "...",
            badgeVisible = true,
            shouldAnimate = true,  // Pulse animation
            shouldNotify = false,
            insightCount = 0,
            statusMessage = "Analyzing...",
            timestampMs = System.currentTimeMillis()
        )
        
        /**
         * Create READY state with insights
         */
        @JvmStatic
        @JvmOverloads
        fun ready(insightCount: Int, shouldNotify: Boolean = true): AuditorUIState = AuditorUIState(
            type = StateType.READY,
            iconTintColor = app.aaps.core.ui.R.color.inRange,  // Green
            badgeBackgroundColor = CoreR.color.high,  // Red badge for visibility
            badgeText = insightCount.toString(),
            badgeVisible = insightCount > 0,
            shouldAnimate = false,
            shouldNotify = shouldNotify && insightCount > 0,
            insightCount = insightCount,
            statusMessage = "$insightCount insight${if (insightCount != 1) "s" else ""} available",
            timestampMs = System.currentTimeMillis()
        )
        
        /**
         * Create WARNING state
         */
        @JvmStatic
        @JvmOverloads
        fun warning(message: String = "Warning"): AuditorUIState = AuditorUIState(
            type = StateType.WARNING,
            iconTintColor = CoreR.color.warning,  // Orange
            badgeBackgroundColor = CoreR.color.warning,
            badgeText = "!",
            badgeVisible = true,
            shouldAnimate = false,
            shouldNotify = true,
            insightCount = 1,
            statusMessage = message,
            timestampMs = System.currentTimeMillis()
        )
        
        /**
         * Create ERROR state
         */
        @JvmStatic
        @JvmOverloads
        fun error(message: String = "Error"): AuditorUIState = AuditorUIState(
            type = StateType.ERROR,
            iconTintColor = CoreR.color.high,  // Red
            badgeBackgroundColor = CoreR.color.high,
            badgeText = "×",
            badgeVisible = true,
            shouldAnimate = false,
            shouldNotify = false,
            insightCount = 0,
            statusMessage = message,
            timestampMs = System.currentTimeMillis()
        )
    }
}
