package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Golden cases for the post-hypo window. The 45 minute expiry and the 30 minute meal split are the ref. */
class AimiPostHypoClassifierTest {

    private val quietBgs = List(12) { 110f }

    @Test
    fun `a reading under 70 starts the window and a meal confirms it`() {
        var confidenceReads = 0
        val step = AimiPostHypoClassifier.classify(
            recentBGs = listOf(60f) + quietBgs,
            cob = 0.0,
            explicitMealMode = false,
            shortAvgDelta = 1f,
            delta = 1f,
            slopeFromMinDeviation = 0.2,
            estimatedCarbs = 0.0,
            estimatedCarbsAgeMs = 0L,
            localHour = 12,
            targetBg = 100.0,
            lastHypoBelow70At = 0L,
            now = 1_000_000L,
            uamConfidence = { confidenceReads += 1; 0.9 },
        )
        val meal = assertIs<AimiPostHypoState.MealConfirmed>(step.state)
        assertEquals(0L, meal.sinceMs)
        assertEquals(1_000_000L, step.lastHypoBelow70At)
        assertTrue(step.reason.contains("POST_HYPO_MEAL"))
        assertEquals(1, confidenceReads)
    }

    @Test
    fun `forty six minutes after the hypo the guard is gone`() {
        val now = 46 * 60_000L
        val step = AimiPostHypoClassifier.classify(
            recentBGs = quietBgs,
            cob = 20.0,
            explicitMealMode = true,
            shortAvgDelta = 4f,
            delta = 4f,
            slopeFromMinDeviation = 2.0,
            estimatedCarbs = 40.0,
            estimatedCarbsAgeMs = 1_000L,
            localHour = 12,
            targetBg = 100.0,
            lastHypoBelow70At = now - 46 * 60_000L,
            now = now,
            uamConfidence = { error("confidence must not be read after the window") },
        )
        assertIs<AimiPostHypoState.None>(step.state)
        assertEquals(0L, step.lastHypoBelow70At)
        assertEquals("", step.reason)
    }

    @Test
    fun `no meal inside thirty minutes is a suspected rebound`() {
        val step = AimiPostHypoClassifier.classify(
            recentBGs = quietBgs,
            cob = 0.0,
            explicitMealMode = false,
            shortAvgDelta = 0f,
            delta = 0f,
            slopeFromMinDeviation = 0.0,
            estimatedCarbs = 0.0,
            estimatedCarbsAgeMs = Long.MAX_VALUE,
            localHour = 3,
            targetBg = 0.0,
            lastHypoBelow70At = 500_000L,
            now = 500_000L + 10 * 60_000L,
            uamConfidence = { 0.0 },
        )
        val rebound = assertIs<AimiPostHypoState.ReboundSuspected>(step.state)
        assertEquals(10 * 60_000L, rebound.sinceMs)
        assertTrue(step.reason.contains("POST_HYPO_REBOUND"))
    }
}
