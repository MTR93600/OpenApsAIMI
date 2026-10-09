package app.aaps.plugins.aimicontracts

import kotlinx.serialization.Serializable

/**
 * Causal memory between ticks. W7 is the envelope only: generation + schema.
 * Hypo holds, RBT rings and learner checkpoints are filled when the freeze tick is extracted.
 */
@Serializable
data class AimiEngineState(
    val schemaVersion: Int,
    val generation: Long,
)

/**
 * Models resolved for this tick. The engine must not open `modelUAM.tflite` itself.
 * [uamSha256] is the expected digest; a missing file is [uamSha256] = null.
 */
@Serializable
data class AimiModelBundle(
    val uamSchemaId: String,
    val uamSha256: String?,
)

/** What the shell may enact. The engine never talks to the pump. */
@Serializable
sealed interface AimiTherapyCommand {
    @Serializable
    data class Hold(val reasonCode: String) : AimiTherapyCommand
    @Serializable
    data class Smb(val insulinU: Double) : AimiTherapyCommand
    @Serializable
    data class TempBasal(val rateUPerHour: Double, val durationMs: Long) : AimiTherapyCommand
}

@Serializable
sealed interface AimiTrainingEvent

@Serializable
sealed interface AimiPersistenceEvent

@Serializable
data class AimiDecisionTrace(
    val reasonCode: String,
)

@Serializable
data class AimiSafetyReport(
    val holdReasonCode: String?,
)

/**
 * One evaluate() result. [nextState] is advice for tick N+1. Auditor/TPO must not mutate this.
 */
@Serializable
data class AimiTickResult(
    val command: AimiTherapyCommand,
    val nextState: AimiEngineState,
    val trainingEvents: List<AimiTrainingEvent>,
    val persistenceEvents: List<AimiPersistenceEvent>,
    val telemetry: AimiDecisionTrace,
    val safety: AimiSafetyReport,
    /**
     * Second command of the same tick.
     *
     * The meal advisor scene enacts an SMB and a temp basal together. Null means this tick has one
     * command. It does not mean a zero dose.
     */
    val pairedCommand: AimiTherapyCommand? = null,
)
