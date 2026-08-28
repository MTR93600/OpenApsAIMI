package app.aaps.plugins.aps.openAPSAIMI.plugins.impl

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPluginContext
import app.aaps.plugins.aps.openAPSAIMI.plugins.AimiDecisionPlugin
import kotlin.math.roundToInt

/**
 * 📈 StableControlPlugin
 * 
 * Suggests increasing basal/lunch aggression when control is stable but suboptimal.
 */
class StableControlPlugin : AimiDecisionPlugin {
    override val id: String = "stable_control"
    override val name: String = "Stable Control Optimizer"
    override val priority: Int = 50 // Medium priority

    override fun analyze(context: AimiPluginContext): List<AimiAction> {
        val actions = mutableListOf<AimiAction>()
        
        // Simulation of AimiAdvisorService Rule 2
        val lunchFactor = context.preferences.get(DoubleKey.OApsAIMILunchFactor)
        
        // If we have headroom to increase aggression
        if (lunchFactor < 1.2) {
            val newValue = (lunchFactor + 0.1 * 10.0).roundToInt() / 10.0
            actions.add(
                AimiAction.PreferenceUpdate(
                    key = DoubleKey.OApsAIMILunchFactor,
                    newValue = newValue,
                    reason = "Stable glucose but TIR suboptimal. Increasing Lunch Factor for better post-prandial control.",
                    domain = AimiDomain.Basal,
                    priority = AimiPriority.High
                )
            )
        }
        
        return actions
    }
}
