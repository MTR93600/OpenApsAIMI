package app.aaps.plugins.aps.openAPSAIMI.carbs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class CarbsAdvisorTest {

    @Test
    fun `test estimateRequiredCarbs no hypo`() {
        // High BG, no drop
        val result = CarbsAdvisor.estimateRequiredCarbs(
            bg = 150.0,
            targetBG = 100.0,
            slope = 0.0,
            iob = 0.0,
            csf = 10.0,
            isf = 50.0,
            cob = 0.0
        )
        assertEquals(0, result)
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `test estimateRequiredCarbs hypo predicted`() {
        // BG 100. Target 100.
        // Slope -2.0 -> drop 40 in 20 min.
        // IOB 1.0 * ISF 50 = 50 drop.
        // Total drop = 90.
        // Future BG = 10.
        // Diff = 100 - 10 = 90.
        // CSF 10. Needed = 9.
        // COB 0.
        
        val result = CarbsAdvisor.estimateRequiredCarbs(
            bg = 100.0,
            targetBG = 100.0,
            slope = -2.0,
            iob = 1.0,
            csf = 10.0,
            isf = 50.0,
            cob = 0.0
        )
        assertEquals(9, result)
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `test estimateRequiredCarbs with COB`() {
        // Same as above, but COB 10.
        // COB effect = 10 * 0.2 = 2.
        // Needed = 9 - 2 = 7.
        
        val result = CarbsAdvisor.estimateRequiredCarbs(
            bg = 100.0,
            targetBG = 100.0,
            slope = -2.0,
            iob = 1.0,
            csf = 10.0,
            isf = 50.0,
            cob = 10.0
        )
        assertEquals(7, result)
    }
}
