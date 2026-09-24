package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parser never trusts the model, and it never throws. Every case here checks that an unusable
 * field becomes 1.0 plus a note in `parseIssues`, never an exception and never a value the loop
 * could act on directly.
 */
class AuditorProfileFactorParserTest {

    private val goodAnswer = """
        {
          "isfFactor": 0.85,
          "targetFactor": 0.85,
          "reasonCode": "RESISTANCE_UNDER_CORRECTION",
          "confidence": 0.80,
          "competingHypothesis": "none",
          "claims": {
            "bg_start_mgdl": 158.7, "bg_end_mgdl": 173.6, "bg_min_mgdl": 158.7,
            "iob_start_u": 11.19, "iob_end_u": 10.37, "insulin_delivered_u": 1.58
          },
          "rationale": "Glucose kept rising while insulin was absorbed."
        }
    """.trimIndent()

    @Test
    fun `the good answer parses to the same numbers`() {
        val out = AuditorProfileFactorParser.parse(goodAnswer)
        assertNull(out.failure)
        assertTrue(out.parseIssues.isEmpty())
        assertEquals(0.85, out.isfFactorRaw, 1e-9)
        assertEquals(0.85, out.targetFactorRaw, 1e-9)
        assertEquals(ProfileFactorReasonCode.RESISTANCE_UNDER_CORRECTION, out.reasonCode)
        assertEquals(0.80, out.confidence, 1e-9)
        assertEquals("none", out.competingHypothesis)
        assertEquals(158.7, out.claims.bgStartMgdl!!, 1e-9)
        assertEquals(10.37, out.claims.iobEndU!!, 1e-9)
    }

    @Test
    fun `wrapped in a code fence with text around still parses`() {
        val fenced = "Here is my answer:\n```json\n$goodAnswer\n```\nThank you."
        val out = AuditorProfileFactorParser.parse(fenced)
        assertNull(out.failure)
        assertEquals(0.85, out.isfFactorRaw, 1e-9)
    }

    @Test
    fun `an unusable isfFactor becomes 1 point 0 with an issue`() {
        val cases = listOf(
            """{"isfFactor": "abc", "targetFactor": 1.0}""",
            """{"isfFactor": null, "targetFactor": 1.0}""",
            """{"targetFactor": 1.0}""",
            """{"isfFactor": "NaN", "targetFactor": 1.0}""",
            """{"isfFactor": 1e999, "targetFactor": 1.0}""",
            """{"isfFactor": 0, "targetFactor": 1.0}""",
            """{"isfFactor": -0.9, "targetFactor": 1.0}""",
        )
        cases.forEach { text ->
            val out = AuditorProfileFactorParser.parse(text)
            assertEquals(1.0, out.isfFactorRaw, 1e-9) { "case: $text" }
            // Two safe endings, and the body decides which one. A readable body with an unusable
            // factor gives the neutral 1.0 plus the issue. A body the JSON reader refuses outright
            // (`1e999` overflows it) never reaches the factors and gives a failure instead. The
            // validator refuses on either signal, so both are acceptable here - what must never
            // happen is a usable-looking factor coming out of an unusable body.
            val refused = out.parseIssues.contains("isfFactor_invalid") || out.failure != null
            assertTrue(refused) { "case: $text" }
        }
    }

    @Test
    fun `a numeric string factor is read as a number`() {
        val out = AuditorProfileFactorParser.parse("""{"isfFactor": 1.0, "targetFactor": "0.9"}""")
        assertEquals(0.9, out.targetFactorRaw, 1e-9)
        assertTrue(out.parseIssues.isEmpty())
    }

    @Test
    fun `an unknown reason code becomes NONE`() {
        val out = AuditorProfileFactorParser.parse(
            """{"isfFactor": 1.0, "targetFactor": 1.0, "reasonCode": "MORE_INSULIN"}""",
        )
        assertEquals(ProfileFactorReasonCode.NONE, out.reasonCode)
    }

    @Test
    fun `confidence out of the 0 to 1 range is clamped, a non-numeric one is 0`() {
        val over = AuditorProfileFactorParser.parse("""{"isfFactor": 1.0, "targetFactor": 1.0, "confidence": 7}""")
        assertEquals(1.0, over.confidence, 1e-9)
        val words = AuditorProfileFactorParser.parse("""{"isfFactor": 1.0, "targetFactor": 1.0, "confidence": "high"}""")
        assertEquals(0.0, words.confidence, 1e-9)
    }

    @Test
    fun `text that is not JSON fails the parse, both factors 1 point 0`() {
        val out = AuditorProfileFactorParser.parse("I think ISF should be 40.")
        assertEquals("parse", out.failure)
        assertEquals(1.0, out.isfFactorRaw, 1e-9)
        assertEquals(1.0, out.targetFactorRaw, 1e-9)
    }

    @Test
    fun `a rationale longer than 240 characters is cut`() {
        val long = "x".repeat(500)
        val out = AuditorProfileFactorParser.parse(
            """{"isfFactor": 1.0, "targetFactor": 1.0, "rationale": "$long"}""",
        )
        assertEquals(240, out.rationale.length)
    }
}
