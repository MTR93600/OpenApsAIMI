package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A whole revert, through the real file the session lives in.
 *
 * The existing revert tests hand the manager a document built in memory, so they never see what JSON
 * does to the values on the way to disk and back. That is exactly where the user's value was lost: a
 * baseline of 1.0 was stored as `1`, read back as an `Int`, and refused by the writer without a word.
 * Field report 2026-09-18 — `max_smb` never came back from the bottom rung.
 */
class TpoRevertThroughFileTest {

    private val key = DoubleKey.OApsAIMIMaxSMB

    private fun persistenceIn(dir: File): TpoPersistence {
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any(), any()) } answers {
            val subdir = File(dir, firstArg<String>()).apply { mkdirs() }
            File(subdir, secondArg<String>())
        }
        every { storage.saveFileSafe(any(), any()) } answers {
            firstArg<File>().writeText(secondArg())
            true
        }
        return TpoPersistence(storage)
    }

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
    fun `a whole-number baseline survives the file and is put back`(@TempDir dir: File) {
        val persistence = persistenceIn(dir)
        // The user's own value, and the bottom rung the session wrote over it.
        persistence.saveSession(session(baseline = 1.0, overlay = 0.80))
        val preferences = mockk<Preferences>(relaxed = true)
        every { preferences.get(key) } returns 0.80
        val manager = TpoSessionManager(persistence)

        assertThat(manager.revertNow(preferences, historyRepo = null, nowMs = 1_000L)).isTrue()

        val written = slot<Double>()
        verify { preferences.put(key, capture(written)) }
        assertThat(written.captured).isWithin(0.0001).of(1.0)
        // The session file is gone, so the next session starts from the user's value again.
        assertThat(persistence.loadSession()).isNull()
    }

    @Test
    fun `the value really is written without a decimal point on disk`(@TempDir dir: File) {
        // Not a behaviour we want, just the fact the fix has to live with: this is what makes the
        // normalisation on read necessary rather than optional.
        val persistence = persistenceIn(dir)
        persistence.saveSession(session(baseline = 1.0, overlay = 0.80))

        val text = File(File(dir, "tpo"), "tpo_session.json").readText()

        // No decimal point: org.json writes a whole double as an integer literal, and reads it back
        // as an Int. That is the whole reason the baseline used to be lost.
        assertThat(text).contains("\"${key.key}\": 1")
        assertThat(text).doesNotContain("\"${key.key}\": 1.0")
        assertThat(persistence.loadSession()!!.baseline[key.key]).isEqualTo(1.0)
    }
}
