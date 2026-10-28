package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The step part of the DIA adjustment. Unknown steps (null) must apply no factor. */
class BioSyncDiaRuleTest {

    private fun kind(steps: Int?, heartRate: Int?, intense: Boolean = false) =
        BioSyncDiaRule.decide(steps, heartRate, intense)

    @Test
    fun `unknown steps apply neither the stress factor nor the flow factor`() {
        assertThat(kind(steps = null, heartRate = 100)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        assertThat(kind(steps = null, heartRate = 0)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        assertThat(kind(steps = null, heartRate = null)).isEqualTo(BioSyncDiaRule.Kind.NONE)
    }

    @Test
    fun `real steps under 100 with a high heart rate lengthen the DIA`() {
        assertThat(kind(steps = 0, heartRate = 96)).isEqualTo(BioSyncDiaRule.Kind.STRESS)
        assertThat(kind(steps = 99, heartRate = 96)).isEqualTo(BioSyncDiaRule.Kind.STRESS)
        // At exactly 100 steps or at exactly 95 bpm the rule does not fire.
        assertThat(kind(steps = 100, heartRate = 96)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        assertThat(kind(steps = 50, heartRate = 95)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        assertThat(BioSyncDiaRule.STRESS_FACTOR).isEqualTo(1.2f)
    }

    @Test
    fun `real steps over 350 shorten the DIA unless the activity is already intense`() {
        assertThat(kind(steps = 351, heartRate = 0)).isEqualTo(BioSyncDiaRule.Kind.FLOW)
        assertThat(kind(steps = 350, heartRate = 0)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        // In an INTENSE state only the activity factor applies and the flow factor is skipped.
        assertThat(kind(steps = 351, heartRate = 0, intense = true)).isEqualTo(BioSyncDiaRule.Kind.NONE)
        assertThat(BioSyncDiaRule.FLOW_FACTOR).isEqualTo(0.90f)
    }
}
