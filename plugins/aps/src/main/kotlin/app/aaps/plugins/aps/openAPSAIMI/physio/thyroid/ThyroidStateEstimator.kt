package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThyroidStateEstimator {

    private val _currentState = MutableStateFlow(ThyroidStatus.EUTHYROID)
    val currentState: StateFlow<ThyroidStatus> = _currentState.asStateFlow()

    private val _confidence = MutableStateFlow(1.0)
    val confidence: StateFlow<Double> = _confidence.asStateFlow()
    
    // Simplistic hysteresis / mapping for now. In a full physio module,
    // this integrates EWMA on resting HR. For this baseline:
    fun updateState(inputs: ThyroidInputs) {
        if (!inputs.isEnabled) {
            _currentState.value = ThyroidStatus.EUTHYROID
            _confidence.value = 1.0
            return
        }

        when (inputs.userMode) {
            ThyroidEstimationMode.MANUAL -> {
                // If in treatment TITRATION, it overrides status to NORMALIZING
                if (inputs.treatmentPhase == ThyroidTreatmentPhase.TITRATION) {
                    _currentState.value = ThyroidStatus.NORMALIZING
                    _confidence.value = 1.0
                } else if (inputs.treatmentPhase == ThyroidTreatmentPhase.DE_ESCALATION) {
                    // Could also be normalizing or just returning to baseline
                    _currentState.value = ThyroidStatus.NORMALIZING
                    _confidence.value = 0.8
                } else {
                    _currentState.value = inputs.manualStatus
                    _confidence.value = 1.0
                }
            }
            ThyroidEstimationMode.AUTO -> {
                // Future Implementation: Merge vitals, BG patterns with EWMA limits
                _currentState.value = ThyroidStatus.UNKNOWN
                _confidence.value = 0.0
            }
        }
    }
}
