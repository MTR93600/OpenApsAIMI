package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A Boost writes its three ceilings for 60 minutes, gives them back, and no automatic pack can supersede it. */
class TpoBoostLifecycleTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun preferences() = MapPreferences().apply {
        doubles[DoubleKey.OApsAIMIHighBGMaxSMB] = 2.0
        doubles[DoubleKey.ApsMaxBasal] = 3.0
        doubles[DoubleKey.autodriveMaxBasal] = 2.5
    }

    private fun startBoost(preferences: MapPreferences): Pair<TpoSessionManager, TpoSessionDocument> {
        val manager = TpoSessionManager(TpoPersistence(InMemoryAimiStorage()))
        val plan = TpoBoostPlan.build(preferences, hardMaxBasal = 10.0)
        val session = manager.startSession(plan, preferences, now, llmResult = null, historyRepo = null, ttlMs = TpoBoostPlan.TTL_MS)
        return manager to session
    }

    @Test
    fun start_writes_the_three_ceilings_for_60_minutes() {
        val preferences = preferences()
        val (_, session) = startBoost(preferences)

        assertEquals(TpoPackId.MANUAL_BOOST, session.packId)
        assertEquals(now + 60 * minute, session.expiresAtMs)
        assertEquals(2.6, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
        assertEquals(3.9, preferences.get(DoubleKey.ApsMaxBasal), 1e-9)
        assertEquals(3.25, preferences.get(DoubleKey.autodriveMaxBasal), 1e-9)
    }

    @Test
    fun the_user_values_come_back_at_the_end() {
        val preferences = preferences()
        val (manager, _) = startBoost(preferences)

        manager.expireIfNeeded(now + 59 * minute, preferences, historyRepo = null)
        assertEquals(3.9, preferences.get(DoubleKey.ApsMaxBasal), 1e-9) // still running

        manager.expireIfNeeded(now + 61 * minute, preferences, historyRepo = null)
        assertEquals(2.0, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
        assertEquals(3.0, preferences.get(DoubleKey.ApsMaxBasal), 1e-9)
        assertEquals(2.5, preferences.get(DoubleKey.autodriveMaxBasal), 1e-9)
    }

    @Test
    fun cancel_gives_the_values_back_at_once() {
        val preferences = preferences()
        val (manager, _) = startBoost(preferences)

        manager.revertNow(preferences, historyRepo = null, nowMs = now + 5 * minute)

        assertEquals(2.0, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), 1e-9)
        assertEquals(3.0, preferences.get(DoubleKey.ApsMaxBasal), 1e-9)
    }

    @Test
    fun no_protective_pack_supersedes_a_running_boost() {
        // The highest priority: even a clear post-hypo signal proposes nothing over a Boost. Only a real
        // low (TpoBoostEligibility.shouldEndForLow) ends it.
        val input = TpoTickInput(
            nowMs = now,
            bgMgdl = 75.0,
            deltaMgdl5m = 1.0,
            cobGrams = 0.0,
            minBgLookback75m = 62.0,
            mealProb = 0.0,
            sleepDebtScore = 0.0,
            thermalRecoveryBurden = 0.0,
            postHypoReboundProb = 0.9,
            patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
            patientModeConfidence = 0.9,
            causalDominantName = "UNKNOWN",
            causalDominantConfidence = 0.0,
            eventMemory = PatientEventMemory.EMPTY,
            reboundGuardActive = true,
            dawnEndogenousDrive = 0.0,
        )
        // Without a Boost the same tick proposes the protection, so the signal itself is strong enough
        assertEquals(TpoPackId.POST_HYPO_RECOVERY, assertNotNull(TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), null, emptyMap()).proposal).packId)

        assertNull(TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), TpoPackId.MANUAL_BOOST, emptyMap()).proposal)
    }
}
