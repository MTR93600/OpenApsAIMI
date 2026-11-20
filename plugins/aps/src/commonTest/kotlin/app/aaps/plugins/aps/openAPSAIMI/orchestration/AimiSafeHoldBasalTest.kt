package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiSafeHoldBasal.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The temp basal of a safe hold. Owner's rule (2026-11-20): the profile basal, unless glucose is at
 * the hypo threshold or falling to it within 30 minutes, then a zero temp. Never an SMB, never more
 * than the profile basal, and no change when the inputs cannot be trusted.
 *
 * min_bg 100 gives a hypo threshold of 70 (no LGS threshold set).
 */
class AimiSafeHoldBasalTest {

    private fun decide(
        bg: Double = 150.0,
        delta: Double = 0.0,
        shortAvgDelta: Double = 0.0,
        glucoseAgeMinutes: Double? = 2.0,
        profileBasal: Double = 0.8,
        minBg: Double = 100.0,
        lgsThreshold: Int? = null,
        currentRate: Double = 0.05,
        currentRemainingMinutes: Int = 20,
    ): Command = AimiSafeHoldBasal.decide(
        bg, delta, shortAvgDelta, glucoseAgeMinutes, profileBasal, minBg, lgsThreshold, currentRate, currentRemainingMinutes,
    )

    @Test
    fun `a near zero temp during a rise is replaced by the profile basal`() {
        // The field report: 0.05 U/h running while glucose rose from 133 to 184.
        val command = assertIs<Command.Temp>(decide(bg = 184.0, delta = 4.0, shortAvgDelta = 3.0, currentRate = 0.05))
        assertEquals(0.8, command.rateUPerH)
        assertEquals(30, command.durationMinutes)
    }

    @Test
    fun `a high temp during a fall above the threshold is brought down to the profile basal`() {
        val command = assertIs<Command.Temp>(decide(bg = 160.0, delta = -3.0, shortAvgDelta = -2.0, currentRate = 3.0))
        assertEquals(0.8, command.rateUPerH)
    }

    @Test
    fun `glucose at the hypo threshold gives a zero temp`() {
        val command = assertIs<Command.Temp>(decide(bg = 70.0, currentRate = 0.8))
        assertEquals(0.0, command.rateUPerH)
        assertEquals(30, command.durationMinutes)
    }

    @Test
    fun `a fall that reaches the threshold within 30 minutes gives a zero temp`() {
        // 100 - 6 * 5 = 70, the threshold.
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 100.0, delta = -5.0, shortAvgDelta = -4.0)).rateUPerH)
        // The steeper of the two deltas counts.
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 100.0, delta = -1.0, shortAvgDelta = -5.0)).rateUPerH)
        // A slower fall that stays above it gives the profile basal.
        assertEquals(0.8, assertIs<Command.Temp>(decide(bg = 100.0, delta = -2.0, shortAvgDelta = -2.0)).rateUPerH)
    }

    @Test
    fun `the LGS threshold of the user is used when it is higher`() {
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 78.0, lgsThreshold = 80)).rateUPerH)
    }

    @Test
    fun `a zero temp with time left is kept`() {
        assertIs<Command.Keep>(decide(bg = 65.0, currentRate = 0.0, currentRemainingMinutes = 25))
        // Almost over: sent again.
        assertIs<Command.Temp>(decide(bg = 65.0, currentRate = 0.0, currentRemainingMinutes = 5))
    }

    @Test
    fun `with no temp running the pump already gives the profile basal`() {
        assertIs<Command.Keep>(decide(currentRemainingMinutes = 0))
        // Unless a zero temp is justified.
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 60.0, currentRemainingMinutes = 0)).rateUPerH)
    }

    @Test
    fun `a temp at the profile basal is kept`() {
        assertIs<Command.Keep>(decide(currentRate = 0.805))
    }

    @Test
    fun `old glucose only ever brings a high temp down`() {
        assertEquals(0.8, assertIs<Command.Temp>(decide(glucoseAgeMinutes = 30.0, currentRate = 2.0)).rateUPerH)
        assertIs<Command.Keep>(decide(glucoseAgeMinutes = 30.0, currentRate = 0.05))
        assertIs<Command.Keep>(decide(glucoseAgeMinutes = null, currentRate = 0.05))
        // Even when the old value looks low: no zero temp is judged on it.
        assertIs<Command.Keep>(decide(bg = 60.0, glucoseAgeMinutes = 30.0, currentRate = 0.5))
    }

    @Test
    fun `inputs that are not numbers change nothing upward`() {
        assertIs<Command.Keep>(decide(profileBasal = Double.NaN))
        assertIs<Command.Keep>(decide(profileBasal = -1.0))
        assertIs<Command.Keep>(decide(bg = Double.NaN, currentRate = 0.05))
        assertIs<Command.Keep>(decide(minBg = Double.NaN))
        // A not-a-number delta is ignored; the other one still counts.
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 90.0, delta = Double.NaN, shortAvgDelta = -5.0)).rateUPerH)
        // No usable trend at all: nothing changes, unless glucose is already at the threshold.
        assertIs<Command.Keep>(decide(bg = 150.0, delta = Double.NaN, shortAvgDelta = Double.NaN, currentRate = 0.0))
        assertEquals(0.0, assertIs<Command.Temp>(decide(bg = 65.0, delta = Double.NaN, shortAvgDelta = Double.NaN, currentRate = 0.8)).rateUPerH)
    }

    @Test
    fun `the hold basal is never above the profile basal`() {
        for (bg in listOf(40.0, 70.0, 100.0, 180.0, 400.0)) {
            for (rate in listOf(0.0, 0.05, 0.8, 5.0)) {
                val command = decide(bg = bg, delta = 10.0, shortAvgDelta = 10.0, currentRate = rate)
                if (command is Command.Temp) assertEquals(true, command.rateUPerH <= 0.8, "bg=$bg rate=$rate")
            }
        }
    }
}
