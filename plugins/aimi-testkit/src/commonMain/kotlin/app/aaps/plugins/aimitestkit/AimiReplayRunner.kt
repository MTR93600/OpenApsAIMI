package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimiengine.AimiEngine

/**
 * One replayed tick: what the engine produced now ([actual]) next to what was
 * recorded ([expected]).
 */
data class AimiReplayOutcome(
    val tickId: Long,
    val expected: AimiTickResult,
    val actual: AimiTickResult,
)

/**
 * Replays [captures] in order through [engine]. Sequential and deterministic:
 * no coroutines, no shared mutable state.
 *
 * State chaining: the first tick runs with `captures[0].state`. Every later tick
 * runs with the `nextState` produced by the previous tick, NOT with the recorded
 * capture state. This is what makes the replay executable rather than analytic:
 * a poisoned recorded state cannot contaminate a pure engine, because the engine
 * only ever sees states it produced itself.
 *
 * An empty capture list returns an empty outcome list.
 */
fun replay(engine: AimiEngine, captures: List<AimiTickCapture>): List<AimiReplayOutcome> {
    val outcomes = ArrayList<AimiReplayOutcome>(captures.size)
    var state = captures.firstOrNull()?.state ?: return outcomes
    for (capture in captures) {
        val actual = engine.evaluate(capture.input, state, capture.models)
        outcomes += AimiReplayOutcome(
            tickId = capture.input.meta.tickId,
            expected = capture.expected,
            actual = actual,
        )
        state = actual.nextState
    }
    return outcomes
}
