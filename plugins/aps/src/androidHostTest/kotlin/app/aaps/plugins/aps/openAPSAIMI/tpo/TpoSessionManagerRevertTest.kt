package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

/**
 * What a finished time-period override does to the preferences it changed.
 *
 * The key under test is the one the user watched move on its own: the high-glucose SMB ceiling.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `f3de6740ee` (file unchanged on tip `a546722609`).
 * Study source set: [TpoSessionManager] is androidMain (UUID + [AdvisorHistoryRepository]), so this
 * lives in `androidHostTest` + mockito (not mockk). Persistence is mocked; the revert path does
 * not need a real file. Names are camelCase (no backtick commas).
 */
class TpoSessionManagerRevertTest : TestBase() {

    private val key = DoubleKey.OApsAIMIHighBGMaxSMB

    private fun session(
        baseline: Double,
        overlay: Double,
        userOwnedKeys: Set<String> = emptySet(),
    ) = TpoSessionDocument(
        sessionId = "session-under-test",
        packId = TpoPackId.POST_HYPO_RECOVERY,
        tier = TuningStepTier.MODERATE,
        status = TpoSessionStatus.ACTIVE,
        startedAtMs = 0L,
        expiresAtMs = 45L * 60L * 1000L,
        triggerAlgoConfidence = 0.80,
        triggerReasonCodes = listOf("post_hypo"),
        baseline = mapOf(key.key to baseline),
        overlay = mapOf(key.key to overlay),
        userOwnedKeys = userOwnedKeys,
    )

    private fun managerFor(doc: TpoSessionDocument, liveValue: Double): Pair<TpoSessionManager, Preferences> {
        val persistence = mock<TpoPersistence>()
        val preferences = mock<Preferences>()
        whenever(persistence.loadSession()).thenReturn(doc)
        whenever(persistence.loadLastRevertAtMsByPack()).thenReturn(emptyMap())
        whenever(preferences.get(key)).thenReturn(liveValue)
        return TpoSessionManager(persistence) to preferences
    }

    @Test
    fun aValueStillEqualToWhatTheSessionWroteIsPutBack() {
        val (manager, preferences) = managerFor(session(baseline = 2.0, overlay = 1.25), liveValue = 1.25)

        manager.revertNow(preferences, historyRepo = null, nowMs = 100L)

        verify(preferences).put(key, 2.0)
    }

    @Test
    fun aValueSomebodyElseHasMovedIsLeftAlone() {
        val (manager, preferences) = managerFor(session(baseline = 2.0, overlay = 1.25), liveValue = 1.60)

        manager.revertNow(preferences, historyRepo = null, nowMs = 100L)

        verify(preferences, never()).put(key, 2.0)
    }

    /**
     * The bug. `userOwnedKeys` was sticky: one tick where the read differed from the overlay marked
     * the key for the rest of the session, and the revert then skipped it for good — so 1.25 stayed
     * in the preferences and the user's 2.0 was lost. The value is back at what the session wrote, so
     * it must be put back whatever was observed earlier.
     */
    @Test
    fun aKeyMarkedEarlierInTheSessionIsStillPutBackWhenTheValueIsTheSessionsOwn() {
        val (manager, preferences) = managerFor(
            session(baseline = 2.0, overlay = 1.25, userOwnedKeys = setOf(key.key)),
            liveValue = 1.25,
        )

        manager.revertNow(preferences, historyRepo = null, nowMs = 100L)

        verify(preferences).put(key, 2.0)
    }

    @Test
    fun anExpiredSessionPutsTheValueBackToo() {
        val doc = session(baseline = 2.0, overlay = 1.25)
        val persistence = mock<TpoPersistence>()
        val preferences = mock<Preferences>()
        whenever(persistence.loadSession()).thenReturn(doc)
        whenever(persistence.loadLastRevertAtMsByPack()).thenReturn(emptyMap())
        whenever(preferences.get(key)).thenReturn(1.25)

        val expired = TpoSessionManager(persistence)
            .expireIfNeeded(nowMs = doc.expiresAtMs + 1L, preferences = preferences, historyRepo = null)

        assertTrue(expired)
        verify(preferences).put(key, 2.0)
    }
}
