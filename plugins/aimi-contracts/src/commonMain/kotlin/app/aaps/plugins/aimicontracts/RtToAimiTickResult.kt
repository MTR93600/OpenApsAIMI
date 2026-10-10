package app.aaps.plugins.aimicontracts

import app.aaps.core.interfaces.aps.RT

/**
 * Maps an APS result onto the engine contract.
 *
 * It lives in this module, beside the types it builds, because both platforms need it: the Android
 * tick maps its own result here so a run can be replayed, and the shared engine maps its result the
 * same way. It started in `:plugins:aps` `appleJvmMain`, which the Android tick cannot see, so the
 * recorder could not reach it at all.
 *
 * Nothing in it is platform code - it reads [RT] and writes contract types, and that is why it can
 * sit here rather than being written twice.
 */
fun RT.toAimiTickResult(state: AimiEngineState): AimiTickResult {
    // C1: Android's LoopPlugin applies BOTH the temp basal and the SMB when both
    // are requested (TBR first, then SMB). The previous code dropped the TBR
    // whenever an SMB was present. Emit the SMB as the primary command and the
    // TBR as the paired command so the shell enacts both.
    val smbCommand: AimiTherapyCommand.Smb? =
        if (units != null && (units ?: 0.0) > 0.0) AimiTherapyCommand.Smb(units ?: 0.0) else null
    val tbrCommand: AimiTherapyCommand.TempBasal? =
        if (rate != null && duration != null) AimiTherapyCommand.TempBasal(
            rateUPerHour = rate ?: 0.0,
            durationMs = (duration ?: 0) * 60_000L,
        ) else null
    val command: AimiTherapyCommand = smbCommand
        ?: tbrCommand
        ?: AimiTherapyCommand.Hold(reasonCode = "NO_COMMAND")
    // The paired command is the "other" one when both are present.
    val pairedCommand: AimiTherapyCommand? = when {
        smbCommand != null && tbrCommand != null -> tbrCommand
        else -> null
    }
    return AimiTickResult(
        command = command,
        nextState = state.copy(generation = state.generation + 1),
        trainingEvents = emptyList(),
        persistenceEvents = emptyList(),
        telemetry = AimiDecisionTrace(reasonCode = reason.toString().take(200)),
        safety = AimiSafetyReport(holdReasonCode = null),
        pairedCommand = pairedCommand,
    )
}
