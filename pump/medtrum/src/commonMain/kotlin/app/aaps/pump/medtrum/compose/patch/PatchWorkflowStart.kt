package app.aaps.pump.medtrum.compose.patch

import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

/**
 * Where "Change patch" starts, from what the pump last reported. Shared, so the Android overview
 * and the iPhone open the same page for the same pump state:
 * - no patch, or a stopped one: prepare a new patch;
 * - a patch between filling and activation: resume that activation;
 * - otherwise a patch is running: take it off first.
 */
fun patchWorkflowStart(pumpState: MedtrumPumpState, patchPrimed: Boolean): PatchStep = when {
    pumpState == MedtrumPumpState.STOPPED || pumpState == MedtrumPumpState.NONE                ->
        PatchStep.PREPARE_PATCH

    pumpState <= MedtrumPumpState.EJECTED && !(pumpState < MedtrumPumpState.PRIMING && patchPrimed) ->
        PatchStep.RETRY_ACTIVATION

    else                                                                                        ->
        PatchStep.START_DEACTIVATION
}
