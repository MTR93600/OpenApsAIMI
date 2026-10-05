package app.aaps.plugins.aimiengine

import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aimicontracts.TimedValue
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiEngineTest {

    @AfterTest
    fun switchOff() {
        AimiCommonEngineSwitch.enabled = false
    }

    @Test
    fun hello_is_a_fixed_string_with_no_aimi_logic() {
        assertEquals("aimi-engine", AimiEngineFacade.hello())
    }

    @Test
    fun evaluate_holds_and_does_not_command_insulin() {
        val engine: AimiEngine = HoldAimiEngine()
        val input = AimiTestSnapshots.emptyInput()
        val state = AimiTestSnapshots.emptyState(generation = 7L)
        val result = engine.evaluate(input, state, AimiTestSnapshots.emptyModels())
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, command.reasonCode)
        assertEquals(7L, result.nextState.generation)
        assertTrue(result.trainingEvents.isEmpty())
        assertTrue(result.persistenceEvents.isEmpty())
    }

    @Test
    fun evaluate_does_not_treat_missing_glucose_as_zero() {
        val engine: AimiEngine = HoldAimiEngine()
        val input = AimiTestSnapshots.emptyInput()
        assertTrue(input.glucose.glucoseMgdl is TimedValue.Missing)
        assertNull(input.glucose.glucoseMgdl.valueOrNull)
        val result = engine.evaluate(input, AimiTestSnapshots.emptyState(), AimiTestSnapshots.emptyModels())
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
    }

    @Test
    fun switch_off_keeps_hold_even_when_a_common_engine_is_supplied() {
        val delegate = CountingEngine(AimiTherapyCommand.TempBasal(rateUPerHour = 9.0, durationMs = 1L))
        AimiCommonEngineSwitch.enabled = false
        val result = HoldAimiEngine(delegate).evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(generation = 3L),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, command.reasonCode)
        assertNull(result.pairedCommand)
        assertEquals(0, delegate.calls)
        assertEquals(3L, result.nextState.generation)
    }

    @Test
    fun switch_on_without_a_delegate_still_holds() {
        AimiCommonEngineSwitch.enabled = true
        val result = HoldAimiEngine().evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, command.reasonCode)
    }

    @Test
    fun switch_on_delegates_and_does_not_catch_the_common_engine() {
        val delegate = CountingEngine(AimiTherapyCommand.Smb(insulinU = 3.3))
        AimiCommonEngineSwitch.enabled = true
        val result = HoldAimiEngine(delegate).evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(generation = 4L),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Smb)
        assertEquals(3.3, command.insulinU)
        assertEquals(1, delegate.calls)
        assertEquals("DELEGATED", result.telemetry.reasonCode)
        assertEquals(4L, result.nextState.generation)
    }

    @Test
    fun seeded_prng_is_deterministic() {
        val a = AimiSeededPrng(seed = 42L)
        val b = AimiSeededPrng(seed = 42L)
        assertEquals(a.nextDouble(), b.nextDouble())
        assertEquals(a.nextLong(), b.nextLong())
    }

    private class CountingEngine(
        private val command: AimiTherapyCommand,
    ) : AimiEngine {
        var calls: Int = 0

        override fun evaluate(
            input: AimiInputSnapshot,
            state: AimiEngineState,
            models: AimiModelBundle,
        ): AimiTickResult {
            calls += 1
            return AimiTickResult(
                command = command,
                nextState = state,
                trainingEvents = emptyList(),
                persistenceEvents = emptyList(),
                telemetry = AimiDecisionTrace("DELEGATED"),
                safety = AimiSafetyReport(holdReasonCode = null),
            )
        }
    }
}
