package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiCapabilitySnapshot
import app.aaps.plugins.aimicontracts.AimiConfigSnapshot
import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiGlucoseSnapshot
import app.aaps.plugins.aimicontracts.AimiGlucoseWarmup
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiInsulinSnapshot
import app.aaps.plugins.aimicontracts.AimiMealSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiPhysiologySnapshot
import app.aaps.plugins.aimicontracts.AimiProfileSnapshot
import app.aaps.plugins.aimicontracts.AimiPumpSnapshot
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickMeta
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimicontracts.AimiTickTrigger
import app.aaps.plugins.aimicontracts.TimedValue
import app.aaps.plugins.aimiengine.AimiEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: replay harness + engine wired end to end.
 *
 * BLOCKED (documented, not worked around): the real wired engine,
 * `DetermineBasalAimiEngine`, wraps `DetermineBasalaimiSMB2`, which needs
 * ~17 constructor dependencies plus ~15 `@Inject lateinit` fields
 * (persistenceLayer, tddCalculator, dateUtil, activePlugin, learners, ...).
 * It cannot be instantiated in a commonTest without a full DI graph.
 *
 * This test therefore uses [GlucoseAwareFakeEngine], a deterministic fake
 * whose output depends only on (input, state): hypo -> Hold, hyper -> Smb,
 * in-range -> Hold, with the generation bumped by one each tick.
 * The day the real engine becomes instantiable in commonTest, swap the fake
 * for `DetermineBasalAimiEngine(plugin)` here — the assertions stay valid.
 */
private class GlucoseAwareFakeEngine : AimiEngine {
    override fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle,
    ): AimiTickResult {
        val glucose = input.glucose.glucoseMgdl.valueOrNull ?: 0.0
        val command: AimiTherapyCommand = when {
            glucose < 70.0 -> AimiTherapyCommand.Hold("HYPO")
            glucose > 180.0 -> AimiTherapyCommand.Smb((glucose - 180.0) / 100.0)
            else -> AimiTherapyCommand.Hold("IN_RANGE")
        }
        return AimiTickResult(
            command = command,
            nextState = state.copy(generation = state.generation + 1),
            trainingEvents = emptyList(),
            persistenceEvents = emptyList(),
            telemetry = AimiDecisionTrace(reasonCode = "INTEGRATION"),
            safety = AimiSafetyReport(holdReasonCode = null),
        )
    }
}

private fun freshDouble(value: Double): TimedValue<Double> =
    TimedValue.Fresh(value = value, capturedAtEpochMs = 1L, ageMs = 0L)

private fun inputWithGlucose(tickId: Long, glucoseMgdl: Double): AimiInputSnapshot {
    val base = AimiTestSnapshots.emptyInput(tickId = tickId, loopEligible = true)
    return base.copy(
        glucose = AimiGlucoseSnapshot(
            glucoseMgdl = freshDouble(glucoseMgdl),
            sourceId = "AAPS-DexcomOnePlus",
            warmup = AimiGlucoseWarmup.None,
            loopEligible = true,
        ),
    )
}

/**
 * Builds captures with properly chained expected results: each tick's expected
 * output is recorded with the state the previous tick actually produced,
 * mirroring what [replay] does.
 */
private fun chainedCaptures(
    engine: AimiEngine,
    glucoses: List<Pair<Long, Double>>,
): List<AimiTickCapture> {
    var state = AimiTestSnapshots.emptyState(0L)
    val models = AimiTestSnapshots.emptyModels()
    return glucoses.map { (tickId, glucoseMgdl) ->
        val input = inputWithGlucose(tickId, glucoseMgdl)
        val expected = engine.evaluate(input, state, models)
        val capture = AimiTickCapture(input = input, state = state, models = models, expected = expected)
        state = expected.nextState
        capture
    }
}

class AimiReplayIntegrationTest {

    @Test
    fun replay_engine_end_to_end_no_exceptions() {
        val engine = GlucoseAwareFakeEngine()
        // Normal (120), hypo (65), hyper (220): three clinical scenarios.
        val captures = chainedCaptures(
            engine,
            listOf(1L to 120.0, 2L to 65.0, 3L to 220.0),
        )

        // Must not throw.
        val outcomes = replay(engine, captures)

        assertEquals(3, outcomes.size)
    }

    @Test
    fun replay_is_deterministic_across_runs() {
        val engine = GlucoseAwareFakeEngine()
        // Normal (120), hypo (65), hyper (220): three clinical scenarios.
        val captures = chainedCaptures(
            engine,
            listOf(1L to 120.0, 2L to 65.0, 3L to 220.0),
        )

        val first = replay(engine, captures)
        val second = replay(engine, captures)

        assertEquals(first.size, second.size)
        for (i in first.indices) {
            val diff = compare(first[i].actual, second[i].actual)
            assertTrue(diff.passed, "tick ${first[i].tickId} differs between runs: ${diff.diffs}")
        }
    }

    @Test
    fun replay_produces_expected_clinical_commands() {
        val engine = GlucoseAwareFakeEngine()
        // Normal (120), hypo (65), hyper (220): three clinical scenarios.
        val captures = chainedCaptures(
            engine,
            listOf(1L to 120.0, 2L to 65.0, 3L to 220.0),
        )

        val outcomes = replay(engine, captures)

        // In-range -> Hold, hypo -> Hold(HYPO), hyper -> Smb.
        assertTrue(outcomes[0].actual.command is AimiTherapyCommand.Hold)
        val hypoHold = outcomes[1].actual.command as AimiTherapyCommand.Hold
        assertEquals("HYPO", hypoHold.reasonCode)
        assertTrue(outcomes[2].actual.command is AimiTherapyCommand.Smb)
        // State chained: generations 1, 2, 3.
        assertEquals(1L, outcomes[0].actual.nextState.generation)
        assertEquals(2L, outcomes[1].actual.nextState.generation)
        assertEquals(3L, outcomes[2].actual.nextState.generation)
    }

    @Test
    fun report_renders_for_integration_run() {
        val engine = GlucoseAwareFakeEngine()
        // Normal (120), hypo (65), hyper (220): three clinical scenarios.
        val captures = chainedCaptures(
            engine,
            listOf(1L to 120.0, 2L to 65.0, 3L to 220.0),
        )

        val outcomes = replay(engine, captures)
        val diffs = outcomes.map { compare(it.expected, it.actual) }
        val report = renderReport(outcomes, diffs)

        assertTrue(report.contains("3 tick(s), 3 passed, 0 failed"), "unexpected report:\n$report")
        assertTrue(report.contains("[PASS] tick 1"))
        assertTrue(report.contains("[PASS] tick 2"))
        assertTrue(report.contains("[PASS] tick 3"))
    }
}
