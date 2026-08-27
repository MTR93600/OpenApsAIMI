package app.aaps.plugins.aimiengine

import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult

/**
 * Safe default until the freeze tick is extracted into this module.
 *
 * Always holds. Does not read [input] services (there are none). Does not command a pump.
 * Reason code [REASON_NOT_EXTRACTED] is stable for traces and tests.
 */
class HoldAimiEngine : AimiEngine {

    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        return AimiTickResult(
            command = AimiTherapyCommand.Hold(REASON_NOT_EXTRACTED),
            nextState = state,
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace(REASON_NOT_EXTRACTED),
            safety = AimiSafetyReport(holdReasonCode = REASON_NOT_EXTRACTED),
        )
    }

    companion object {
        const val REASON_NOT_EXTRACTED: String = "ENGINE_NOT_EXTRACTED"
    }
}
