package app.aaps.plugins.aps.openAPSAIMI.decision

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext

/**
 * Interface for decision policies.
 * Enables modular implementation of insulin and carb logic.
 */
interface DecisionPolicy {
    val priority: Int
    val name: String
    
    fun applyDecision(context: LoopContext): DecisionResult
}

/**
 * 🛡️ Safety-First Policy
 * High priority: can block all other actions if unsafe conditions are met.
 */
class SafetyDecisionPolicy : DecisionPolicy {
    override val priority: Int = 100
    override val name: String = "SafetyShield"

    override fun applyDecision(context: LoopContext): DecisionResult {
        if (context.bg.mgdl < context.profile.lgsThreshold) {
            return DecisionResult.Applied(
                source = name,
                tbrUph = 0.0,
                tbrMin = 30,
                reason = "LGS: BG below threshold"
            )
        }
        return DecisionResult.Fallthrough("Safe to proceed")
    }
}

/**
 * 💉 Bolus (SMB) Policy
 */
class BolusDecisionPolicy : DecisionPolicy {
    override val priority: Int = 50
    override val name: String = "BolusAdvisor"

    override fun applyDecision(context: LoopContext): DecisionResult {
        // Logic for SMB calculation would go here
        return DecisionResult.Fallthrough("Bolus not required")
    }
}

/**
 * 📉 Temporary Basal Rate Policy
 */
class TBRDecisionPolicy : DecisionPolicy {
    override val priority: Int = 30
    override val name: String = "TBRAdvisor"

    override fun applyDecision(context: LoopContext): DecisionResult {
        // Logic for TBR calculation would go here
        return DecisionResult.Fallthrough("Standard basal maintained")
    }
}
