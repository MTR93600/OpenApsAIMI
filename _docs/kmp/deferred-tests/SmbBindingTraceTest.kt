package app.aaps.plugins.aps.openAPSAIMI.quality

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbBindingTraceTest {

    @Test
    fun build_classifies_first_reduction_and_preserves_ordered_chain() {
        val trace = SmbBindingTrace.Draft(
            timestampMs = 1_721_000_000_000L,
            originOwner = "AutodriveV3",
            finalOwner = "AutodriveV3",
            mpcOutputU = 2.2,
            tier = "LARGE",
            smallPrebolusPrefU = 1.0,
            largePrebolusPrefU = 3.0,
            patternActive = "MEAL_FIRST_WAVE",
            patternCapU = 1.2,
        )
            .appendStage("HTR", 2.2, 2.2)
            .appendStage("PATTERN_CAP", 2.2, 1.2, 1.2)
            .appendStage("PKPD_THROTTLE", 1.2, 0.9)
            .build(finalU = 0.9)

        assertThat(trace.bindingStage).isEqualTo("PATTERN_CAP")
        assertThat(trace.stages.map { it.name })
            .containsExactly("HTR", "PATTERN_CAP", "PKPD_THROTTLE")
            .inOrder()
    }

    @Test
    fun build_does_not_classify_red_carpet_increase_as_binding() {
        val trace = SmbBindingTrace.Draft(
            timestampMs = 76_808_000L,
            originOwner = "AutodriveV3",
            finalOwner = "GlobalAIMI",
        )
            .appendStage("RED_CARPET", 0.23, 1.2, phase = "FINALIZE", kind = "RESTORE")
            .build(finalU = 1.2)

        assertThat(trace.bindingStage).isNull()
        assertThat(trace.stages.single().afterU).isGreaterThan(trace.stages.single().beforeU)
    }

    @Test
    fun skipped_duplicate_pkpd_is_observation_and_final_does_not_restore_from_zero() {
        val terminalU = 0.816
        val trace = SmbBindingTrace.Draft(
            timestampMs = 52_152_000L,
            originOwner = "AutodriveV3",
            finalOwner = "AutodriveV3",
            pkpdBeforeU = 1.2,
            pkpdAfterU = 1.02,
        )
            .appendStage(
                "PKPD_GUARD_SKIPPED_DUPLICATE",
                1.2,
                1.2,
                phase = "LEGACY_GUARD",
                kind = "OBSERVATION",
            )
            .appendStage("FINAL", terminalU, terminalU, phase = "EXPORT", kind = "OBSERVATION")
            .build(finalU = terminalU)

        assertThat(trace.bindingStage).isNull()
        assertThat(trace.pkpdBeforeU).isWithin(1e-9).of(1.2)
        assertThat(trace.pkpdAfterU).isWithin(1e-9).of(1.02)
        assertThat(trace.stages.first().kind).isEqualTo("OBSERVATION")
        assertThat(trace.stages.first().beforeU).isEqualTo(trace.stages.first().afterU)
        assertThat(trace.stages.last().beforeU).isWithin(1e-9).of(terminalU)
        assertThat(trace.stages.last().afterU).isWithin(1e-9).of(terminalU)
    }

    @Test
    fun toJsonObject_exports_binding_fields_and_full_stage_chain() {
        val json = SmbBindingTrace.Draft(
            timestampMs = 1_721_000_000_000L,
            originOwner = "AutodriveV3",
            finalOwner = "GlobalAIMI",
            modelOutputU = 0.8,
            mpcOutputU = 2.2,
            tier = "LARGE",
            smallPrebolusPrefU = 1.0,
            largePrebolusPrefU = 3.0,
            autodriveFloorU = 2.2,
            maxSmbU = 1.5,
            maxSmbHighBgU = 2.2,
            iobHeadroomU = 4.1,
            patternActive = "MEAL_UNDECLARED_FAST",
            patternCapU = 1.2,
            safetyNetBaseLimitU = 2.2,
        )
            .appendStage("AUTODRIVE_FLOOR", 0.8, 2.2, 2.2, "AUTODRIVE_PRE_TERMINAL", "FLOOR")
            .appendStage("PATTERN_CAP", 2.2, 1.2, 1.2)
            .build(finalU = 1.2)
            .toJsonObject()

        assertThat(json.getString("binding_stage")).isEqualTo("PATTERN_CAP")
        assertThat(json.getDouble("final_u")).isEqualTo(1.2)
        assertThat(json.getDouble("max_smb_high_bg_u")).isEqualTo(2.2)
        assertThat(json.getJSONArray("stages").length()).isEqualTo(2)
        assertThat(json.getJSONArray("stages").getJSONObject(1).getBoolean("reduced")).isTrue()
        assertThat(json.getString("origin_owner")).isEqualTo("AutodriveV3")
        assertThat(json.getString("final_owner")).isEqualTo("GlobalAIMI")
        assertThat(json.getString("stage_structure")).isEqualTo("ORDERED_EVENTS_NOT_STRICTLY_CONTIGUOUS")
        assertThat(json.getJSONArray("stages").getJSONObject(0).has("reference_u")).isTrue()
        assertThat(json.getJSONArray("stages").getJSONObject(0).has("limit_u")).isFalse()
        assertThat(json.getJSONArray("stages").getJSONObject(0).getString("kind")).isEqualTo("FLOOR")
    }

    @Test
    fun terminalAmountU_matches_outcome_amount_semantics_without_stale_fallback() {
        assertThat(SmbBindingTrace.terminalAmountU(0.816)).isWithin(1e-9).of(0.816)
        assertThat(SmbBindingTrace.terminalAmountU(null)).isEqualTo(0.0)
        assertThat(SmbBindingTrace.terminalAmountU(Double.NaN)).isEqualTo(0.0)
    }
}
