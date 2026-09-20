package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LgsSafetyTriageTest {

    private val basal = 1.2

    @Test
    fun tier1WhenBgBelowThreshold() {
        val r = resolveSafetyStart(
            bg = 60.0,
            delta = 0f,
            noise = 0,
            predBg = 100.0,
            eventualBg = 100.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.decision is DecisionResult.Applied)
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T1", a.source)
        assertEquals(0.0, a.tbrUph!!, 1e-9)
        assertEquals("SafetyLGS_T1", r.lastSafetySource)
        assertTrue(r.haltRemainingPipeline)
        assertTrue(r.consoleLines.any { it.contains("SAFETY_LGS_TIER1") })
    }

    @Test
    fun tier2WhenPredBelowThresholdAndBgOk() {
        val r = resolveSafetyStart(
            bg = 100.0,
            delta = 0f,
            noise = 0,
            predBg = 65.0,
            eventualBg = 100.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T2", a.source)
        assertEquals(basal * 0.25, a.tbrUph!!, 1e-9)
        assertEquals("SafetyLGS_T2", r.lastSafetySource)
        assertFalse(r.haltRemainingPipeline)
    }

    @Test
    fun tier2SuppressedWhenRisingAt137() {
        val r = resolveSafetyStart(
            bg = 137.0,
            delta = 5f,
            noise = 0,
            predBg = 65.0,
            eventualBg = 79.0,
            currentBasalUph = basal,
            lgsThreshold = 90,
        )
        assertTrue(r.decision is DecisionResult.Fallthrough)
        assertTrue(r.consoleLines.any { it.contains("SAFETY_LGS_RISING_GATE") })
    }

    @Test
    fun tier2SuppressedAtHyperArtifact311() {
        val r = resolveSafetyStart(
            bg = 311.0,
            delta = -1f,
            noise = 0,
            predBg = 87.0,
            eventualBg = 239.0,
            currentBasalUph = basal,
            lgsThreshold = 90,
        )
        assertTrue(r.decision is DecisionResult.Fallthrough)
    }

    @Test
    fun tier3WhenEventualBelowThreshold() {
        val r = resolveSafetyStart(
            bg = 100.0,
            delta = 0f,
            noise = 0,
            predBg = 100.0,
            eventualBg = 65.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T3", a.source)
        assertEquals(basal * 0.50, a.tbrUph!!, 1e-9)
        assertFalse(r.haltRemainingPipeline)
    }

    @Test
    fun tier3SuppressedWithMealContextOnFlatPreSpike() {
        val r = resolveSafetyStart(
            bg = 119.0,
            delta = 0.6f,
            noise = 0,
            predBg = 39.0,
            eventualBg = 39.0,
            currentBasalUph = basal,
            lgsThreshold = 90,
            mealContext = MealSafetyContext(mealModeActive = true),
        )
        assertTrue(r.decision is DecisionResult.Fallthrough)
    }

    @Test
    fun fallingTailStillTriggersTier3WithoutSuppression() {
        val r = resolveSafetyStart(
            bg = 120.0,
            delta = -4f,
            noise = 0,
            predBg = 100.0,
            eventualBg = 54.0,
            currentBasalUph = basal,
            lgsThreshold = 90,
        )
        assertTrue(r.decision is DecisionResult.Applied)
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T3", a.source)
    }

    @Test
    fun noiseBlocksBeforeFallthrough() {
        val r = resolveSafetyStart(
            bg = 150.0,
            delta = 0f,
            noise = 3,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyNoise", a.source)
        assertEquals("SafetyNoise", r.lastSafetySource)
        assertTrue(r.haltRemainingPipeline)
    }

    @Test
    fun fallthroughWhenSafe() {
        val r = resolveSafetyStart(
            bg = 150.0,
            delta = 0f,
            noise = 0,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.decision is DecisionResult.Fallthrough)
        assertEquals("SafetyPass", r.lastSafetySource)
    }

    @Test
    fun unitRangeWarningPrepended() {
        val r = resolveSafetyStart(
            bg = 800.0,
            delta = 0f,
            noise = 0,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.consoleLines.first().contains("Unit Mismatch"))
        assertTrue(r.decision is DecisionResult.Fallthrough)
    }
}
