package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkpdLearningWindowGateTest {

    /** Records one delta every 5 min from [fromMin] up to (not including) [toMin]. */
    private fun PkpdLearningWindowGate.fill(fromMin: Long, toMin: Long, delta: (Long) -> Double) {
        var t = fromMin
        while (t < toMin) {
            record(t, delta(t))
            t += 5
        }
    }

    @Test
    fun quietHourPasses() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { -1.0 }
        val verdict = gate.evaluate(65, -0.5, mealConfidence = 0.2)
        assertTrue(verdict.pass)
        assertNull(verdict.blockedBy)
        assertEquals(-1.0, verdict.maxRecentDeltaMgdl!!, 1e-9)
    }

    @Test
    fun riseInsideTheWindowBlocks() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { t -> if (t == 30L) 4.0 else 0.0 }
        val verdict = gate.evaluate(65, 0.0, mealConfidence = 0.2)
        assertFalse(verdict.pass)
        assertEquals(PkpdLearningWindowGate.BLOCK_RECENT_RISE, verdict.blockedBy)
    }

    @Test
    fun riseOlderThanTheWindowIsForgotten() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 135) { t -> if (t < 60L) 6.0 else -1.0 }
        assertTrue(gate.evaluate(135, -1.0, mealConfidence = 0.0).pass)
    }

    @Test
    fun risingNowBlocks() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        assertEquals(PkpdLearningWindowGate.BLOCK_RISING_NOW, gate.evaluate(65, 1.5, 0.0).blockedBy)
    }

    /** The meal belief has a background plateau near 0.40: that must NOT block, 0.50 must. */
    @Test
    fun mealVetoSitsAboveTheBackgroundPlateau() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        assertTrue(gate.evaluate(65, 0.0, mealConfidence = 0.40).pass)
        assertEquals(PkpdLearningWindowGate.BLOCK_MEAL_BELIEF, gate.evaluate(65, 0.0, mealConfidence = 0.50).blockedBy)
    }

    /** A loop gap is not a quiet hour. */
    @Test
    fun shortOrGappedHistoryBlocks() {
        val empty = PkpdLearningWindowGate()
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, empty.evaluate(65, 0.0, 0.0).blockedBy)

        val gapped = PkpdLearningWindowGate()
        gapped.fill(35, 65) { 0.0 }
        val verdict = gapped.evaluate(65, 0.0, 0.0)
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, verdict.blockedBy)
        assertEquals(30L, verdict.coveredMin)
    }

    /** The tick being judged is not part of its own window, and a repeated minute replaces the old one. */
    @Test
    fun sameMinuteReplacesAndCurrentTickIsExcluded() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        gate.record(65, 9.0)
        gate.record(65, 0.0)
        assertTrue(gate.evaluate(70, 0.0, 0.0).pass)
        // A rise recorded at the judged minute itself is not in its window.
        gate.record(75, 9.0)
        assertTrue(gate.evaluate(75, 0.0, 0.0).pass)
    }

    @Test
    fun nonFiniteDeltaBlocksAndIsNotRecorded() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        gate.record(65, Double.NaN)
        assertEquals(PkpdLearningWindowGate.BLOCK_NO_DELTA, gate.evaluate(70, Double.NaN, 0.0).blockedBy)
        assertTrue(gate.evaluate(70, 0.0, 0.0).pass)
    }

    /** 2026-10-06: two learning ticks 2 and 7 min after an FCL prebolus, glucose still flat. */
    @Test
    fun declaredMealBlocksThreeHoursEvenOnAFlatCurve() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        assertEquals(PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, gate.evaluate(65, 0.0, 0.0, declaredMealAgeMin = 2).blockedBy)
        assertEquals(PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, gate.evaluate(65, 0.0, 0.0, declaredMealAgeMin = 179).blockedBy)
        assertTrue(gate.evaluate(65, 0.0, 0.0, declaredMealAgeMin = 180).pass)
    }

    @Test
    fun highCarbMealBlocksLonger() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        val verdict = gate.evaluate(65, 0.0, 0.0, declaredMealAgeMin = 200, declaredMealHighCarb = true)
        assertEquals(PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, verdict.blockedBy)
        assertEquals(200L, verdict.declaredMealAgeMin)
        assertTrue(gate.evaluate(65, 0.0, 0.0, declaredMealAgeMin = 240, declaredMealHighCarb = true).pass)
    }

    /** UAM: a +30 mg/dL rise in 30 min opens a meal episode; its slow tail must not train DIA. */
    @Test
    fun detectedRiseBlocksTheTailThenExpires() {
        val gate = PkpdLearningWindowGate()
        // Rise 100 -> 140 between minute 0 and 30, then a long flat plateau at 140.
        var t = 0L
        while (t <= 400) {
            val bg = if (t <= 30) 100.0 + t * 40.0 / 30.0 else 140.0
            gate.record(t, if (t <= 30) 6.0 else 0.0, bg)
            t += 5
        }
        // 150 min after the start: the curve is quiet, but the episode is not over.
        val fresh = PkpdLearningWindowGate()
        t = 0L
        while (t < 180) {
            val bg = if (t <= 30) 100.0 + t * 40.0 / 30.0 else 140.0
            fresh.record(t, if (t <= 30) 6.0 else 0.0, bg)
            t += 5
        }
        val inTail = fresh.evaluate(180, 0.0, 0.0)
        assertEquals(PkpdLearningWindowGate.BLOCK_DETECTED_RISE, inTail.blockedBy)
        assertTrue(inTail.detectedRiseAgeMin!! < PkpdLearningWindowGate.DETECTED_RISE_BLOCK_MIN)
        // Long after: free again.
        assertTrue(gate.evaluate(405, 0.0, 0.0).pass)
    }

    @Test
    fun aSlowDriftIsNotAMeal() {
        val gate = PkpdLearningWindowGate()
        // +1 mg/dL per 5 min = +6 in 30 min: under the meal-size threshold.
        var t = 0L
        while (t < 65) {
            gate.record(t, 1.0, 100.0 + t / 5.0)
            t += 5
        }
        val verdict = gate.evaluate(65, 0.0, 0.0)
        assertTrue(verdict.pass)
        assertNull(verdict.detectedRiseAgeMin)
    }

    @Test
    fun clockGoingBackRestartsHistory() {
        val gate = PkpdLearningWindowGate()
        gate.fill(100, 165) { 0.0 }
        gate.record(10, 0.0)
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, gate.evaluate(15, 0.0, 0.0).blockedBy)
    }
}
