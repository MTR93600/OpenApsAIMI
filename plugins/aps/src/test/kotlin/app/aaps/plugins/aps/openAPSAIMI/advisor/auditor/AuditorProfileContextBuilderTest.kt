package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turns the ring's own ticks into the window the profile checker is shown, and the same window the
 * validator recomputes its claim check against. This is the fixture of the design spec, the 22:12
 * tick of `AIMI_Decisions_Last24h.jsonl` (1790021540129): every number here was checked by hand
 * against the spec's own numbers, not just against the code.
 */
class AuditorProfileContextBuilderTest {

    private fun fact(
        ts: Long,
        bg: Double,
        delta: Double,
        iob: Double,
        commandIsf: Double,
        target: Double,
        runningBasal: Double,
        profileBasal: Double,
    ) = AuditorTickFact(
        timestampMs = ts,
        bgMgdl = bg,
        deltaMgdl5m = delta,
        shortAvgDeltaMgdl5m = delta,
        iobU = iob,
        cobG = 0.0,
        profileIsfStaticMgdl = 60.0,
        dynamicIsfRawMgdl = commandIsf,
        commandIsfRawMgdl = commandIsf,
        commandIsfPreFloorMgdl = 36.649,
        commandFloorMultiplier = 1.0,
        workingIsfRawMgdl = null,
        profileTargetMgdl = 100.0,
        tempTargetActive = false,
        workingTargetRawMgdl = target,
        minPredBgMgdl = null,
        hypoThresholdMgdl = null,
        runningBasalUph = runningBasal,
        profileBasalUph = profileBasal,
        postHypoActive = false,
        exerciseLockout = false,
        isfFactorApplied = 1.0,
        targetFactorApplied = 1.0,
    )

    private val sevenTicks = listOf(
        fact(1790019740092L, 158.7, -7.71, 11.192, 77.28, 100.0, 0.0011, 0.51),
        fact(1790020041258L, 164.6, 5.37, 10.805, 42.26, 100.0, 0.8838, 0.51),
        fact(1790020339978L, 172.5, 6.91, 10.573, 36.53, 75.0, 4.6948, 0.60),
        fact(1790020641823L, 167.7, 0.19, 10.444, 47.84, 77.0, 4.7094, 0.60),
        fact(1790020939697L, 168.0, 0.36, 10.429, 38.30, 100.0, 4.7094, 0.60),
        fact(1790021240115L, 172.5, 2.73, 10.402, 35.89, 100.0, 4.7094, 0.60),
        fact(1790021540129L, 173.6, 1.71, 10.374, 60.00, 100.0, 4.7094, 0.60),
    )

    private val auditedTickMs = 1790021540129L

    @Test
    fun `the seven ticks build a complete window with the spec's own numbers`() {
        val context = AuditorProfileContextBuilder.build(
            ticks = sevenTicks,
            nowMs = auditedTickMs,
            bolusU = 0.2226,
            carbsG = 0.0,
            mealModeName = null,
            mealCertaintyLevel = "LOW",
            mealSupport = false,
            minBg75mMgdl = 158.7,
            cgmNoise = 0.0,
        )
        assertTrue(context.complete)
        assertEquals(7, context.completeness)
        assertEquals(158.7, context.bgStartMgdl!!, 1e-9)
        assertEquals(173.6, context.bgEndMgdl!!, 1e-9)
        assertEquals(158.7, context.bgMinMgdl!!, 1e-9)
        assertEquals(11.192, context.iobStartU!!, 1e-9)
        assertEquals(10.374, context.iobEndU!!, 1e-9)
        assertEquals(1.3572, context.netBasalU, 1e-3)
        assertEquals(1.5798, context.insulinDeliveredU, 1e-3)
        assertEquals(2.3978, context.insulinAbsorbedU!!, 1e-3)
        assertEquals(-6.214, context.impliedIsfMgdlPerU!!, 1e-2)
        assertEquals(1.0, context.isfCommandOverProfile!!, 1e-9)
        assertTrue(context.isfOnProfileFloor)
    }

    @Test
    fun `the prompt JSON of the full fixture stays under the size budget`() {
        val context = AuditorProfileContextBuilder.build(
            ticks = sevenTicks,
            nowMs = auditedTickMs,
            bolusU = 0.2226,
            carbsG = 0.0,
            mealModeName = null,
            mealCertaintyLevel = "LOW",
            mealSupport = false,
            minBg75mMgdl = 158.7,
            cgmNoise = 0.0,
        )
        assertTrue(context.toPromptJson().toString().length < AuditorProfileFactorLimits.CONTEXT_JSON_MAX_CHARS)
    }

    @Test
    fun `a hole in the middle of the window still counts as complete`() {
        val holed = sevenTicks.filterIndexed { index, _ -> index != 1 && index != 2 }
        val context = AuditorProfileContextBuilder.build(
            ticks = holed, nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 158.7, cgmNoise = 0.0,
        )
        assertEquals(5, context.completeness)
        assertTrue(context.complete)
    }

    @Test
    fun `a hole at either end of the window is never complete`() {
        val missingOldest = sevenTicks.drop(1)
        val context = AuditorProfileContextBuilder.build(
            ticks = missingOldest, nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 158.7, cgmNoise = 0.0,
        )
        assertEquals(6, context.completeness)
        assertFalse(context.complete)
    }

    @Test
    fun `the minutes label of a point is the real age of the tick, not the age of its bucket`() {
        val context = AuditorProfileContextBuilder.build(
            ticks = sevenTicks, nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 158.7, cgmNoise = 0.0,
        )
        // The oldest tick is 30.0006 minutes old and the newest is the audited tick itself.
        assertEquals(30, context.points.first()!!.minutesAgo)
        assertEquals(0, context.points.last()!!.minutesAgo)

        // A tick that drifted 2 minutes off the grid is labelled 17, not 15.
        val drifted = sevenTicks.toMutableList()
        drifted[3] = drifted[3].copy(timestampMs = auditedTickMs - 17 * 60_000L)
        val withDrift = AuditorProfileContextBuilder.build(
            ticks = drifted, nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 158.7, cgmNoise = 0.0,
        )
        assertTrue(withDrift.points.any { it?.minutesAgo == 17 })
    }

    @Test
    fun `a tick older than the window edge is left out`() {
        // The edge is the oldest bucket centre plus half a bucket: 32.5 minutes.
        val tooOld = sevenTicks.toMutableList()
        tooOld[0] = tooOld[0].copy(timestampMs = auditedTickMs - 33 * 60_000L)
        val context = AuditorProfileContextBuilder.build(
            ticks = tooOld, nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 158.7, cgmNoise = 0.0,
        )
        assertEquals(6, context.completeness)
        assertFalse(context.complete)
    }

    @Test
    fun `an empty ring gives an empty, incomplete context, never a crash`() {
        val context = AuditorProfileContextBuilder.build(
            ticks = emptyList(), nowMs = auditedTickMs, bolusU = 0.0, carbsG = 0.0,
            mealModeName = null, mealCertaintyLevel = null, mealSupport = false,
            minBg75mMgdl = 100.0, cgmNoise = 0.0,
        )
        assertFalse(context.complete)
        assertEquals(0, context.completeness)
        assertEquals(null, context.bgStartMgdl)
        assertEquals(null, context.insulinAbsorbedU)
    }
}
