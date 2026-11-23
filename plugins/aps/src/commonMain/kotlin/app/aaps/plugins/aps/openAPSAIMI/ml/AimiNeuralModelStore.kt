package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.loadFromFile
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Crash-safe, validated persistence for [AimiNeuralNetwork] weight files, shared by every AIMI on-device trainer
 * (SMB refinement, basal, T3C). Single source of truth for the write/rotate/validate protocol so the heads cannot
 * drift apart.
 *
 * - **Save:** [AimiStorage.replaceKeepingBackup] does the write/rotate/rename: serialize to a sibling `.tmp` →
 *   rotate the current file to `.bak` → atomic rename `.tmp` → target. A crash between steps leaves either the
 *   previous target or the `.bak` intact.
 * - **Load:** try the target then its `.bak`, accepting the first model that deserializes and probes finite for
 *   `expectedInputSize` (rejects NaN/Inf and input-dimension mismatches — e.g. an old-schema model after the input
 *   size changed). A file that cannot be read at all is skipped, never thrown at the caller.
 * - **Delete:** removes the target and both siblings, so a model judged dead cannot come back through the backup.
 *   `NeuralModelTrainer` uses it when the incumbent fails its probes; the caller then runs without a model, which
 *   is a valid and safe state.
 * - Sibling paths are derived through [AimiStorage.sibling], never by taking [AimiPath] apart.
 */
internal object AimiNeuralModelStore {

    /**
     * Writes [network] to [target]. [extraFields] are added at the top level of the JSON (for example the stamp of
     * the health check that passed). They can never replace a field of the network itself, and the loader ignores
     * keys it does not know, so older code still reads the file.
     */
    fun save(
        storage: AimiStorage,
        target: AimiPath,
        network: AimiNeuralNetwork,
        extraFields: Map<String, JsonElement> = emptyMap(),
    ): Boolean =
        try {
            val text = if (extraFields.isEmpty()) {
                network.toJsonString()
            } else {
                val root = Json.parseToJsonElement(network.toJsonString()).jsonObject
                JsonObject(root + extraFields.filterKeys { it !in root }).toString()
            }
            storage.replaceKeepingBackup(target, text)
        } catch (_: Exception) {
            false
        }

    fun load(storage: AimiStorage, target: AimiPath, expectedInputSize: Int): AimiNeuralNetwork? {
        for (path in listOf(target, storage.sibling(target, ".bak"))) {
            if (!storage.exists(path)) continue
            try {
                val net = AimiNeuralNetwork.loadFromFile(storage, path) ?: continue
                val out = net.predict(FloatArray(expectedInputSize) { 0f })
                if (out.all { it.isFinite() }) return net
            } catch (_: Exception) {
                // corrupt/incompatible → fall through to the backup
            }
        }
        return null
    }

    /**
     * Removes the weight file and its `.bak` / `.tmp` siblings. Returns true if nothing is left on disk.
     *
     * The `.bak` must go too. Deleting only the target would let the next [load] pick the backup up again and
     * resurrect exactly the model we just judged dead.
     */
    fun delete(storage: AimiStorage, target: AimiPath): Boolean =
        try {
            for (path in listOf(target, storage.sibling(target, ".bak"), storage.sibling(target, ".tmp"))) {
                if (storage.exists(path)) storage.delete(path)
            }
            !storage.exists(target) && !storage.exists(storage.sibling(target, ".bak"))
        } catch (_: Exception) {
            false
        }
}
