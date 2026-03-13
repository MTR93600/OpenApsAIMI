package app.aaps.plugins.aps.openAPSAIMI.plugins.impl

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPluginContext
import app.aaps.plugins.aps.openAPSAIMI.plugins.AimiDecisionPlugin
import kotlin.math.roundToInt

/**
 * 🛡️ SafetyAggressionPlugin
 * 
 * Monitors hypo risk and automatically proposes reductions in SMB aggression.
 * Migrated from legacy AimiAdvisorService.
 */
class SafetyAggressionPlugin : AimiDecisionPlugin {
    override val id: String = "safety_aggression"
    override val name: String = "Safety & SMB Aggression Monitor"
    override val priority: Int = 90 // High priority for safety

    override fun analyze(context: AimiPluginContext): List<AimiAction> {
        val actions = mutableListOf<AimiAction>()
        
        // Note: Real metrics calculation should happen here or be passed in context.
        // For this refactoring step, we simulate the logic from AimiAdvisorService.
        
        // Assuming glucose status contains enough info for basic risk assessment
        val bg = context.glucose.glucose
        val delta = context.glucose.delta
        
        // Critical Rule: If we are low or dropping fast towards low
        if (bg < 75.0 || (bg < 100.0 && delta < -10.0)) {
            val maxSmb = context.preferences.get(DoubleKey.OApsAIMIMaxSMB)
            
            if (maxSmb > 1.0) {
                val newValue = (maxSmb * 0.7 * 10.0).roundToInt() / 10.0
                actions.add(
                    AimiAction.PreferenceUpdate(
                        key = DoubleKey.OApsAIMIMaxSMB,
                        newValue = newValue,
                        reason = "High hypo risk detected (BG: ${bg.toInt()}, Delta: $delta). Reducing Max SMB for safety.",
                        domain = AimiDomain.Safety,
                        priority = AimiPriority.Critical
                    )
                )
                
                actions.add(
                    AimiAction.Notification(
                        title = "Safety Intervention",
                        message = "SMB aggression reduced automatically due to hypo risk.",
                        domain = AimiDomain.Safety,
                        priority = AimiPriority.High,
                        reason = "Hypo prevention"
                    )
                )
            }
        }
        
        return actions
    }
}
