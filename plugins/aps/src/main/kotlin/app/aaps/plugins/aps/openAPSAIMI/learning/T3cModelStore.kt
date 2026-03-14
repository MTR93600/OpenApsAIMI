package app.aaps.plugins.aps.openAPSAIMI.learning

import android.util.Log
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import java.io.File

/**
 * Persistence for the T3cNeuralLearner model.
 */
internal object T3cModelStore {
    private const val TAG = "T3cModelStore"

    private fun mainFile(dir: File) = File(dir, "aimi_t3c_model.json")
    private fun tmpFile(dir: File)  = File(dir, "aimi_t3c_model.json.tmp")
    private fun bakFile(dir: File)  = File(dir, "aimi_t3c_model.json.bak")

    fun save(dir: File, network: AimiNeuralNetwork): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = tmpFile(dir)
            val main = mainFile(dir)
            val bak = bakFile(dir)

            network.saveToFile(tmp)

            if (main.exists()) {
                bak.delete()
                main.renameTo(bak)
            }

            val ok = tmp.renameTo(main)
            if (!ok) Log.e(TAG, "Atomic rename failed")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "save() failed: ${e.message}")
            false
        }
    }

    fun load(dir: File, expectedInputSize: Int): AimiNeuralNetwork? {
        val candidates = listOf(mainFile(dir), bakFile(dir))
        for (file in candidates) {
            if (!file.exists()) continue
            try {
                val net = AimiNeuralNetwork.loadFromFile(file) ?: continue
                if (validate(net, expectedInputSize)) {
                    Log.d(TAG, "Model loaded from ${file.name}")
                    return net
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load ${file.name}: ${e.message}")
            }
        }
        return null
    }

    private fun validate(net: AimiNeuralNetwork, expectedInputSize: Int): Boolean {
        return try {
            val probe = FloatArray(expectedInputSize) { 0f }
            val out = net.predict(probe)
            out.all { it.isFinite() }
        } catch (e: Exception) {
            false
        }
    }
}
