package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.loadFromFile
import app.aaps.plugins.aps.openAPSAIMI.saveToFile
import java.io.File

/**
 * Crash-safe, validated persistence for [AimiNeuralNetwork] weight files, shared by every AIMI on-device trainer
 * (SMB refinement, basal, T3C). Single source of truth for the write/rotate/validate protocol so the heads cannot
 * drift apart.
 *
 * - **Save:** serialize to a sibling `.tmp` → rotate the current file to `.bak` → atomic rename `.tmp` → target.
 *   A crash between steps leaves either the previous target or the `.bak` intact.
 * - **Load:** try the target then its `.bak`, accepting the first model that deserializes and probes finite for
 *   `expectedInputSize` (rejects NaN/Inf and input-dimension mismatches — e.g. an old-schema model after the input
 *   size changed). A file that cannot be read at all is skipped, never thrown at the caller.
 * - **Delete:** removes the target and both siblings, so a model judged dead cannot come back through the backup.
 *   `NeuralModelTrainer` uses it when the incumbent fails its probes; the caller then runs without a model, which
 *   is a valid and safe state.
 * - Sibling files are derived from the parent **path string** (null-safe [File] constructor), so a target whose
 *   `parentFile` is absent or unresolved never crashes the caller.
 */
internal object AimiNeuralModelStore {

    private fun sibling(target: File, suffix: String): File = File(target.parent, target.name + suffix)

    fun save(target: File, network: AimiNeuralNetwork): Boolean =
        try {
            target.parentFile?.mkdirs()
            val tmp = sibling(target, ".tmp")
            val bak = sibling(target, ".bak")
            network.saveToFile(tmp)
            if (target.exists()) {
                bak.delete()
                target.renameTo(bak)
            }
            tmp.renameTo(target)
        } catch (_: Exception) {
            false
        }

    fun load(target: File, expectedInputSize: Int): AimiNeuralNetwork? {
        for (file in listOf(target, sibling(target, ".bak"))) {
            if (!file.exists()) continue
            try {
                val net = AimiNeuralNetwork.loadFromFile(file) ?: continue
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
    fun delete(target: File): Boolean =
        try {
            for (file in listOf(target, sibling(target, ".bak"), sibling(target, ".tmp"))) {
                if (file.exists()) file.delete()
            }
            !target.exists() && !sibling(target, ".bak").exists()
        } catch (_: Exception) {
            false
        }
}
