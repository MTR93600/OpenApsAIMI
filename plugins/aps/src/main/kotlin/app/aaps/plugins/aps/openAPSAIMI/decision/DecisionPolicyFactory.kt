package app.aaps.plugins.aps.openAPSAIMI.decision

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext

/**
 * Factory and orchestrator for Decision Policies.
 * Manages policy lifecycle and execution order.
 */
object DecisionPolicyFactory {
    
    private val policies = mutableListOf<DecisionPolicy>()
    
    init {
        // Register default policies
        register(SafetyDecisionPolicy())
        register(BolusDecisionPolicy())
        register(TBRDecisionPolicy())
    }
    
    @JvmStatic
    fun register(policy: DecisionPolicy) {
        policies.add(policy)
        policies.sortByDescending { it.priority }
    }
    
    @JvmStatic
    fun execute(context: LoopContext): DecisionResult {
        for (policy in policies) {
            val result = policy.applyDecision(context)
            if (result is DecisionResult.Applied) {
                return result
            }
        }
        return DecisionResult.Fallthrough("No policy applied a definitive action")
    }
    
    @JvmStatic
    fun clear() {
        policies.clear()
    }
}
