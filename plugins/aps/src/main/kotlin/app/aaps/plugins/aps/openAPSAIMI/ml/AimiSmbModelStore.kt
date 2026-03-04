package app.aaps.plugins.aps.openAPSAIMI.ml

import android.util.Log
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import java.io.File

/**
 * Crash-safe persistence for the AimiNeuralNetwork SMB model.
 *
 * Write strategy:
 *  1. Serialize to a .tmp file
 *  2. Rename atomically to .json (OS guarantees atomicity on most FS)
 *  3. Keep a .bak copy for recovery
 *
 * Validation: rejects any model containing NaN/Inf weights
 * or whose input dimension doesn't match [expectedInputSize].
 */
internal object AimiSmbModelStore {

    private const val TAG = "AimiSmbModelStore"

    // ---- File names -------------------------------------------------------
    private fun mainFile(dir: File) = File(dir, "aimi_smb_model.json")
    private fun tmpFile(dir: File)  = File(dir, "aimi_smb_model.json.tmp")
    private fun bakFile(dir: File)  = File(dir, "aimi_smb_model.json.bak")

    // ---- Save -------------------------------------------------------------

    /**
     * Atomically save [network] to [dir].
     * Returns true on success, false on any IO error.
     */
    fun save(dir: File, network: AimiNeuralNetwork): Boolean {
        return try {
            dir.mkdirs()
            val tmp = tmpFile(dir)
            val main = mainFile(dir)
            val bak = bakFile(dir)

            // 1. Serialize to tmp
            network.saveToFile(tmp)

            // 2. Rotate: main → bak
            if (main.exists()) {
                bak.delete()
                main.renameTo(bak)
            }

            // 3. tmp → main (atomic on most Android FS)
            val ok = tmp.renameTo(main)
            if (!ok) {
                Log.e(TAG, "Atomic rename failed — model may be stale.")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "save() failed: ${e.message}")
            false
        }
    }

    // ---- Load -------------------------------------------------------------

    /**
     * Load the model from [dir], trying main file first then backup.
     * Validates that the model is sane (no NaN/Inf, correct input size).
     * Returns null if no valid model is found.
     */
    fun load(dir: File, expectedInputSize: Int): AimiNeuralNetwork? {
        val candidates = listOf(mainFile(dir), bakFile(dir))
        for (file in candidates) {
            if (!file.exists()) continue
            try {
                val net = AimiNeuralNetwork.loadFromFile(file) ?: continue
                if (validate(net, expectedInputSize)) {
                    Log.d(TAG, "Model loaded from ${file.name}")
                    return net
                } else {
                    Log.w(TAG, "Model ${file.name} failed validation — skipping.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load ${file.name}: ${e.message}")
            }
        }
        return null
    }

    // ---- Validation -------------------------------------------------------

    /**
     * Returns true if the model contains no NaN/Inf values
     * and matches the expected input dimension.
     */
    private fun validate(net: AimiNeuralNetwork, expectedInputSize: Int): Boolean {
        return try {
            // Probe with a zero vector to detect NaN/Inf at runtime
            val probe = FloatArray(expectedInputSize) { 0f }
            val out = net.predict(probe)
            out.all { it.isFinite() }
        } catch (e: Exception) {
            false
        }
    }
}
