package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Regression tests for the asymmetric learn-dynamics fixes (F1–F4) in [UnifiedReactivityLearner]:
 * the one-way ratchets where the reactivity factor collapsed fast on a single hypo but never recovered.
 */
class UnifiedReactivityLearnerLearnDynamicsTest {

    private fun newLearner(): UnifiedReactivityLearner {
        val directory = Files.createTempDirectory("reactivity-dynamics").toFile()
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any()) } answers { directory.resolve(firstArg<String>()) }
        every { storage.loadFileSafe(any(), any(), any()) } returns false
        every { storage.saveFileSafe(any(), any()) } returns true
        val dateUtil = mockk<DateUtil>()
        every { dateUtil.now() } returns 10_000L
        return UnifiedReactivityLearner(
            context = mockk<Context>(relaxed = true),
            persistenceLayer = mockk<PersistenceLayer>(relaxed = true),
            dateUtil = dateUtil,
            preferences = mockk<Preferences>(relaxed = true),
            log = mockk<AAPSLogger>(relaxed = true),
            storageHelper = storage,
        )
    }

    private fun perf(
        hypoCount: Int = 0,
        tirBelow70: Double = 0.0,
        tir70_180: Double = 90.0,
        cv: Double = 25.0,
        tirAbove180: Double = 5.0,
    ) = UnifiedReactivityLearner.GlycemicPerformance(
        tir70_180 = tir70_180,
        tir70_140 = tir70_180 * 0.7,
        tir140_180 = tir70_180 * 0.3,
        tir180_250 = tirAbove180,
        tir_above_250 = 0.0,
        tir_above_180 = tirAbove180,
        hypo_count = hypoCount,
        tir_below_70 = tirBelow70,
        cv_percent = cv,
        crossing_count = 2,
        mean_bg = 120.0,
        total_readings = 24,
    )

    // F1 — shortTermFactor previously had no recovery path: after a hypo it pinned at the 0.5 floor forever.
    @Test
    fun `shortTermFactor recovers on good windows after a hypo`() {
        val learner = newLearner()
        val hypoWindow = perf(hypoCount = 1, tirBelow70 = 5.0, tir70_180 = 60.0)
        repeat(10) { learner.computeShortTermAdjustment(hypoWindow) }
        val floored = learner.shortTermFactor
        assertTrue(floored < 0.6, "expected shortTermFactor to fall near floor, was $floored")

        val goodWindow = perf(hypoCount = 0, tirBelow70 = 0.0, tir70_180 = 95.0, cv = 20.0)
        repeat(10) { learner.computeShortTermAdjustment(goodWindow) }
        assertTrue(
            learner.shortTermFactor > floored + 0.1,
            "expected recovery above $floored, was ${learner.shortTermFactor}"
        )
    }

    // F2 — a single episode lingering in the rolling 24h window must not re-apply its penalty every analysis.
    @Test
    fun `single hypo episode is not re-punished while it lingers in the window`() {
        val learner = newLearner()
        val lingeringHypo = perf(hypoCount = 1, tirBelow70 = 1.39, tir70_180 = 80.0, cv = 30.0, tirAbove180 = 20.0)

        learner.computeAdjustment(lingeringHypo)
        val afterFirst = learner.globalFactor
        assertTrue(afterFirst < 1.0, "new episode should penalize once, was $afterFirst")

        repeat(10) { learner.computeAdjustment(lingeringHypo) }
        assertEquals(afterFirst, learner.globalFactor, 1e-6, "same episode must not keep compounding down")
    }

    // F4 — a good-but-not-optimal day (CV 36–44, hypo-free) previously froze the factor in place.
    @Test
    fun `good but not optimal day recovers a depressed global factor`() {
        val learner = newLearner()
        learner.computeAdjustment(perf(hypoCount = 2, tirBelow70 = 5.0, tir70_180 = 60.0, cv = 30.0))
        val depressed = learner.globalFactor
        assertTrue(depressed < 0.95, "expected a depressed factor, was $depressed")

        val goodEnough = perf(hypoCount = 0, tirBelow70 = 0.0, tir70_180 = 90.0, cv = 40.0, tirAbove180 = 5.0)
        repeat(10) { learner.computeAdjustment(goodEnough) }
        assertTrue(
            learner.globalFactor > depressed + 0.02,
            "expected recovery above $depressed, was ${learner.globalFactor}"
        )
    }
}
