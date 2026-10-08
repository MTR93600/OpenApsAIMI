package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * AIMI Auditor UI State
 *
 * Represents the visual state of the Auditor status indicator in toolbar
 * Transformed from AuditorStatusTracker.Status for UI consumption
 *
 * This state carries no colour. [type] is the classification, and the UI layer picks the colour
 * from it: `AuditorStatusBadgeSource` maps [type] onto
 * [app.aaps.core.interfaces.overview.PluginStatusLevel], and the Overview chip gives each level
 * its own theme colour pair.
 */
data class AuditorUIState(
    val type: StateType,
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
        // 1. Textual Integrity
        if (badgeVisible) {
            require(badgeText.isNotEmpty()) { "badgeText cannot be empty when badgeVisible is true" }
        }

        // 2. Logic Consistency & Constraints
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

        // 3. Temporal Integrity
        require(timestampMs > 0) { "timestampMs must be valid" }

        ValidatedAuditorUIState(this)
    }

    /**
     * UI State Types matching visual design
     */
    enum class StateType {
        IDLE,           // No badge
        PROCESSING,     // Animated dots badge
        READY,          // Count badge
        WARNING,        // "!" badge
        ERROR           // "×" badge
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
    fun getAgeMs(): Long = aimiWallClockMs() - timestampMs

    companion object {

        /**
         * Create IDLE state (default)
         */
        @JvmStatic
        fun idle(): AuditorUIState = AuditorUIState(
            type = StateType.IDLE,
            badgeText = "",
            badgeVisible = false,
            shouldAnimate = false,
            shouldNotify = false,
            insightCount = 0,
            statusMessage = "Auditor idle",
            timestampMs = aimiWallClockMs()
        )

        /**
         * Create PROCESSING state
         */
        @JvmStatic
        fun processing(): AuditorUIState = AuditorUIState(
            type = StateType.PROCESSING,
            badgeText = "...",
            badgeVisible = true,
            shouldAnimate = true,  // Pulse animation
            shouldNotify = false,
            insightCount = 0,
            statusMessage = "Analyzing...",
            timestampMs = aimiWallClockMs()
        )

        /**
         * Create READY state with insights
         */
        @JvmStatic
        @JvmOverloads
        fun ready(insightCount: Int, shouldNotify: Boolean = true): AuditorUIState = AuditorUIState(
            type = StateType.READY,
            badgeText = insightCount.toString(),
            badgeVisible = insightCount > 0,
            shouldAnimate = false,
            shouldNotify = shouldNotify && insightCount > 0,
            insightCount = insightCount,
            statusMessage = "$insightCount insight${if (insightCount != 1) "s" else ""} available",
            timestampMs = aimiWallClockMs()
        )

        /**
         * Create WARNING state
         */
        @JvmStatic
        @JvmOverloads
        fun warning(message: String = "Warning", shouldNotify: Boolean = true): AuditorUIState = AuditorUIState(
            type = StateType.WARNING,
            badgeText = "!",
            badgeVisible = true,
            shouldAnimate = false,
            shouldNotify = shouldNotify,
            insightCount = 1,
            statusMessage = message,
            timestampMs = aimiWallClockMs()
        )

        /**
         * Create ERROR state
         */
        @JvmStatic
        @JvmOverloads
        fun error(message: String = "Error"): AuditorUIState = AuditorUIState(
            type = StateType.ERROR,
            badgeText = "×",
            badgeVisible = true,
            shouldAnimate = false,
            shouldNotify = false,
            insightCount = 0,
            statusMessage = message,
            timestampMs = aimiWallClockMs()
        )
    }
}
