package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.model.AdvisorSeverity
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Robust manager for Auditor UI state transitions.
 * Enforces safety rules, logs history, and provides rollback capabilities.
 *
 * NOTE: the original Android version used `android.util.Log` and `ConcurrentLinkedQueue`,
 * neither of which exists in commonMain. The bounded log is kept lock-free and thread-safe
 * via a copy-on-write [AtomicReference] list (drop-oldest, oldest-first order preserved).
 */
@OptIn(ExperimentalAtomicApi::class)
object StateTransitionManager {

    private const val MAX_LOG_SIZE = 50
    private val transitionLog = AtomicReference<List<TransitionRecord>>(emptyList())

    data class TransitionRecord(
        val from: AuditorUIState.StateType,
        val to: AuditorUIState.StateType,
        val severity: AdvisorSeverity,
        val timestampMs: Long = aimiWallClockMs(),
        val context: String
    )

    /**
     * Applies a transition based on new severity and validates the outcome.
     */
    fun applyTransition(
        current: AuditorUIState,
        newSeverity: AdvisorSeverity,
        context: String = "Automated System Update"
    ): AuditorUIState {
        val nextState = when (newSeverity) {
            is AdvisorSeverity.Good -> {
                 // Transition to READY or IDLE based on insights
                 if (current.insightCount > 0) AuditorUIState.ready(current.insightCount)
                 else AuditorUIState.idle()
            }
            is AdvisorSeverity.Warning -> AuditorUIState.warning(current.statusMessage)
            is AdvisorSeverity.Critical -> AuditorUIState.error(current.statusMessage)
        }

        return if (validateTransition(current, nextState)) {
            recordTransition(current.type, nextState.type, newSeverity, context)
            nextState
        } else {
            // Illegal transition rejected (android.util.Log unavailable in commonMain)
            current // Rollback: maintain current state
        }
    }

    /**
     * Validates if a transition is logically and clinically safe.
     */
    fun validateTransition(current: AuditorUIState, next: AuditorUIState): Boolean {
        // Enforce basic state machine rules
        return when (current.type) {
            AuditorUIState.StateType.IDLE -> true // Anything is better than idle
            AuditorUIState.StateType.PROCESSING -> true // Processing should resolve to something
            AuditorUIState.StateType.READY -> true // From ready we can go anywhere
            AuditorUIState.StateType.WARNING -> {
                // Warning cannot jump to IDLE without being READY first (for example)
                // This is a business rule: we must clear warnings through a 'READY' observation
                next.type != AuditorUIState.StateType.IDLE
            }
            AuditorUIState.StateType.ERROR -> {
                // Errors are terminal until re-validated through processing
                next.type == AuditorUIState.StateType.PROCESSING || next.type == AuditorUIState.StateType.ERROR
            }
        }
    }

    private fun recordTransition(from: AuditorUIState.StateType, to: AuditorUIState.StateType, severity: AdvisorSeverity, context: String) {
        val record = TransitionRecord(from, to, severity, context = context)
        while (true) {
            val current = transitionLog.load()
            val next = (current + record).let { if (it.size > MAX_LOG_SIZE) it.takeLast(MAX_LOG_SIZE) else it }
            if (transitionLog.compareAndSet(current, next)) break
        }
    }

    /**
     * Returns a copy of the recent transition history.
     */
    fun getHistory(): List<TransitionRecord> = transitionLog.load()
}
