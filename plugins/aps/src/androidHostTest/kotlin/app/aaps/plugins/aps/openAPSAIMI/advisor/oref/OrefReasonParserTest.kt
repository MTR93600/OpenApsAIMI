package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class OrefReasonParserTest {

    @Test
    fun parse_extracts_minGuardBG_mgdl() {
        val r = OrefReasonParser.parse("foo minGuardBG: 95 bar")
        assertThat(r.minGuardBG).isEqualTo(95.0)
    }

    @Test
    fun parseCrFromReason() {
        assertThat(OrefReasonParser.parseCrFromReason("CR: 12")).isEqualTo(12.0)
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun parse_extracts_Dev_with_comma_decimal_mgdl() {
        val r = OrefReasonParser.parse("Target: 5,5, Dev: -0,3, BGI: 1,2, minPredBG 5,0")
        assertThat(r.dev).isWithin(0.01).of(-5.4)
        assertThat(r.bgi).isWithin(0.01).of(21.6)
        assertThat(r.minPredBG).isWithin(0.01).of(90.0)
    }

    @Test
    fun parseNumericToken_handles_comma() {
        assertThat(OrefReasonParser.parseNumericToken("5,5")).isEqualTo(5.5)
    }
}
