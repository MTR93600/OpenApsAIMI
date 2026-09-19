package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * What a session looks like after it has been through the file it lives in.
 *
 * Every revert re-reads `tpo/tpo_session.json`, so the types the revert works on are the types JSON
 * gives back, not the types the session was built with. A `Double` with nothing after the point is
 * written without a decimal point and comes back as an `Int` — and the revert path only ever accepts
 * a `Double`. Field report 2026-09-18: `max_smb` 1.0 and `high_bg_max_smb` 2.0 never came back.
 */
class TpoSessionDocumentJsonRoundTripTest {

    private fun document(baseline: Map<String, Any>, overlay: Map<String, Any>) = TpoSessionDocument(
        sessionId = "s1",
        packId = TpoPackId.entries.first(),
        tier = TuningStepTier.MODERATE,
        status = TpoSessionStatus.ACTIVE,
        startedAtMs = 1_000L,
        expiresAtMs = 2_000L,
        triggerAlgoConfidence = 0.5,
        triggerReasonCodes = listOf("test"),
        baseline = baseline,
        overlay = overlay,
    )

    private fun roundTrip(doc: TpoSessionDocument): TpoSessionDocument =
        TpoSessionDocument.fromJsonObject(JSONObject(doc.toJsonObject().toString()))!!

    @Test
    fun `a whole-number baseline comes back as a whole number, not as a truncated type`() {
        val doc = document(baseline = mapOf("key_openapsaimi_max_smb" to 1.0), overlay = mapOf("key_openapsaimi_max_smb" to 0.8))

        val back = roundTrip(doc)

        // The value must survive as a number worth 1.0 — this is what the revert writes back.
        val baseline = back.baseline["key_openapsaimi_max_smb"]
        assertThat(baseline).isInstanceOf(java.lang.Double::class.java)
        assertThat((baseline as Double)).isWithin(0.0001).of(1.0)
    }

    @Test
    fun `a whole-number overlay still matches the live preference value`() {
        // The gate compares the live preference (always a Double) with what the session wrote.
        val doc = document(baseline = mapOf("key_openapsaimi_high_bg_max_smb" to 1.25), overlay = mapOf("key_openapsaimi_high_bg_max_smb" to 1.0))

        val back = roundTrip(doc)

        val overlay = back.overlay["key_openapsaimi_high_bg_max_smb"]!!
        assertThat(TpoRevertPolicy.sameValue(1.0, overlay)).isTrue()
        assertThat(TpoRevertPolicy.shouldRestore(liveValue = 1.0, overlayValue = overlay)).isTrue()
    }

    @Test
    fun `a value with decimals is unaffected`() {
        val doc = document(baseline = mapOf("key_openapsaimi_max_smb" to 2.2), overlay = mapOf("key_openapsaimi_max_smb" to 1.25))

        val back = roundTrip(doc)

        assertThat((back.baseline["key_openapsaimi_max_smb"] as Double)).isWithin(0.0001).of(2.2)
        assertThat(TpoRevertPolicy.shouldRestore(1.25, back.overlay["key_openapsaimi_max_smb"])).isTrue()
    }
}
