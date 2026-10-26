package app.aaps.plugins.aps.openAPSAIMI

/**
 * The UAM model runner of this platform, or null where there is none.
 *
 * Android and iOS both return one, and the plugin installs it on start. They must be switched on
 * together: a model that runs on one phone only would make the two phones dose differently (see
 * [AimiUamHandler]). The desktop JVM has no runner, so it keeps the model-absent path.
 */
internal expect fun aimiPlatformUamPredictor(): AimiUamHandler.AimiUamPredictor?

/**
 * The steps around the model call that both platforms must do the same way.
 *
 * The model itself already gives the same float on Android and iOS (`UamTfliteIosParityTest`).
 * These steps decide what happens to that float, so they are shared code: one copy, the same SMB.
 */
internal object AimiUamInference {

    /** Where the model lives, under the platform's `Documents` folder, on both platforms. */
    const val MODEL_RELATIVE_PATH = "AAPS/ml/modelUAM.tflite"

    /** A non-finite input becomes 0. Returns the inputs and how many were replaced. */
    fun sanitizeWithCount(values: FloatArray): Pair<FloatArray, Int> {
        var replaced = 0
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val v = values[i]
            out[i] = if (isUsable(v)) v else {
                replaced += 1
                0f
            }
        }
        return out to replaced
    }

    fun isUsable(v: Float): Boolean = v.isFinite() && !v.isNaN()

    /** Cuts to four decimals toward zero, as the Android handler always did. */
    fun round4(v: Float): Float = (v * 10000f).toInt() / 10000f

    /** The dose the engine gets from a raw model output: 4 decimals, never negative, 0 when unusable. */
    fun doseFromRaw(raw: Float): Float {
        val result = if (isUsable(raw)) round4(raw) else 0f
        return if (isUsable(result)) maxOf(0f, result) else 0f
    }
}
