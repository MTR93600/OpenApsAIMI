package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorSeverity
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.StateTransitionManager
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.processDecision
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuditorStateValidationTest {

    @Test
    fun `test valid idle state`() {
        val state = AuditorUIState.idle()
        assertTrue(state.validate().isSuccess)
    }

    @Test
    fun `test invalid resource ids`() {
        val state = AuditorUIState.idle().copy(iconTintColor = 0)
        assertTrue(state.validate().isFailure)
        assertEquals("iconTintColor must be a valid @ColorRes (got 0)", state.validate().exceptionOrNull()?.message)
    }

    @Test
    fun `test badge text requirement`() {
        val state = AuditorUIState.warning().copy(badgeText = "")
        assertTrue(state.validate().isFailure)
        assertEquals("badgeText cannot be empty when badgeVisible is true", state.validate().exceptionOrNull()?.message)
    }

    @Test
    fun `test processing state must be animated`() {
        val state = AuditorUIState.processing().copy(shouldAnimate = false)
        assertTrue(state.validate().isFailure)
    }

    @Test
    fun `test allowed transition Good to Warning`() {
        val current = AuditorUIState.idle()
        val next = StateTransitionManager.applyTransition(current, AdvisorSeverity.Warning)
        assertEquals(AuditorUIState.StateType.WARNING, next.type)
    }

    @Test
    fun `test blocked transition Error to Idle`() {
        val current = AuditorUIState.error()
        // Error can only go to PROCESSING or ERROR
        val next = StateTransitionManager.applyTransition(current, AdvisorSeverity.Good)
        assertEquals(AuditorUIState.StateType.ERROR, next.type) // Rollback to current
    }

    @Test
    fun `test exhaustive decision processing`() {
        // This test ensures that the global function works for all types
        // (If a new type is added and not handled in when, this won't compile)
        processDecision(DecisionResult.Applied(source = "Test", reason = "Testing"))
        processDecision(DecisionResult.Cancelled(source = "Test", reason = "User aborted"))
        processDecision(DecisionResult.Skipped(source = "Test", reason = "Redundant"))
        processDecision(DecisionResult.Fallthrough(reason = "No AI action"))
    }
}
