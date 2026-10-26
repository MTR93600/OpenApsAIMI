package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.AimiUamHandler
import app.aaps.plugins.aps.openAPSAIMI.AimiUamInference
import app.aaps.plugins.aps.openAPSAIMI.UamInputSchemaValidator
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import kotlin.concurrent.Volatile

/**
 * The iPhone side of the UAM model, the same steps as Android's `AimiModelHandler`.
 *
 * The model file is `Documents/AAPS/ml/modelUAM.tflite` in the app's container, the same relative
 * path as on Android. It is read once and kept; a missing file is tried again on the next tick, and
 * until then the answer is 0f, exactly like Android.
 *
 * Android keeps a result cache keyed on the inputs. It only saves time: the model gives the same
 * float for the same inputs, so leaving it out here does not change the dose.
 */
internal object IosUamPredictor : AimiUamHandler.AimiUamPredictor {

    private val lock = AapsLock()

    @Volatile
    private var model: ByteArray? = null

    override fun predict(features: FloatArray, reason: StringBuilder?, rh: TextResolver): Float {
        val path = modelPath()
        val loaded = loadModel(path)
        reason?.appendLine(statusLine(rh, loaded))

        val (inputs, replaced) = AimiUamInference.sanitizeWithCount(features)
        if (replaced > 0) reason?.appendLine(rh.gs(ApsStrings.sanitize_info, replaced))

        if (loaded == null) {
            reason?.appendLine(rh.gs(ApsStrings.model_missing, path ?: AimiUamInference.MODEL_RELATIVE_PATH))
            reason?.appendLine(rh.gs(ApsStrings.uam_unavailable))
            return 0f
        }
        if (inputs.size != IosUamTflite.INPUTS) {
            reason?.appendLine(UamInputSchemaValidator.mismatchReason(IosUamTflite.INPUTS, inputs.size))
            return 0f
        }
        val raw = try {
            IosUamTflite.infer(loaded, inputs)
        } catch (e: Throwable) {
            reason?.appendLine(rh.gs(ApsStrings.tflite_failed, e.message ?: "Unknown error"))
            return 0f
        }
        val dose = AimiUamInference.doseFromRaw(raw)
        if (AimiUamInference.isUsable(raw)) reason?.appendLine(rh.gs(ApsStrings.uam_executed, aimiFmt2(dose)))
        else reason?.appendLine(rh.gs(ApsStrings.uam_invalid, raw))
        return dose
    }

    private fun loadModel(path: String?): ByteArray? {
        model?.let { return it }
        if (path == null) return null
        return lock.withLock {
            model ?: readFile(path)?.takeIf { it.isNotEmpty() }?.also { model = it }
        }
    }

    private fun statusLine(rh: TextResolver, loaded: ByteArray?): String {
        val flag = if (loaded != null) "✔" else "✘"
        val size = loaded?.let { "${aimiFmt1(it.size / 1024.0)} KB" } ?: "missing"
        return rh.gs(ApsStrings.uam_model_status, flag, "Documents/${AimiUamInference.MODEL_RELATIVE_PATH}", size)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun modelPath(): String? {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return null
        return "$documents/${AimiUamInference.MODEL_RELATIVE_PATH}"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return data.bytes?.readBytes(data.length.toInt())
    }
}
