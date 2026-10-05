package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT

/**
 * [runBasalDecisionEngineDecideStage] enters the orchestrator.
 * Learners and the medical-JSON export stay ports, called at this line.
 * `nightbis` is the flag already set by the tick clock, passed as `nightMode`.
 */
internal interface AimiTickEngineCalls<TDecision> {
    fun basalEngine(): TDecision
    fun learners(decision: TDecision): RT
    fun export(finalResult: RT)
}

internal fun <TDecision> decideDetermineBasalTickEngine(
    calls: AimiTickEngineCalls<TDecision>,
): RT {
    val decision = calls.basalEngine()
    val finalResult = calls.learners(decision)
    calls.export(finalResult)
    return finalResult
}
