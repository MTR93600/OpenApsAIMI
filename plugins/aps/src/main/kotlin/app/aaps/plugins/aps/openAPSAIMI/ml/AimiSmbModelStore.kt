package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import java.io.File

/**
 * SMB model persistence. Thin facade over the shared [AimiNeuralModelStore] that pins the SMB weight filename inside
 * the provided directory; the crash-safe tmp → bak → rename protocol and probe validation live in the shared store.
 */
object AimiSmbModelStore {

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
     * Returns `true` when no weight file is left behind, whether it was deleted now or already gone.
     */
    fun delete(dir: File): Boolean {
        val file = modelFile(dir)
        if (!file.exists()) return true
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
