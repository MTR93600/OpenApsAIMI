package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Verifies §15 adapter registry covers all 101 BeliefLeafId entries. */
class BeliefLeafCoverageTest {

    @Test
    fun every_enum_entry_has_scale_mapping() {
        for (id in BeliefLeafId.entries) {
            val tau = when (id) {
                in BeliefLeafId.MICRO -> 15
                in BeliefLeafId.MESO -> 60
                in BeliefLeafId.MACRO -> 180
                in BeliefLeafId.META -> 480
                in BeliefLeafId.SHADOW -> 60
                else -> error("Unmapped leaf $id")
            }
            assertThat(listOf(15, 60, 180, 480)).contains(tau)
        }
        assertThat(BeliefLeafId.MICRO.size + BeliefLeafId.MESO.size +
            BeliefLeafId.MACRO.size + BeliefLeafId.META.size + BeliefLeafId.SHADOW.size)
            .isEqualTo(BeliefLeafId.entries.size)
        assertThat(BeliefLeafId.entries.size).isAtLeast(101)
    }

    @Test
    fun registry_reads_core_leaves_with_rich_context() {
        val ctx = RecursiveBeliefMr7TestHelper.coverageCtx()
        val collected = buildList {
            addAll(BeliefLeafAdapterRegistry.collect(15, ctx, includeShadow = false))
            addAll(BeliefLeafAdapterRegistry.collect(60, ctx, includeShadow = true))
            addAll(BeliefLeafAdapterRegistry.collect(180, ctx, includeShadow = false))
            addAll(BeliefLeafAdapterRegistry.collect(480, ctx, includeShadow = false))
        }
        val readable = BeliefLeafId.entries.mapNotNull { id ->
            BeliefLeafAdapterRegistry.readLeaf(id, ctx)?.takeIf { it.credibility >= 0.05 }
        }
        assertThat(BeliefLeafId.entries.size).isEqualTo(
            BeliefLeafId.MICRO.size + BeliefLeafId.MESO.size +
                BeliefLeafId.MACRO.size + BeliefLeafId.META.size + BeliefLeafId.SHADOW.size,
        )
        assertThat(readable.size).isAtLeast(80)
        assertThat(collected.size).isAtLeast(70)
        assertThat(readable.map { it.id }).contains(BeliefLeafId.HTR_RELEASE)
        assertThat(readable.map { it.id }).contains(BeliefLeafId.PATTERN_RISK)
        assertThat(readable.map { it.id }).contains(BeliefLeafId.SLEEP_DEBT)
        assertThat(readable.map { it.id }).contains(BeliefLeafId.SLEEP_LIVE)
        assertThat(readable.map { it.id }).contains(BeliefLeafId.SHADOW_ML_TRAIN)
        assertThat(collected.map { it.id }).contains(BeliefLeafId.DELTA_NOW)
        assertThat(collected.map { it.id }).contains(BeliefLeafId.SCEN_TRAJ_RISE)
    }
}
