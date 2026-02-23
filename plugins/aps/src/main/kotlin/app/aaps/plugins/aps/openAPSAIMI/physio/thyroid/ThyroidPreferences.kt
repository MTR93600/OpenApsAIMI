package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides access to AIMI Hyperthyroidism (Basedow) preferences and emits updates via StateFlow.
 */
class ThyroidPreferences(private val prefs: Preferences) {

    private val _inputsFlow = MutableStateFlow(getCurrentInputs())
    val inputsFlow: StateFlow<ThyroidInputs> = _inputsFlow.asStateFlow()

    fun isEnabled(): Boolean = prefs.get(BooleanKey.OApsAIMIThyroidEnabled)
    
    fun getMode(): ThyroidEstimationMode = enumValue(prefs.get(StringKey.OApsAIMIThyroidMode), ThyroidEstimationMode.MANUAL)
    
    fun getManualStatus(): ThyroidStatus = enumValue(prefs.get(StringKey.OApsAIMIThyroidManualStatus), ThyroidStatus.EUTHYROID)
    
    fun getTreatmentPhase(): ThyroidTreatmentPhase = enumValue(prefs.get(StringKey.OApsAIMIThyroidTreatmentPhase), ThyroidTreatmentPhase.NONE)
    
    fun getGuardLevel(): NormalizingGuardLevel = enumValue(prefs.get(StringKey.OApsAIMIThyroidGuardLevel), NormalizingGuardLevel.HIGH)

    fun isDebugLoggingEnabled(): Boolean = prefs.get(BooleanKey.OApsAIMIThyroidLogVerbosity)

    fun update() {
        _inputsFlow.value = getCurrentInputs()
    }

    private fun getCurrentInputs(): ThyroidInputs {
        return ThyroidInputs(
            timestampMs = System.currentTimeMillis(),
            isEnabled = isEnabled(),
            userMode = getMode(),
            manualStatus = getManualStatus(),
            treatmentPhase = getTreatmentPhase(),
            guardLevel = getGuardLevel()
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String, default: T): T {
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: Exception) {
            default
        }
    }
}
