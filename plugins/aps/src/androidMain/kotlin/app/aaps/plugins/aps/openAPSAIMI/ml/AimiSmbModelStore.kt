package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import java.io.File

/**
 * SMB model persistence. Thin facade over the shared [AimiNeuralModelStore] that pins the SMB weight filename inside
 * the provided directory; the crash-safe tmp → bak → rename protocol and probe validation live in the shared store.
 */
internal object AimiSmbModelStore {

    private const val MODEL_FILE_NAME = "aimi_smb_model.json"

    /** The SMB weight file inside [dir] (exposed so the trainer can publish through the shared training pipeline). */
    fun modelFile(dir: File): File = File(dir, MODEL_FILE_NAME)

    fun save(dir: File, network: AimiNeuralNetwork): Boolean =
        AimiNeuralModelStore.save(modelFile(dir), network)

    fun load(dir: File, expectedInputSize: Int): AimiNeuralNetwork? =
        AimiNeuralModelStore.load(modelFile(dir), expectedInputSize)

    /**
     * Removes the stored SMB weights from [dir].
     *
     * Used when the weights are known to have been trained on the wrong column, so the engine falls
     * back to the rule-based dose until a training run on a readable corpus publishes new weights.
     * Deleting from here keeps the filename in one place; callers never build the path themselves.
     *
     * Study adaptation vs `6c0c0285ff`: the reference deleted only the target file. This store's
     * [load] goes through [AimiNeuralModelStore.load], which would otherwise resurrect the same
     * weights from the `.bak`. Delegating to [AimiNeuralModelStore.delete] removes target + siblings
     * so the discard actually clears the model the next [load] would see.
     *
     * Returns `true` when no weight file is left behind, whether it was deleted now or already gone.
     *
     * ⚠️ ASYNC IMPACT: File I/O. Called from the trainer's IO coroutine after the corpus guard
     * refuses the CSV; not on the `refine()` hot path.
     */
    fun delete(dir: File): Boolean = AimiNeuralModelStore.delete(modelFile(dir))
}
