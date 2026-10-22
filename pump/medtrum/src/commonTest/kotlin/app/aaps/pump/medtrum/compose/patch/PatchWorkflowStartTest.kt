package app.aaps.pump.medtrum.compose.patch

import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Change patch" opens the same page on Android and iPhone for the same pump state. */
class PatchWorkflowStartTest {

    @Test
    fun `no patch or a stopped patch starts a new one`() {
        assertEquals(PatchStep.PREPARE_PATCH, patchWorkflowStart(MedtrumPumpState.NONE, patchPrimed = false))
        assertEquals(PatchStep.PREPARE_PATCH, patchWorkflowStart(MedtrumPumpState.STOPPED, patchPrimed = true))
    }

    @Test
    fun `a patch between filling and activation resumes the activation`() {
        assertEquals(PatchStep.RETRY_ACTIVATION, patchWorkflowStart(MedtrumPumpState.FILLED, patchPrimed = false))
        assertEquals(PatchStep.RETRY_ACTIVATION, patchWorkflowStart(MedtrumPumpState.PRIMED, patchPrimed = true))
        assertEquals(PatchStep.RETRY_ACTIVATION, patchWorkflowStart(MedtrumPumpState.EJECTED, patchPrimed = true))
    }

    /** Filled again after a priming: Android does not offer a resume there, it asks to take it off. */
    @Test
    fun `a primed patch that reports filled is taken off`() {
        assertEquals(PatchStep.START_DEACTIVATION, patchWorkflowStart(MedtrumPumpState.FILLED, patchPrimed = true))
    }

    @Test
    fun `a running patch is taken off first`() {
        assertEquals(PatchStep.START_DEACTIVATION, patchWorkflowStart(MedtrumPumpState.ACTIVE, patchPrimed = true))
        assertEquals(PatchStep.START_DEACTIVATION, patchWorkflowStart(MedtrumPumpState.OCCLUSION, patchPrimed = true))
    }
}
