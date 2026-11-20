package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The export runs after the dose is decided and only records it. It must never be able to cancel
 * the decision: a non-finite number in the export used to throw and turn the tick into a safe hold.
 */
class DecideTickEngineTest {

    private fun decided(): RT = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true, rate = 1.2, duration = 30)

    private class Calls(private val result: RT, private val exportError: Throwable?) : AimiTickEngineCalls<Unit> {
        var exported = false
        override fun basalEngine() = Unit
        override fun learners(decision: Unit): RT = result
        override fun export(finalResult: RT) {
            exported = true
            exportError?.let { throw it }
        }
    }

    @Test
    fun `a working export returns the decision unchanged`() {
        val rt = decided()
        val calls = Calls(rt, exportError = null)
        assertSame(rt, decideDetermineBasalTickEngine(calls))
        assertTrue(calls.exported)
        assertTrue(rt.consoleError.isNullOrEmpty())
    }

    @Test
    fun `a failing export keeps the decision and says so`() {
        val rt = decided()
        val out = decideDetermineBasalTickEngine(Calls(rt, IllegalArgumentException("Cannot round NaN value.")))
        assertSame(rt, out)
        assertEquals(1.2, out.rate)
        assertEquals(30, out.duration)
        val line = out.consoleError.orEmpty().single()
        assertTrue(line.contains("export failed") && line.contains("Cannot round NaN value."), line)
    }

    @Test
    fun `a cancellation is not swallowed`() {
        assertFailsWith<CancellationException> {
            decideDetermineBasalTickEngine(Calls(decided(), CancellationException("cancelled")))
        }
    }
}
