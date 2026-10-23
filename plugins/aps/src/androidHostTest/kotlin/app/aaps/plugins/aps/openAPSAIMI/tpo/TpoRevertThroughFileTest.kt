package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole revert, through the real file the session lives in.
 *
 * The other revert tests give the manager a document built in memory, so they never see what JSON
 * does to the values on the way to the file and back. That is exactly where the user's value was
 * lost: a baseline of 1.0 was stored as `1`, read back as an `Int`, and refused by the writer without
 * a word. Field report 2026-09-18 - `max_smb` never came back from the bottom rung.
 *
 * Port of `TpoRevertThroughFileTest` from `dev_OAPSAIMI` @ `acdc2b118e`. The file goes through
 * [InMemoryAimiStorage] instead of a temporary directory. The test stays in `androidHostTest` because
 * it mocks [Preferences], like [TpoSessionManagerRevertTest].
 *
 * The second dev case checked that org.json writes 1.0 as `1`. kotlinx serialization writes `1.0`,
 * so that fact does not hold here; instead the test writes the old org.json form by hand, because a
 * session file left by an older Android build still looks like that.
 */
class TpoRevertThroughFileTest : TestBase() {

    private val key = DoubleKey.OApsAIMIMaxSMB
    private val sessionPath = "/aimi/tpo/tpo_session.json"

    private fun session(baseline: Double, overlay: Double) = TpoSessionDocument(
        sessionId = "through-the-file",
        packId = TpoPackId.POST_HYPO_RECOVERY,
        tier = TuningStepTier.MODERATE,
        status = TpoSessionStatus.ACTIVE,
        startedAtMs = 0L,
        expiresAtMs = 45L * 60L * 1000L,
        triggerAlgoConfidence = 0.80,
        triggerReasonCodes = listOf("post_hypo"),
        baseline = mapOf(key.key to baseline),
        overlay = mapOf(key.key to overlay),
    )

    @Test
    fun wholeNumberBaselineSurvivesTheFileAndIsPutBack() {
        val storage = InMemoryAimiStorage()
        val persistence = TpoPersistence(storage)
        // The user's own value, and the bottom rung the session wrote over it.
        persistence.saveSession(session(baseline = 1.0, overlay = 0.80))
        assertNotNull(storage.files[sessionPath])
        val preferences = mock<Preferences>()
        whenever(preferences.get(key)).thenReturn(0.80)
        val manager = TpoSessionManager(persistence)

        assertTrue(manager.revertNow(preferences, historyRepo = null, nowMs = 1_000L))

        verify(preferences).put(key, 1.0)
        // The session file is gone, so the next session starts from the user's value again.
        assertNull(persistence.loadSession())
    }

    @Test
    fun integerLiteralWrittenByOrgJsonIsStillPutBack() {
        val storage = InMemoryAimiStorage()
        val persistence = TpoPersistence(storage)
        // The org.json form: a whole double with no decimal point.
        storage.files[sessionPath] = """
            {
              "pack_id": "${TpoPackId.POST_HYPO_RECOVERY.name}",
              "session_id": "old-android-file",
              "status": "ACTIVE",
              "started_at_ms": 0,
              "expires_at_ms": 2700000,
              "baseline": { "${key.key}": 1 },
              "overlay": { "${key.key}": 0.8 }
            }
        """.trimIndent()
        val preferences = mock<Preferences>()
        whenever(preferences.get(key)).thenReturn(0.80)
        val manager = TpoSessionManager(persistence)

        assertTrue(manager.revertNow(preferences, historyRepo = null, nowMs = 1_000L))

        verify(preferences).put(key, 1.0)
        assertNull(persistence.loadSession())
    }
}
