package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CausalKineticsContextMemoryTest {

    private val stress = CausalStatePosterior(
        stressResistanceProb = 1.0,
        dominant = CausalStateId.STRESS_RESISTANCE,
        dominantConfidence = 1.0,
    )
    private val minute = 60_000L

    @Test
    fun firstTickHasNoContext() {
        assertNull(CausalKineticsContextMemory().forTickAt(1_000L))
    }

    @Test
    fun nextTickGetsThePosteriorOfThePreviousTick() {
        val memory = CausalKineticsContextMemory()
        memory.remember(tickTimeMs = 10 * minute, posterior = stress)
        assertEquals(stress, memory.forTickAt(15 * minute))
    }

    @Test
    fun aTickWithoutPosteriorClearsTheContext() {
        val memory = CausalKineticsContextMemory()
        memory.remember(tickTimeMs = 10 * minute, posterior = stress)
        memory.remember(tickTimeMs = 15 * minute, posterior = null)
        assertNull(memory.forTickAt(20 * minute))
    }

    @Test
    fun tooOldPosteriorIsNotUsed() {
        val memory = CausalKineticsContextMemory()
        memory.remember(tickTimeMs = 0L, posterior = stress)
        assertEquals(stress, memory.forTickAt(CausalKineticsContextMemory.MAX_AGE_MS))
        assertNull(memory.forTickAt(CausalKineticsContextMemory.MAX_AGE_MS + 1L))
    }

    @Test
    fun posteriorFromTheFutureIsNotUsed() {
        val memory = CausalKineticsContextMemory()
        memory.remember(tickTimeMs = 20 * minute, posterior = stress)
        assertNull(memory.forTickAt(10 * minute))
    }

    @Test
    fun runTickKeepsThePosteriorThatTheTickLeft() {
        val memory = CausalKineticsContextMemory()
        var posterior: CausalStatePosterior? = null
        val result = memory.runTick(tickTimeMs = 10 * minute, posteriorAfterTick = { posterior }) {
            posterior = stress
            42
        }
        assertEquals(42, result)
        assertEquals(stress, memory.forTickAt(15 * minute))
    }

    @Test
    fun aTickThatThrowsLeavesNoContext() {
        val memory = CausalKineticsContextMemory()
        memory.remember(tickTimeMs = 10 * minute, posterior = stress)
        assertFailsWith<IllegalStateException> {
            memory.runTick(tickTimeMs = 15 * minute, posteriorAfterTick = { stress }) { error("tick failed") }
        }
        // The next tick must not dose with the posterior of two ticks ago.
        assertNull(memory.forTickAt(20 * minute))
    }
}
