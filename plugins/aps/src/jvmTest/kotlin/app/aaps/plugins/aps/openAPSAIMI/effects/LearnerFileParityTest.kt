package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The learner files are the Android names, in the same directory the iOS tick already uses.
 * A missing file is multiplier 1.0 and governance WARMUP. A broken file logs the Android line
 * and stays at that default. A file written by the learner is what the next process reads.
 */
class LearnerFileParityTest {

    @Test
    fun emptyFilesStayWarmupAndASavedMultiplierReloads() {
        val root = File(System.getProperty("java.io.tmpdir"), "aimi-learners-${System.nanoTime()}")
        try {
            val storage = DirectoryAimiStorage(root.absolutePath)
            val log = LearnerColdLogger()
            val prefs = JsonBackedPreferences(storage)
            val lines = coldLearnerNightLines(
                storage = storage,
                preferences = prefs,
                persistence = LearnerColdPersistence(),
                dateUtil = LearnerColdClock(),
                log = log,
            )
            assertEquals(
                COLD_LEARNER_NIGHT_TRACE.lines().filterNot { it.startsWith("Storage:") },
                lines.filterNot { it.startsWith("Storage:") },
            )
            assertTrue(lines.any { it.startsWith("Storage: directory=") }, lines.toString())
            assertEquals(BasalNeuralLearner.GovernanceAction.WARMUP, governanceOf(storage, prefs, log).action)

            storage.writeText(storage.file("aimi_basal_learner.json"), "{")
            val brokenLog = LearnerColdLogger()
            val broken = BasalLearner(brokenLog, DirectoryAimiStorage(root.absolutePath))
            assertEquals(1.0, broken.getMultiplier(), 1e-9)
            assertTrue(
                brokenLog.failures.any { it.contains("Load failed, using defaults (multiplier=1.0)") },
                brokenLog.failures.toString(),
            )

            storage.writeText(
                storage.file("aimi_basal_learner.json"),
                """{"shortTermMultiplier":1.2,"mediumTermMultiplier":1.2,"longTermMultiplier":1.2}""",
            )
            val reloaded = BasalLearner(LearnerColdLogger(), DirectoryAimiStorage(root.absolutePath))
            assertEquals(1.2, reloaded.getMultiplier(), 1e-9)
            assertTrue(File(root, "aimi_basal_learner.json").isFile)

            storage.writeText(
                storage.file("aimi_unified_reactivity.json"),
                """{"globalFactor":1.5,"shortTermFactor":1.5}""",
            )
            val reactivity = UnifiedReactivityLearner(
                LearnerColdPersistence(),
                LearnerColdClock(),
                prefs,
                LearnerColdLogger(),
                DirectoryAimiStorage(root.absolutePath),
            )
            assertEquals(1.5, reactivity.getCombinedFactor(), 1e-9)

            storage.writeText(storage.file("basal_adaptive_weights.json"), "not-a-network")
            storage.writeText(storage.file("t3c_brain_weights.json"), "not-a-network")
            val neural = BasalNeuralLearner(prefs, DirectoryAimiStorage(root.absolutePath), LearnerColdLogger())
            neural.updateLearning(
                bgBefore = 120.0,
                bgAfter = 120.0,
                basalDelivered = 1.0,
                targetBg = 100.0,
                accel = 0.0,
                duraISFminutes = 0.0,
                duraISFaverage = 0.0,
                iob = 0.0,
            )
            val gov = neural.getGovernanceSnapshot()
            assertEquals(BasalNeuralLearner.GovernanceAction.WARMUP, gov.action)
            assertEquals("Warmup", gov.reason)
            assertTrue(File(root, "basal_adaptive_records.csv").isFile)
            assertTrue(File(root, "basal_adaptive_weights.json").isFile)
            assertTrue(File(root, "t3c_brain_weights.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun governanceOf(
        storage: DirectoryAimiStorage,
        prefs: JsonBackedPreferences,
        log: LearnerColdLogger,
    ) = BasalNeuralLearner(prefs, storage, log).getGovernanceSnapshot()
}
