package app.aaps.plugins.aps.openAPSAIMI.context.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextLLMClient
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ContextPreset
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes

/**
 * ViewModel for Context Activity.
 * 
 * Manages context intents, LLM parsing, and medical context building.
 * 
 * Note: Manual injection for now (simpler than Hilt for this feature)
 */
class ContextViewModel(
    private val contextManager: ContextManager,
    private val sp: SP,
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction
) : ViewModel() {
    
    private val _activeIntents = MutableLiveData<List<Pair<String, ContextIntent>>>()
    val activeIntents: LiveData<List<Pair<String, ContextIntent>>> = _activeIntents
    
    private val _parseStatus = MutableLiveData<ParseStatus>()
    val parseStatus: LiveData<ParseStatus> = _parseStatus
    
    private val _contextEnabled = MutableLiveData<Boolean>()
    val contextEnabled: LiveData<Boolean> = _contextEnabled
    
    private val _llmEnabled = MutableLiveData<Boolean>()
    val llmEnabled: LiveData<Boolean> = _llmEnabled
    
    init {
        refreshSettings()
        refreshActiveIntents()
    }
    
    /**
     * Parse user text and add intent.
     * 
     * Uses LLM if enabled, otherwise offline parser.
     */
    fun parseAndAddIntent(userText: String) {
        viewModelScope.launch {
            _parseStatus.value = ParseStatus.Parsing
            
            try {
                aapsLogger.info(LTag.APS, "[ContextViewModel] Parsing: '$userText'")
                
                // Add intent via manager (handles LLM/offline automatically)
                val ids = contextManager.addIntent(userText)
                
                if (ids.isNotEmpty()) {
                    _parseStatus.value = ParseStatus.Success(ids.size)
                    aapsLogger.info(LTag.APS, "[ContextViewModel] Added ${ids.size} intent(s)")
                } else {
                    _parseStatus.value = ParseStatus.Error("No intents parsed. Try being more specific.")
                    aapsLogger.warn(LTag.APS, "[ContextViewModel] No intents parsed from: '$userText'")
                }
                
                refreshActiveIntents()
                
            } catch (e: Exception) {
                _parseStatus.value = ParseStatus.Error(e.message ?: "Unknown error")
                aapsLogger.error(LTag.APS, "[ContextViewModel] Parse error", e)
            }
        }
    }
    
    /**
     * Add preset intent (from button).
     */
    fun addPreset(
        preset: ContextPreset,
        customDuration: kotlin.time.Duration? = null,
        customIntensity: ContextIntent.Intensity? = null
    ) {
        viewModelScope.launch {
            try {
                val id = contextManager.addPreset(preset, customDuration, customIntensity)
                aapsLogger.info(LTag.APS, "[ContextViewModel] Added preset: ${preset.displayName}")
                
                _parseStatus.value = ParseStatus.Success(1)
                refreshActiveIntents()
                
            } catch (e: Exception) {
                _parseStatus.value = ParseStatus.Error(e.message ?: "Unknown error")
                aapsLogger.error(LTag.APS, "[ContextViewModel] Preset error", e)
            }
        }
    }
    
    /**
     * Refresh active intents list.
     */
    fun refreshActiveIntents() {
        val allIntents = contextManager.getAllIntents()
        _activeIntents.value = allIntents.toList()
        
        aapsLogger.debug(LTag.APS, "[ContextViewModel] Refreshed: ${allIntents.size} active intent(s)")
    }
    
    /**
     * Remove intent by ID.
     */
    fun removeIntent(id: String) {
        contextManager.removeIntent(id)
        refreshActiveIntents()
    }
    
    /**
     * Extend intent duration.
     */
    fun extendIntent(id: String, additionalMinutes: Int) {
        contextManager.extendDuration(id, additionalMinutes.minutes)
        refreshActiveIntents()
    }
    
    /**
     * Clear all intents.
     */
    fun clearAll() {
        contextManager.clearAll()
        refreshActiveIntents()
    }
    
    /**
     * Refresh settings from preferences.
     */
    fun refreshSettings() {
        _contextEnabled.value = sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled.key, false)
        _llmEnabled.value = sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, false)
    }
    
    /**
     * Toggle context module on/off.
     */
    fun toggleContextEnabled(enabled: Boolean) {
        sp.putBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled.key, enabled)
        _contextEnabled.value = enabled
        aapsLogger.info(LTag.APS, "[ContextViewModel] Context module ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Toggle LLM parsing on/off.
     */
    fun toggleLLMEnabled(enabled: Boolean) {
        sp.putBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, enabled)
        _llmEnabled.value = enabled
        aapsLogger.info(LTag.APS, "[ContextViewModel] LLM parsing ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Get formatted time remaining for intent.
     */
    fun getTimeRemaining(intent: ContextIntent): String {
        val now = System.currentTimeMillis()
        val remaining = (intent.endTimeMs - now) / 1000 / 60 // minutes
        
        return when {
            remaining <= 0 -> "Expired"
            remaining < 60 -> "${remaining}min left"
            remaining < 1440 -> "${remaining / 60}h ${remaining % 60}min left"
            else -> "${remaining / 1440}d left"
        }
    }
    
    /**
     * Get display string for intent.
     */
    fun getIntentDisplayString(intent: ContextIntent): String {
        return when (intent) {
            is ContextIntent.Activity -> "🏃 Activity: ${intent.activityType.name} ${intent.intensity.name}"
            is ContextIntent.Illness -> "🤒 Illness: ${intent.symptomType.name} ${intent.intensity.name}"
            is ContextIntent.Stress -> "😰 Stress: ${intent.stressType.name} ${intent.intensity.name}"
            is ContextIntent.UnannouncedMealRisk -> "🍕 Meal Risk: ${intent.intensity.name}"
            is ContextIntent.Alcohol -> "🍷 Alcohol: ${intent.units}U ${intent.intensity.name}"
            is ContextIntent.Travel -> "✈️ Travel: ${intent.intensity.name}"
            is ContextIntent.MenstrualCycle -> "🔄 Cycle: ${intent.phase.name}"
            is ContextIntent.Custom -> "📝 ${intent.description}"
        }
    }
}

/**
 * Parse status for UI feedback.
 */
sealed class ParseStatus {
    object Idle : ParseStatus()
    object Parsing : ParseStatus()
    data class Success(val intentCount: Int) : ParseStatus()
    data class Error(val message: String) : ParseStatus()
}
