package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class AutodrivePatternCapReplayTest {

    private data class AutoDriveSnapshot(
        val label: String,
        val timestampMs: Long,
    )

    private val autoDriveSnapshots = listOf(
        AutoDriveSnapshot("14:29:12", 52_152_000L),
        AutoDriveSnapshot("21:20:08", 76_808_000L),
    )

    @Test
    fun soft_meal_proposal_no_longer_binds_terminal_pre_caps_at_1_2() {
        // Historical Variant A used PATTERN_CAP min→1.2 as binding. Soft meal must not.
        val softReplay = SmbBindingTrace.replayCapsBeforeTerminalProtections(
            timestampMs = 52_152_000L,
            autodriveRequestU = 2.2,
            patternCapU = 1.2,
            patternCapKind = PatternCapKind.SOFT,
        )
        assertThat(softReplay.afterPatternCapU).isWithin(1e-9).of(2.2)
        assertThat(softReplay.bindingStage).isNull()
        assertThat(softReplay.stages.single().name).isEqualTo("PATTERN_SOFT_PROPOSAL")
        assertThat(softReplay.stages.single().kind).isEqualTo("PROPOSAL")
    }

    @Test
    fun hard_protective_pattern_still_binds_before_terminal_protections() {
        val hardReplay = SmbBindingTrace.replayCapsBeforeTerminalProtections(
            timestampMs = 76_808_000L,
            autodriveRequestU = 2.2,
            patternCapU = 0.50,
            patternCapKind = PatternCapKind.HARD,
        )
        assertThat(hardReplay.afterPatternCapU).isWithin(1e-9).of(0.50)
        assertThat(hardReplay.bindingStage).isEqualTo("PATTERN_CAP")
    }

    @Test
    fun observed_22_59_is_global_aimi_negative_control_without_autodrive_pattern_binding() {
        val observed = SmbBindingTrace.Draft(
            timestampMs = 82_743_000L,
            originOwner = "GlobalAIMI",
            finalOwner = "GlobalAIMI",
            modelOutputU = 0.72,
            mpcOutputU = 0.0,
            tier = "OFF",
            patternCapU = null,
        )
            .appendStage("GLOBAL_AIMI", 0.72, 0.72)
            .appendStage("FINAL", 0.72, 0.72)
            .build(finalU = 0.72)

        assertThat(observed.originOwner).isEqualTo("GlobalAIMI")
        assertThat(observed.finalOwner).isEqualTo("GlobalAIMI")
        assertThat(observed.mpcOutputU).isEqualTo(0.0)
        assertThat(observed.finalU).isWithin(1e-9).of(0.72)
        assertThat(observed.stages.map { it.name }).doesNotContain("PATTERN_CAP")
        assertThat(observed.bindingStage).isNull()
    }

    @Test
    fun isolated_soft_meal_counterfactual_leaves_mpc_demand_for_harmonia_envelope() {
        autoDriveSnapshots.forEach { snapshot ->
            val softBeforeTerminal = SmbBindingTrace.replayCapsBeforeTerminalProtections(
                timestampMs = snapshot.timestampMs,
                autodriveRequestU = 2.2,
                patternCapU = 1.2,
                patternCapKind = PatternCapKind.SOFT,
            )
            val hardEnvelopeStillApplies = minOf(softBeforeTerminal.afterPatternCapU, 2.2)

            assertWithMessage(snapshot.label)
                .that(softBeforeTerminal.afterPatternCapU)
                .isWithin(1e-9)
                .of(2.2)
            assertWithMessage(snapshot.label)
                .that(softBeforeTerminal.bindingStage)
                .isNull()
            assertWithMessage(snapshot.label)
                .that(hardEnvelopeStillApplies)
                .isWithin(1e-9)
                .of(2.2)
        }
    }
}
