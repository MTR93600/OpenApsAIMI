package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimiengine.AimiEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure fake engine: the output depends only on (input, state, models).
 * It doses a small SMB derived from the tick id and the state generation,
 * and bumps the generation by one.
 */
private class FakePureEngine : AimiEngine {
    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        val smbU = input.meta.tickId.toDouble() * 0.1 + state.generation.toDouble() * 0.01
        return AimiTickResult(
            command = AimiTherapyCommand.Smb(smbU),
            nextState = state.copy(generation = state.generation + 1),
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace(reasonCode = "TEST"),
            safety = AimiSafetyReport(holdReasonCode = null),
        )
    }
}

private fun resultWith(
    command: AimiTherapyCommand,
    generation: Long,
    pairedCommand: AimiTherapyCommand? = null,
    reasonCode: String = "TEST",
    holdReasonCode: String? = null,
): AimiTickResult =
    AimiTickResult(
        command = command,
        nextState = AimiEngineState(schemaVersion = 1, generation = generation),
        trainingEvents = emptyList(),
        persistenceEvents = emptyList(),
        telemetry = AimiDecisionTrace(reasonCode = reasonCode),
        safety = AimiSafetyReport(holdReasonCode = holdReasonCode),
        pairedCommand = pairedCommand,
    )

private fun captureOf(tickId: Long, state: AimiEngineState, expected: AimiTickResult): AimiTickCapture =
    AimiTickCapture(
        input = AimiTestSnapshots.emptyInput(tickId = tickId),
        state = state,
        models = AimiTestSnapshots.emptyModels(),
        expected = expected,
    )

class AimiReplayTest {

    @Test
    fun replay_chains_state_through_next_state() {
        val engine = FakePureEngine()
        // Expected results assume chaining: tick 2 sees generation 1, produced by tick 1.
        val captures = listOf(
            captureOf(1L, AimiTestSnapshots.emptyState(0L), resultWith(AimiTherapyCommand.Smb(0.1), 1L)),
            captureOf(2L, AimiTestSnapshots.emptyState(0L), resultWith(AimiTherapyCommand.Smb(0.21), 2L)),
        )

        val outcomes = replay(engine, captures)

        assertEquals(2, outcomes.size)
        assertEquals(1L, outcomes[0].tickId)
        assertEquals(2L, outcomes[1].tickId)
        // Tick 2 ran with the state tick 1 produced (generation 1 -> SMB 0.2 + 0.01).
        assertEquals(1L, outcomes[0].actual.nextState.generation)
        assertEquals(2L, outcomes[1].actual.nextState.generation)
        val tick2Smb = outcomes[1].actual.command as AimiTherapyCommand.Smb
        assertEquals(0.21, tick2Smb.insulinU, 1e-12)
        assertTrue(compare(outcomes[0].expected, outcomes[0].actual).passed)
        assertTrue(compare(outcomes[1].expected, outcomes[1].actual).passed)
    }

    @Test
    fun comparator_detects_command_value_difference() {
        val expected = resultWith(AimiTherapyCommand.Smb(0.5), 1L)
        val actual = resultWith(AimiTherapyCommand.Smb(0.6), 1L)

        val diff = compare(expected, actual)

        assertFalse(diff.passed)
        assertEquals(1, diff.diffs.size)
        assertEquals("command.insulinU", diff.diffs[0].field)
        assertEquals("0.5", diff.diffs[0].expected)
        assertEquals("0.6", diff.diffs[0].actual)
    }

    @Test
    fun comparator_tolerates_epsilon() {
        val expected = resultWith(AimiTherapyCommand.Smb(0.5), 1L)

        assertTrue(compare(expected, resultWith(AimiTherapyCommand.Smb(0.5 + 1e-10), 1L)).passed)
        assertFalse(compare(expected, resultWith(AimiTherapyCommand.Smb(0.5 + 1e-8), 1L)).passed)
    }

    @Test
    fun report_lists_failed_ticks_with_details() {
        val outcomes = listOf(
            AimiReplayOutcome(1L, resultWith(AimiTherapyCommand.Smb(0.5), 1L), resultWith(AimiTherapyCommand.Smb(0.5), 1L)),
            AimiReplayOutcome(7L, resultWith(AimiTherapyCommand.Smb(0.5), 1L), resultWith(AimiTherapyCommand.Smb(0.6), 1L)),
        )
        val diffs = outcomes.map { compare(it.expected, it.actual) }

        val report = renderReport(outcomes, diffs)

        assertTrue(report.contains("2 tick(s), 1 passed, 1 failed"))
        assertTrue(report.contains("[PASS] tick 1"))
        assertTrue(report.contains("[FAIL] tick 7"))
        assertTrue(report.contains("command.insulinU"))
        assertTrue(report.contains("expected=0.5 actual=0.6"))
    }

    @Test
    fun poisoned_state_does_not_contaminate_pure_engine() {
        val engine = FakePureEngine()
        // The recorded state of tick 2 is poisoned (generation 999). The runner must
        // ignore it and chain the state tick 1 actually produced (generation 1).
        val captures = listOf(
            captureOf(1L, AimiTestSnapshots.emptyState(0L), resultWith(AimiTherapyCommand.Smb(0.1), 1L)),
            captureOf(2L, AimiTestSnapshots.emptyState(999L), resultWith(AimiTherapyCommand.Smb(0.21), 2L)),
        )

        val outcomes = replay(engine, captures)
        val diffs = outcomes.map { compare(it.expected, it.actual) }

        assertTrue(diffs.all { it.passed }, "poisoned recorded state leaked into the replay")
        assertEquals(2L, outcomes[1].actual.nextState.generation)
    }

    @Test
    fun comparator_detects_generation_and_paired_command_differences() {
        val expected = resultWith(
            command = AimiTherapyCommand.Hold("low"),
            generation = 5L,
            pairedCommand = null,
        )
        val actual = resultWith(
            command = AimiTherapyCommand.Hold("low"),
            generation = 6L,
            pairedCommand = AimiTherapyCommand.Smb(0.1),
        )

        val diff = compare(expected, actual)

        assertFalse(diff.passed)
        val fields = diff.diffs.map { it.field }
        assertTrue(fields.contains("nextState.generation"))
        assertTrue(fields.contains("pairedCommand"))
    }
}
