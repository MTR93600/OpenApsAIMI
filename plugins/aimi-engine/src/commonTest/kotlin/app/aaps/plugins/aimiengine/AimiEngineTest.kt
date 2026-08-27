package app.aaps.plugins.aimiengine

import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.TimedValue
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiEngineTest {

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
    fun seeded_prng_is_deterministic() {
        val a = AimiSeededPrng(seed = 42L)
        val b = AimiSeededPrng(seed = 42L)
        assertEquals(a.nextDouble(), b.nextDouble())
        assertEquals(a.nextLong(), b.nextLong())
    }
}
