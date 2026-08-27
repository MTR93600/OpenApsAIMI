package app.aaps.plugins.aps.openAPSAIMI.decision

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext

object DecisionPolicyFactory {

    private val policies = mutableListOf<DecisionPolicy>()

    init {
        register(SafetyDecisionPolicy())
        register(BolusDecisionPolicy())
        register(TBRDecisionPolicy())
    }

    fun register(policy: DecisionPolicy) {
        policies.add(policy)
        policies.sortByDescending { it.priority }
    }

    fun execute(context: LoopContext): DecisionResult {
        for (policy in policies) {
            val result = policy.applyDecision(context)
            if (result is DecisionResult.Applied) {
                return result
            }
        }
        return DecisionResult.Fallthrough("No policy applied a definitive action")
    }

    fun clear() {
        policies.clear()
    }
}
