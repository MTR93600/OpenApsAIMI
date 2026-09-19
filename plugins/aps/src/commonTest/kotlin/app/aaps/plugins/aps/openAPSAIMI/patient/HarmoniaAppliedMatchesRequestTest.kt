package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mode = APPLIED` only says the tick ended with a positive pump rate. It never said the pump ran
 * Harmonia's number: on the night of 2026-09-05 all 18 APPLIED ticks were more than 0.05 U/h away
 * from the asked rate, so the real follow rate was 0 %, not 2.7 %.
 *
 * `applied_matches_request` is the field that answers that question. It reads observation only:
 * [HarmoniaProductionDecision.mode] and the runtime state around it are untouched.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `db21308e6c` (unchanged on tip `c5db5a0333`).
 * Study source set: [HarmoniaProductionDecision] is commonMain and uses kotlinx JSON, so the tests
 * live in `commonTest` (`kotlin.test`). Not `org.json`.
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

        assertEquals(false, d.appliedMatchesRequest)
        assertFalse(d.toJsonObject().getValue("applied_matches_request").jsonPrimitive.boolean)
        // The mode is deliberately left alone: it still says APPLIED.
        assertEquals("APPLIED", d.toJsonObject().getValue("mode").jsonPrimitive.content)
        assertTrue(d.toJsonObject().getValue("applies_to_pump").jsonPrimitive.boolean)
    }

    @Test
    fun `a pump rate equal to the asked rate is reported as followed`() {
        val d = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.85)

        assertEquals(true, d.appliedMatchesRequest)
        assertTrue(d.toJsonObject().getValue("applied_matches_request").jsonPrimitive.boolean)
    }

    @Test
    fun `a gap just inside the tolerance still counts as followed`() {
        val inside = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.89)
        val outside = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = 0.85, appliedRateUph = 0.95)

        assertEquals(true, inside.appliedMatchesRequest)
        assertEquals(false, outside.appliedMatchesRequest)
        assertEquals(0.05, HarmoniaProductionDecision.APPLIED_MATCH_TOLERANCE_UPH, 1e-9)
    }

    @Test
    fun `a missing rate is unknown and never a no`() {
        val noApplied = decision(HarmoniaProductionMode.READY, boundedRateUph = 0.85, appliedRateUph = null)
        val noBounded = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = null, appliedRateUph = 0.85)
        val neither = decision(HarmoniaProductionMode.SKIPPED, boundedRateUph = null, appliedRateUph = null)

        assertNull(noApplied.appliedMatchesRequest)
        assertNull(noBounded.appliedMatchesRequest)
        assertNull(neither.appliedMatchesRequest)
        assertEquals(JsonNull, noApplied.toJsonObject()["applied_matches_request"])
    }

    @Test
    fun `a non-finite rate is unknown`() {
        val d = decision(HarmoniaProductionMode.APPLIED, boundedRateUph = Double.NaN, appliedRateUph = 0.85)

        assertNull(d.appliedMatchesRequest)
    }
}
