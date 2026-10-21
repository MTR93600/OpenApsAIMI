package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SMB from 2026-10-21 08:00: model 0, floor 1.70, guards 0.82, Red Carpet restored 1.70.
 *
 * Source: `dev_OAPSAIMI` working tree 2026-10-21 `SmbRestoreCeilingTest`, same cases in `kotlin.test`.
 */
class SmbRestoreCeilingTest {

    @Test
    fun restoreCannotGoAboveWhatTheModelOrTheGuardsAllowed() {
        assertEquals(0.82, SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = 0.0, afterGuardsU = 0.82, exempt = false))
        assertEquals(0.67, SmbRestoreCeiling.apply(finalU = 1.53, modelOutputU = 0.61, afterGuardsU = 0.67, exempt = false))
    }

    @Test
    fun theModelRequestItselfIsNeverCut() {
        assertEquals(1.2, SmbRestoreCeiling.apply(finalU = 1.2, modelOutputU = 1.2, afterGuardsU = 0.5, exempt = false))
    }

    /** Declared meal or manual action: the restore keeps working (dose early on a declared meal). */
    @Test
    fun declaredMealIsExempt() {
        assertEquals(1.70, SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = 0.0, afterGuardsU = 0.82, exempt = true))
    }

    @Test
    fun unknownModelOutputLeavesTheDose() {
        assertEquals(1.70, SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = null, afterGuardsU = 0.82, exempt = false))
        assertEquals(1.70, SmbRestoreCeiling.apply(finalU = 1.70, modelOutputU = Double.NaN, afterGuardsU = 0.82, exempt = false))
    }

    @Test
    fun neverRaisesADose() {
        assertEquals(0.3, SmbRestoreCeiling.apply(finalU = 0.3, modelOutputU = 1.0, afterGuardsU = 0.8, exempt = false))
    }
}
