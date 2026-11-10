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
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UnifiedReactivityStatusTest {

    @Test
    fun `completed analysis publishes immutable factors and real counter`() {
        val now = 10_000L
        val directory = Files.createTempDirectory("reactivity-status").toFile()
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any()) } answers { directory.resolve(firstArg<String>()) }
        every { storage.loadFileSafe(any(), any(), any()) } returns false
        every { storage.saveFileSafe(any(), any()) } returns true
        val dateUtil = mockk<DateUtil>()
        every { dateUtil.now() } returns now
        val learner = UnifiedReactivityLearner(
            context = mockk<Context>(relaxed = true),
            persistenceLayer = mockk<PersistenceLayer>(relaxed = true),
            dateUtil = dateUtil,
            preferences = mockk<Preferences>(relaxed = true),
            log = mockk<AAPSLogger>(relaxed = true),
            storageHelper = storage,
        )
        val performance = UnifiedReactivityLearner.GlycemicPerformance(
            tir70_180 = 80.0,
            tir70_140 = 60.0,
            tir140_180 = 20.0,
            tir180_250 = 15.0,
            tir_above_250 = 5.0,
            tir_above_180 = 20.0,
            hypo_count = 0,
            tir_below_70 = 0.0,
            cv_percent = 25.0,
            crossing_count = 2,
            mean_bg = 130.0,
            total_readings = 24,
        )

        learner.computeAdjustment(performance)
        val first = learner.statusSnapshot()
        val second = learner.statusSnapshot()

        assertEquals(1L, first.longAnalysisCount)
        assertEquals(24, first.last24hSampleCount)
        assertEquals(24, first.lastAnalysis?.totalReadings)
        assertNotSame(first.segmentFactors, second.segmentFactors)
        assertEquals(first.segmentFactors, second.segmentFactors)
    }
}
