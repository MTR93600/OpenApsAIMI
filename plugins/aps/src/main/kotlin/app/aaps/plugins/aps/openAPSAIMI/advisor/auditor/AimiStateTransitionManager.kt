package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import java.util.concurrent.ConcurrentLinkedQueue
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

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
    private var currentState: AuditorUIState = initialState
    
    /** Performance Optimized: Evicting log limited to 50 elements */
    private val transitionLog = ConcurrentLinkedQueue<TransitionRecord>()
    private val MAX_LOG_SIZE = 50

    data class TransitionRecord(
        val from: AuditorUIState,
        val to: AuditorUIState,
        val timestampMs: Long = System.currentTimeMillis(),
        val reason: String? = null
    )

    /**
     * Attempts to transition to the [newState].
     * Returns true if successful, false if transition is blocked by business rules.
     */
    @Synchronized
    fun transitionTo(newState: AuditorUIState, reason: String? = null): Boolean {
        if (!currentState.canTransitionTo(newState)) {
            logger.warn(LTag.APS, "🚫 [STATE] Blocked transition: ${currentState::class.simpleName} -> ${newState::class.simpleName}")
            return false
        }

        val record = TransitionRecord(currentState, newState, reason = reason)
        currentState = newState
        
        addToLog(record)
        logger.debug(LTag.APS, "🔄 [STATE] Moved to ${newState::class.simpleName} (${reason ?: "Loop Step"})")
        
        return true
    }

    private fun addToLog(record: TransitionRecord) {
        transitionLog.add(record)
        while (transitionLog.size > MAX_LOG_SIZE) {
            transitionLog.poll()
        }
    }

    fun getCurrentState(): AuditorUIState = currentState

    fun getLogs(): List<TransitionRecord> = transitionLog.toList()
}
