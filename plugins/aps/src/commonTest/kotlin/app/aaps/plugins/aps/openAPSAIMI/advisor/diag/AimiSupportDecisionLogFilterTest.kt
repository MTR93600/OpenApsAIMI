package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Last-24h decision-log filter used by the support ZIP.
 * Same heuristic as parked `AimiProfileAdvisorActivity` (not invented).
 */
class AimiSupportDecisionLogFilterTest {

    @Test
    fun `a line at or after the cutoff is kept`() {
        assertTrue(AimiSupportDecisionLogFilter.keep("""{"timestamp":1000,"x":1}""", 1000L))
        assertTrue(AimiSupportDecisionLogFilter.keep("""{"timestamp":1001}""", 1000L))
    }

    @Test
    fun `a line before the cutoff is dropped`() {
        assertFalse(AimiSupportDecisionLogFilter.keep("""{"timestamp":999}""", 1000L))
    }

    @Test
    fun `a line without a timestamp is dropped`() {
        assertFalse(AimiSupportDecisionLogFilter.keep("""{"bg":120}""", 1000L))
    }
}
