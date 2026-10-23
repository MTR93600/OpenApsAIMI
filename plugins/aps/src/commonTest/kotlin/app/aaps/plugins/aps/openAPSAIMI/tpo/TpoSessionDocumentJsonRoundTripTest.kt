package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a session looks like after it has been through the file it lives in.
 *
 * Every revert reads `tpo/tpo_session.json` again, so the types the revert works on are the types
 * JSON gives back, not the types the session was built with. A whole number can be written without
 * a decimal point and come back as an `Int` - and the revert path only accepted a `Double`. Field
 * report 2026-09-18: `max_smb` 1.0 and `high_bg_max_smb` 2.0 never came back.
 *
 * Port of `TpoSessionDocumentJsonRoundTripTest` from `dev_OAPSAIMI` @ `acdc2b118e`. `org.json` is
 * replaced by kotlinx serialization, so the test runs on every target. One case is added: a file
 * written by the Android build with `org.json` holds `1`, not `1.0`, and must still read back as a
 * `Double`.
 */
class TpoSessionDocumentJsonRoundTripTest {

    private val maxSmb = "key_openapsaimi_max_smb"
    private val highBgMaxSmb = "key_openapsaimi_high_bg_max_smb"

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

    private fun parse(text: String): TpoSessionDocument {
        val back = TpoSessionDocument.fromJsonObject(Json.parseToJsonElement(text).jsonObject)
        assertNotNull(back)
        return back
    }

    private fun roundTrip(doc: TpoSessionDocument): TpoSessionDocument = parse(doc.toJsonObject().toString())

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 0.0001, "expected $expected but was $actual")
    }

    @Test
    fun wholeNumberBaselineComesBackAsDouble() {
        val doc = document(baseline = mapOf(maxSmb to 1.0), overlay = mapOf(maxSmb to 0.8))

        val baseline = roundTrip(doc).baseline[maxSmb]

        // The value must survive as a Double worth 1.0 - this is what the revert writes back.
        assertNear(1.0, assertIs<Double>(baseline))
    }

    @Test
    fun wholeNumberOverlayStillMatchesTheLivePreferenceValue() {
        // The gate compares the live preference (always a Double) with what the session wrote.
        val doc = document(baseline = mapOf(highBgMaxSmb to 1.25), overlay = mapOf(highBgMaxSmb to 1.0))

        val overlay = roundTrip(doc).overlay[highBgMaxSmb]
        assertNotNull(overlay)

        assertTrue(TpoRevertPolicy.sameValue(1.0, overlay))
        assertTrue(TpoRevertPolicy.shouldRestore(liveValue = 1.0, overlayValue = overlay))
    }

    @Test
    fun valueWithDecimalsIsUnaffected() {
        val doc = document(baseline = mapOf(maxSmb to 2.2), overlay = mapOf(maxSmb to 1.25))

        val back = roundTrip(doc)

        assertNear(2.2, assertIs<Double>(back.baseline[maxSmb]))
        assertTrue(TpoRevertPolicy.shouldRestore(1.25, back.overlay[maxSmb]))
    }

    @Test
    fun integerLiteralInTheFileIsReadAsDouble() {
        // What org.json writes for 1.0 and 2.0: no decimal point. A session file left by an older
        // Android build looks like this, and its baseline must still be put back.
        val text = """
            {
              "pack_id": "${TpoPackId.entries.first().name}",
              "session_id": "old-file",
              "status": "ACTIVE",
              "started_at_ms": 1000,
              "expires_at_ms": 2000,
              "baseline": { "$maxSmb": 1, "$highBgMaxSmb": 2 },
              "overlay": { "$maxSmb": 0.8, "$highBgMaxSmb": 1.25 }
            }
        """.trimIndent()

        val back = parse(text)

        assertNear(1.0, assertIs<Double>(back.baseline[maxSmb]))
        assertNear(2.0, assertIs<Double>(back.baseline[highBgMaxSmb]))
    }
}
