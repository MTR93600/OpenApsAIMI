package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs

/**
 * 🔄 AimiStateTransitionManager
 *
 * Manages the state transitions for the AI Auditor, ensuring clinical safety
 * and maintaining a performance-optimized log of transitions.
 */
class AimiStateTransitionManager(
    private val logger: AAPSLogger,
    initialState: AuditorUIState = AuditorUIState.Idle
) {
    private val lock = AapsLock()
    private var currentState: AuditorUIState = initialState

    /** Performance Optimized: Evicting log limited to 50 elements */
    private val transitionLog = ArrayDeque<TransitionRecord>()
    private val MAX_LOG_SIZE = 50

    data class TransitionRecord(
        val from: AuditorUIState,
        val to: AuditorUIState,
        val timestampMs: Long = aimiWallClockMs(),
        val reason: String? = null
    )

    /**
     * Attempts to transition to the [newState].
     * Returns true if successful, false if transition is blocked by business rules.
     */
    fun transitionTo(newState: AuditorUIState, reason: String? = null): Boolean = lock.withLock {
        if (!currentState.canTransitionTo(newState)) {
            logger.warn(LTag.AIMI, "🚫 [STATE] Blocked transition: ${currentState::class.simpleName} -> ${newState::class.simpleName}")
            return@withLock false
        }

        val record = TransitionRecord(currentState, newState, reason = reason)
        currentState = newState

        addToLog(record)
        logger.debug(LTag.AIMI, "🔄 [STATE] Moved to ${newState::class.simpleName} (${reason ?: "Loop Step"})")

        true
    }

    private fun addToLog(record: TransitionRecord) {
        transitionLog.addLast(record)
        while (transitionLog.size > MAX_LOG_SIZE) {
            transitionLog.removeFirst()
        }
    }

    fun getCurrentState(): AuditorUIState = currentState

    fun getLogs(): List<TransitionRecord> = transitionLog.toList()
}
