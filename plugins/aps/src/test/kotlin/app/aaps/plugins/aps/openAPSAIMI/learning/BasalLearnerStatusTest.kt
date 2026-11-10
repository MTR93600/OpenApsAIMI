package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class BasalLearnerStatusTest {

    @Test
    fun `snapshot is coherent after a completed short update`() {
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any()) } returns File("build/tmp/basal-status.json")
        every { storage.loadFileSafe(any(), any(), any()) } returns false
        every { storage.saveFileSafe(any(), any()) } returns true
        val learner = BasalLearner(
            context = mockk<Context>(relaxed = true),
            log = mockk<AAPSLogger>(relaxed = true),
            storageHelper = storage,
        )

        repeat(3) {
            learner.process(120.0 + it, 1.2, 40.0, 40.0, isFastingTime = true)
        }

        val snapshot = learner.statusSnapshot()
        val expectedCombined = (
            snapshot.shortTermMultiplier * 0.40 +
                snapshot.mediumTermMultiplier * 0.35 +
                snapshot.longTermMultiplier * 0.25
            ).coerceIn(0.70, 2.0)
        assertEquals(3, snapshot.shortBufferCount)
        assertEquals(3, snapshot.mediumBufferCount)
        assertEquals(1L, snapshot.shortUpdateCount)
        assertEquals(expectedCombined, snapshot.combinedMultiplier, 0.0)
    }
}
