package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** SMB from 2026-10-21 08:00: model 0, floor 1.70, guards 0.82, Red Carpet restored 1.70. */
class SmbRestoreCeilingTest {

    @Test
    fun restoreCannotGoAboveWhatTheModelOrTheGuardsAllowed() {
        assertThat(SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = 0.0, afterGuardsU = 0.82, exempt = false)).isEqualTo(0.82)
        assertThat(SmbRestoreCeiling.apply(finalU = 1.53, modelOutputU = 0.61, afterGuardsU = 0.67, exempt = false)).isEqualTo(0.67)
    }

    @Test
    fun theModelRequestItselfIsNeverCut() {
        assertThat(SmbRestoreCeiling.apply(finalU = 1.2, modelOutputU = 1.2, afterGuardsU = 0.5, exempt = false)).isEqualTo(0.5)
    }

    /** Declared meal or manual action: the restore keeps working (dose early on a declared meal). */
    @Test
    fun declaredMealIsExempt() {
        assertThat(SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = 0.0, afterGuardsU = 0.82, exempt = true)).isEqualTo(1.70)
    }

    @Test
    fun unknownModelOutputLeavesTheDose() {
        assertThat(SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = null, afterGuardsU = 0.82, exempt = false)).isEqualTo(0.82)
        assertThat(SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = Double.NaN, afterGuardsU = 0.82, exempt = false)).isEqualTo(0.82)
    }

    @Test
    fun neverRaisesADose() {
        assertThat(SmbRestoreCeiling.apply(finalU = 0.3, modelOutputU = 1.0, afterGuardsU = 0.8, exempt = false)).isEqualTo(0.3)
    }
}
