package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Basal / T3C weight loading for shared code.
 *
 * Reads the target, falls back to its `.bak`, and accepts the first model that deserializes and
 * probes finite for [expectedInputSize] - the same three rules `AimiNeuralModelStore.load` applies on
 * Android, because they are the same rules. A file that cannot be read at all is skipped, never
 * thrown at the caller.
 *
 * **Loading only.** The write side is a tmp -> bak -> rename protocol that needs directory creation,
 * rename and delete, and the AIMI storage seam offers none of the three. `AimiNeuralModelStore` keeps
 * it on Android, where `NeuralModelTrainer` - the only writer - already lives. Do not add a `save`
 * here that is not atomic: a half-written weight file that still parses is a model nobody trained.
 */
internal object BasalMlModelStore {

    fun loadValid(storage: AimiStorage, target: AimiPath, expectedInputSize: Int): AimiNeuralNetwork? {
        for (path in listOf(target, storage.sibling(target, ".bak"))) {
            if (!storage.exists(path)) continue
            try {
                val text = storage.readText(path) ?: continue
                val net = AimiNeuralNetwork.fromJsonString(text) ?: continue
                val out = net.predict(FloatArray(expectedInputSize) { 0f })
                if (out.all { it.isFinite() }) return net
            } catch (_: Exception) {
                // corrupt/incompatible -> fall through to the backup
            }
        }
        return null
    }
}
