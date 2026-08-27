package app.aaps.plugins.aimiengine

import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiTickResult

/**
 * Stateful AIMI tick. One call, no I/O.
 *
 * ⚠️ ASYNC IMPACT: this API is synchronous. Do not add `suspend`, callbacks or a coroutine
 * scope here. Background TDD/TIR/Auditor work stays in the Android/iOS shell, outside evaluate.
 */
interface AimiEngine {
    fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult
}
