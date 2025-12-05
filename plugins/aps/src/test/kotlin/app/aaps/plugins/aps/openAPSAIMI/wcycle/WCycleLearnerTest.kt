package app.aaps.plugins.aps.openAPSAIMI.wcycle

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/*
class WCycleLearnerTest {

    private val context: Context = mockk(relaxed = true)
    // We need to mock file operations or use a temp dir.
    // Since WCycleLearner creates File internally using context.getExternalFilesDir,
    // we can mock that to return a temp dir.
    
    @Test
    fun `test learnedMultipliers default`() {
        val tempDir = createTempDir()
        every { context.getExternalFilesDir(null) } returns tempDir
        
        val learner = WCycleLearner(context)
        val (b, s) = learner.learnedMultipliers(CyclePhase.MENSTRUATION, 0.5, 1.5)
        assertEquals(1.0, b, 0.0)
        assertEquals(1.0, s, 0.0)
        
        tempDir.deleteRecursively()
    }

    @Test
    fun `test update`() {
        val tempDir = createTempDir()
        every { context.getExternalFilesDir(null) } returns tempDir
        
        val learner = WCycleLearner(context)
        // Update with 1.2. Alpha 0.1. Prev 1.0.
        // New = 0.9 * 1.0 + 0.1 * 1.2 = 0.9 + 0.12 = 1.02
        learner.update(CyclePhase.MENSTRUATION, 1.2, 1.2)
        
        val (b, s) = learner.learnedMultipliers(CyclePhase.MENSTRUATION, 0.5, 1.5)
        assertEquals(1.02, b, 0.001)
        assertEquals(1.02, s, 0.001)
        
        tempDir.deleteRecursively()
    }
}
*/
