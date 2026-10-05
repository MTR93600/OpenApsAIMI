package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoDeltaBuilder
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPackId
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoProposal
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionManager
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session and the preference overlay survive a new process reading the same directory.
 * The names are Android's: `tpo/tpo_session.json` and `tpo/aimi_preferences.json`.
 */
class TpoSessionFileParityTest {

    @Test
    fun aFreshDirectoryKeepsTheActiveCeilingAtPointEight() {
        val root = File(System.getProperty("java.io.tmpdir"), "aimi-tpo-${System.nanoTime()}")
        try {
            val storage = DirectoryAimiStorage(root.absolutePath)
            val prefs = JsonBackedPreferences(storage)
            val nowMs = 1_700_000_000_000L
            val plan = TpoDeltaBuilder.buildPlan(
                proposal = TpoProposal(
                    packId = TpoPackId.POST_HYPO_RECOVERY,
                    tier = TuningStepTier.MICRO,
                    algoConfidence = 0.90,
                    reasonCodes = listOf("post_hypo"),
                ),
                preferences = prefs,
                hypoLoad = 0.0,
                t3cBrittle = false,
            )
            TpoSessionManager(TpoPersistence(storage)).startSession(
                plan = plan,
                preferences = prefs,
                nowMs = nowMs,
                llmResult = null,
                historyRepo = null,
            )
            assertTrue(File(root, "tpo/tpo_session.json").isFile)
            assertTrue(File(root, "tpo/aimi_preferences.json").isFile)

            val reloaded = JsonBackedPreferences(DirectoryAimiStorage(root.absolutePath))
            val ceiling = decideTpoSessionAtTickStart(nowMs + 60_000L, reloaded, TpoPersistence(storage))
            assertEquals(0.80, ceiling.maxSmb, 1e-9)
            assertEquals(TpoSessionStatus.ACTIVE, TpoPersistence(DirectoryAimiStorage(root.absolutePath)).loadSession()?.status)
            assertEquals(0.80, reloaded.get(DoubleKey.OApsAIMIMaxSMB), 1e-9)
        } finally {
            root.deleteRecursively()
        }
    }
}
