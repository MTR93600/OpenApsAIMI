package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiNeuralModelStore
import java.io.File

/**
 * Basal / T3C weight persistence. Thin facade over the shared [AimiNeuralModelStore] (crash-safe tmp → bak → rename,
 * probe-validated load) so the basal and SMB heads use one hardened protocol.
 */
internal object BasalMlModelStore {

    fun saveAtomic(target: File, network: AimiNeuralNetwork): Boolean =
        AimiNeuralModelStore.save(target, network)

    fun loadValid(target: File, expectedInputSize: Int): AimiNeuralNetwork? =
        AimiNeuralModelStore.load(target, expectedInputSize)
}
