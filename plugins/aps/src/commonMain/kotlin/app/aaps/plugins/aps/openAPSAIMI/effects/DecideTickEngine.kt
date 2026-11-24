package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.takeWholeChars
import kotlin.coroutines.cancellation.CancellationException

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
    // The export only records the decision. One non-finite number in it used to throw here, after
    // the dose was decided, and the whole tick ended in a safe hold with no insulin change. So a
    // failure is written to the console errors and the decision goes out unchanged.
    try {
        calls.export(finalResult)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        finalResult.addConsoleError("AIMI export failed, decision kept: ${e::class.simpleName}: ${e.message?.takeWholeChars(200).orEmpty()}")
    }
    return finalResult
}

/** Adds [line] to the console errors of this result, creating the list when there is none. */
internal fun RT.addConsoleError(line: String) {
    val errors = consoleError ?: mutableListOf<String>().also { consoleError = it }
    errors.add(line)
}
