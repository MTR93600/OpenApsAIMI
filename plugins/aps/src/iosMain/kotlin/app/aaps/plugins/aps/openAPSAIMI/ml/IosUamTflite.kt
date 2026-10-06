package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.tflite.TfLiteInterpreterAllocateTensors
import app.aaps.plugins.aps.tflite.TfLiteInterpreterCreate
import app.aaps.plugins.aps.tflite.TfLiteInterpreterDelete
import app.aaps.plugins.aps.tflite.TfLiteInterpreterGetInputTensor
import app.aaps.plugins.aps.tflite.TfLiteInterpreterGetOutputTensor
import app.aaps.plugins.aps.tflite.TfLiteInterpreterInvoke
import app.aaps.plugins.aps.tflite.TfLiteInterpreterOptionsCreate
import app.aaps.plugins.aps.tflite.TfLiteInterpreterOptionsDelete
import app.aaps.plugins.aps.tflite.TfLiteInterpreterOptionsSetNumThreads
import app.aaps.plugins.aps.tflite.TfLiteModelCreate
import app.aaps.plugins.aps.tflite.TfLiteModelDelete
import app.aaps.plugins.aps.tflite.TfLiteTensorCopyFromBuffer
import app.aaps.plugins.aps.tflite.TfLiteTensorCopyToBuffer
import app.aaps.plugins.aps.tflite.TfLiteVersion
import app.aaps.plugins.aps.tflite.kTfLiteOk
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

/**
 * CPU inference of `modelUAM.tflite` through TensorFlow Lite C.
 *
 * One thread, no delegate. XNNPACK is not added. Nothing in the basal tick calls this.
 * `predictSmbUam` stays on the model-absent path.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosUamTflite {
    const val THREADS = 1
    const val INPUTS = 18

    fun version(): String = TfLiteVersion()?.toKString() ?: ""

    fun infer(model: ByteArray, input: FloatArray): Float {
        require(input.size == INPUTS) { "UAM input is $INPUTS floats, got ${input.size}" }
        return model.usePinned { pinnedModel ->
            val tfModel = TfLiteModelCreate(pinnedModel.addressOf(0), model.size.convert())
                ?: error("TensorFlow Lite C rejected modelUAM.tflite (${model.size} bytes, runtime ${version()})")
            try {
                val options = TfLiteInterpreterOptionsCreate()
                    ?: error("TensorFlow Lite C did not create interpreter options")
                TfLiteInterpreterOptionsSetNumThreads(options, THREADS)
                val interpreter = TfLiteInterpreterCreate(tfModel, options)
                TfLiteInterpreterOptionsDelete(options)
                if (interpreter == null) error("TensorFlow Lite C did not create an interpreter (${version()})")
                try {
                    if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk) {
                        error("TensorFlow Lite C failed to allocate tensors")
                    }
                    val inputTensor = TfLiteInterpreterGetInputTensor(interpreter, 0)
                        ?: error("TensorFlow Lite C has no input tensor")
                    input.usePinned { pinnedInput ->
                        val copied = TfLiteTensorCopyFromBuffer(
                            inputTensor,
                            pinnedInput.addressOf(0),
                            (input.size * 4).convert(),
                        )
                        if (copied != kTfLiteOk) error("TensorFlow Lite C rejected the input buffer")
                    }
                    if (TfLiteInterpreterInvoke(interpreter) != kTfLiteOk) {
                        error("TensorFlow Lite C invoke failed")
                    }
                    val outputTensor = TfLiteInterpreterGetOutputTensor(interpreter, 0)
                        ?: error("TensorFlow Lite C has no output tensor")
                    val output = FloatArray(1)
                    output.usePinned { pinnedOutput ->
                        val copied = TfLiteTensorCopyToBuffer(
                            outputTensor,
                            pinnedOutput.addressOf(0),
                            4.convert(),
                        )
                        if (copied != kTfLiteOk) error("TensorFlow Lite C rejected the output buffer")
                    }
                    output[0]
                } finally {
                    TfLiteInterpreterDelete(interpreter)
                }
            } finally {
                TfLiteModelDelete(tfModel)
            }
        }
    }
}
