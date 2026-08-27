package app.aaps.plugins.aimicontracts

/**
 * Causal memory between ticks. W7 is the envelope only: generation + schema.
 * Hypo holds, RBT rings and learner checkpoints are filled when the freeze tick is extracted.
 */
data class AimiEngineState(
    val schemaVersion: Int,
    val generation: Long,
)

/**
 * Models resolved for this tick. The engine must not open `modelUAM.tflite` itself.
 * [uamSha256] is the expected digest; a missing file is [uamSha256] = null.
 */
data class AimiModelBundle(
    val uamSchemaId: String,
    val uamSha256: String?,
)

/** What the shell may enact. The engine never talks to the pump. */
sealed interface AimiTherapyCommand {
    data class Hold(val reasonCode: String) : AimiTherapyCommand
    data class Smb(val insulinU: Double) : AimiTherapyCommand
    data class TempBasal(val rateUPerHour: Double, val durationMs: Long) : AimiTherapyCommand
}

sealed interface AimiTrainingEvent

sealed interface AimiPersistenceEvent

data class AimiDecisionTrace(
    val reasonCode: String,
)

data class AimiSafetyReport(
    val holdReasonCode: String?,
)

/**
 * One evaluate() result. [nextState] is advice for tick N+1. Auditor/TPO must not mutate this.
 */
data class AimiTickResult(
    val command: AimiTherapyCommand,
    val nextState: AimiEngineState,
    val trainingEvents: List<AimiTrainingEvent>,
    val persistenceEvents: List<AimiPersistenceEvent>,
    val telemetry: AimiDecisionTrace,
    val safety: AimiSafetyReport,
)
