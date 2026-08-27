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
}
