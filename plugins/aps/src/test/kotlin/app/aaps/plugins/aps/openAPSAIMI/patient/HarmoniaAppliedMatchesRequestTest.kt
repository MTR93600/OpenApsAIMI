package app.aaps.plugins.aps.openAPSAIMI.patient

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `mode = APPLIED` only says the tick ended with a positive pump rate. It never said the pump ran
 * Harmonia's number: on the night of 2026-09-05 all 18 APPLIED ticks were more than 0.05 U/h away
 * from the asked rate, so the real follow rate was 0 %, not 2.7 %.
 *
 * `applied_matches_request` is the field that answers that question. It reads observation only:
 * [HarmoniaProductionDecision.mode] and the runtime state around it are untouched.
 */
class HarmoniaAppliedMatchesRequestTest {

    private fun decision(
        mode: HarmoniaProductionMode,
        boundedRateUph: Double?,
        appliedRateUph: Double?,
    ) = HarmoniaProductionDecision(
        timestampMs = 1_757_120_280_000L,
        mode = mode,
        selectedForProduction = mode == HarmoniaProductionMode.APPLIED,
        requestedRateUph = boundedRateUph,
        boundedRateUph = boundedRateUph,
        appliedRateUph = appliedRateUph,
        appliedDurationMin = 30,
        runtimeBlocker = null,
        safetyBlockers = emptyList(),
        sourceAction = HarmoniaAction.BASAL_FIRST,
        branch = "basal_first",
        reason = "harmonia_basal_first_applied",
    )

    @Test
    fun `the 0258 tick is reported as not followed`() {
        val d = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 3.14)

        assertThat(d.appliedMatchesRequest).isFalse()
        assertThat(d.toJsonObject().getBoolean("applied_matches_request")).isFalse()
        // The mode is deliberately left alone: it still says APPLIED.
        assertThat(d.toJsonObject().getString("mode")).isEqualTo("APPLIED")
        assertThat(d.toJsonObject().getBoolean("applies_to_pump")).isTrue()
    }

    @Test
    fun `a pump rate equal to the asked rate is reported as followed`() {
        val d = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.85)

        assertThat(d.appliedMatchesRequest).isTrue()
        assertThat(d.toJsonObject().getBoolean("applied_matches_request")).isTrue()
    }

    @Test
    fun `a gap just inside the tolerance still counts as followed`() {
        val inside = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.89)
        val outside = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.95)

        assertThat(inside.appliedMatchesRequest).isTrue()
        assertThat(outside.appliedMatchesRequest).isFalse()
        assertThat(HarmoniaProductionDecision.APPLIED_MATCH_TOLERANCE_UPH).isEqualTo(0.05)
    }

    @Test
    fun `a missing rate is unknown and never a no`() {
        val noApplied = decision(HarmoniaProductionMode.READY, boundedRateUph = 0.85, appliedRateUph = null)
        val noBounded = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = null, appliedRateUph = 0.85)
        val neither = decision(HarmoniaProductionMode.SKIPPED, boundedRateUph = null, appliedRateUph = null)

        assertThat(noApplied.appliedMatchesRequest).isNull()
        assertThat(noBounded.appliedMatchesRequest).isNull()
        assertThat(neither.appliedMatchesRequest).isNull()
        assertThat(noApplied.toJsonObject().isNull("applied_matches_request")).isTrue()
    }

    @Test
    fun `a non-finite rate is unknown`() {
        val d = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = Double.NaN, appliedRateUph = 0.85)

        assertThat(d.appliedMatchesRequest).isNull()
    }
}
