package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThyroidStateEstimatorTest {

    @Test
    fun `test disabled overrides all to euthyroid`() {
        val estimator = ThyroidStateEstimator()
        
        // Setup a state that would normally be hyper
        val inputs = ThyroidInputs(
            isEnabled = false,
            userMode = ThyroidEstimationMode.MANUAL,
            manualStatus = ThyroidStatus.HYPER_SEVERE
        )
        
        estimator.updateState(inputs)
        assertEquals(ThyroidStatus.EUTHYROID, estimator.currentState.value)
        assertEquals(1.0, estimator.confidence.value, 0.01)
    }

    @Test
    fun `test titration phase forces normalizing state`() {
        val estimator = ThyroidStateEstimator()
        
        val inputs = ThyroidInputs(
            isEnabled = true,
            userMode = ThyroidEstimationMode.MANUAL,
            manualStatus = ThyroidStatus.HYPER_MODERATE, // This should be ignored
            treatmentPhase = ThyroidTreatmentPhase.TITRATION
        )
        
        estimator.updateState(inputs)
        assertEquals(ThyroidStatus.NORMALIZING, estimator.currentState.value)
        assertEquals(1.0, estimator.confidence.value, 0.01)
    }

    @Test
    fun `test de-escalation phase forces normalizing with lower confidence`() {
        val estimator = ThyroidStateEstimator()
        
        val inputs = ThyroidInputs(
            isEnabled = true,
            userMode = ThyroidEstimationMode.MANUAL,
            treatmentPhase = ThyroidTreatmentPhase.DE_ESCALATION
        )
        
        estimator.updateState(inputs)
        assertEquals(ThyroidStatus.NORMALIZING, estimator.currentState.value)
        assertEquals(0.8, estimator.confidence.value, 0.01)
    }

    @Test
    fun `test manual mode respects manual status when no active treatment`() {
        val estimator = ThyroidStateEstimator()
        
        val inputs = ThyroidInputs(
            isEnabled = true,
            userMode = ThyroidEstimationMode.MANUAL,
            manualStatus = ThyroidStatus.HYPER_MILD,
            treatmentPhase = ThyroidTreatmentPhase.STABLE
        )
        
        estimator.updateState(inputs)
        assertEquals(ThyroidStatus.HYPER_MILD, estimator.currentState.value)
        assertEquals(1.0, estimator.confidence.value, 0.01)
    }

    @Test
    fun `test auto mode returns unknown currently`() {
        val estimator = ThyroidStateEstimator()
        
        val inputs = ThyroidInputs(
            isEnabled = true,
            userMode = ThyroidEstimationMode.AUTO
        )
        
        estimator.updateState(inputs)
        // Until implemented, Auto should fallback to UNKNOWN with 0.0 confidence
        assertEquals(ThyroidStatus.UNKNOWN, estimator.currentState.value)
        assertEquals(0.0, estimator.confidence.value, 0.01)
    }

}
