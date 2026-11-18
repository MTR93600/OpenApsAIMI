package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The manual Boost started through the orchestrator, against a protective session that runs. The Boost
 * must end that session first and be built from the user's own values, not from the lowered ones.
 */
class TpoOrchestratorBoostTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun preferences() = MapPreferences().apply {
        doubles[DoubleKey.OApsAIMIHighBGMaxSMB] = 2.0
        doubles[DoubleKey.ApsMaxBasal] = 3.0
        doubles[DoubleKey.autodriveMaxBasal] = 2.5
        // No notification text is built in this test
        booleans[BooleanKey.OApsAIMITpoNotifyOnApply] = false
    }

    private fun orchestrator(preferences: MapPreferences, storage: InMemoryAimiStorage) =
        TpoOrchestrator(preferences, storage, mock(), mock(), mock(), mock(), mock())

    /** A post-hypo protection that lowers max SMB in hyper from 2.0 to 1.0. */
    private fun startProtection(preferences: MapPreferences, storage: InMemoryAimiStorage) {
        val plan = TpoApplyPlan(
            proposal = TpoProposal(TpoPackId.POST_HYPO_RECOVERY, TuningStepTier.MICRO, algoConfidence = 0.9, reasonCodes = listOf("test")),
            changes = listOf(TuningChange(DoubleKey.OApsAIMIHighBGMaxSMB, DoubleKey.OApsAIMIHighBGMaxSMB.key, 2.0, 1.0, "test", TuningStepTier.MICRO)),
        )
        TpoSessionManager(TpoPersistence(storage)).startSession(plan, preferences, now, llmResult = null, historyRepo = null)
        assertEquals(1.0, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
    }

    @Test
    fun `a boost replaces a running protective session and starts from the user values`() {
        val preferences = preferences()
        val storage = InMemoryAimiStorage()
        startProtection(preferences, storage)
        val tpo = orchestrator(preferences, storage)

        val boost = tpo.startManualBoost(now + minute) { TpoBoostPlan.build(preferences, hardMaxBasal = 10.0) }

        assertEquals(TpoPackId.MANUAL_BOOST, boost?.packId)
        assertEquals(TpoPackId.MANUAL_BOOST, tpo.currentSession()?.packId)
        // 2.0 x 1.3 from the user's value. Built on the lowered 1.0 it would be 1.3.
        assertEquals(2.6, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
        assertEquals(3.9, preferences.get(DoubleKey.ApsMaxBasal), 1e-9)
    }

    @Test
    fun `when the boost ends the user values come back and not the protective ones`() {
        val preferences = preferences()
        val storage = InMemoryAimiStorage()
        startProtection(preferences, storage)
        val tpo = orchestrator(preferences, storage)
        tpo.startManualBoost(now + minute) { TpoBoostPlan.build(preferences, hardMaxBasal = 10.0) }

        tpo.revertNow()

        assertEquals(2.0, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
        assertEquals(3.0, preferences.get(DoubleKey.ApsMaxBasal), 1e-9)
    }

    @Test
    fun `a second boost is not started over a running one`() {
        val preferences = preferences()
        val tpo = orchestrator(preferences, InMemoryAimiStorage())
        tpo.startManualBoost(now) { TpoBoostPlan.build(preferences, hardMaxBasal = 10.0) }

        assertNull(tpo.startManualBoost(now + minute) { TpoBoostPlan.build(preferences, hardMaxBasal = 10.0) })
        assertEquals(2.6, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
    }
}
