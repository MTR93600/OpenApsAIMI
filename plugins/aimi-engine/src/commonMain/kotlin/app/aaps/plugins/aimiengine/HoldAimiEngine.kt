package app.aaps.plugins.aimiengine

import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult

/**
 * Safe default until a caller turns [AimiCommonEngineSwitch] on and supplies [commonEngine].
 *
 * The switch is off by default. Off, this returns `Hold("ENGINE_NOT_EXTRACTED")` and does not
 * call [commonEngine]. On, with a delegate that is not this instance, it returns that delegate's
 * result unchanged. It does not catch the delegate's exceptions. It does not command a pump.
 * Reason code [REASON_NOT_EXTRACTED] stays stable for the off path.
 */
class HoldAimiEngine(
    private val commonEngine: AimiEngine? = null,
) : AimiEngine {

    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        val delegate = commonEngine
        if (AimiCommonEngineSwitch.enabled && delegate != null && delegate !== this) {
            return delegate.evaluate(input, state, models)
        }
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
