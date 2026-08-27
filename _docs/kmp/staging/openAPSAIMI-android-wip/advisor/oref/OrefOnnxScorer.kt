package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Loads ONNX exports of the hypo/hyper classifiers and bg_change regressor from `assets/oref/`.
 * Runs fully on-device; no network. Models are optional — if missing, [tryCreate] returns null.
 */
class OrefOnnxScorer private constructor(
    private val env: OrtEnvironment,
    private val hypoSession: OrtSession,
    private val hyperSession: OrtSession,
    private val bgChangeSession: OrtSession,
    private val hypoInput: String,
    private val hyperInput: String,
    private val bgInput: String,
) : AutoCloseable {

    fun predictHypoProba(featuresRowMajor: Array<FloatArray>): FloatArray =
        runBinaryProbaSecondClass(hypoSession, hypoInput, featuresRowMajor)

    fun predictHyperProba(featuresRowMajor: Array<FloatArray>): FloatArray =
        runBinaryProbaSecondClass(hyperSession, hyperInput, featuresRowMajor)

    fun predictBgChange(featuresRowMajor: Array<FloatArray>): FloatArray =
        runRegression(bgChangeSession, bgInput, featuresRowMajor)

    private fun runBinaryProbaSecondClass(session: OrtSession, inputName: String, rows: Array<FloatArray>): FloatArray {
        val n = rows.size
        if (n == 0) return floatArrayOf()
        val f = OrefModelFeatures.COUNT
        val flat = FloatArray(n * f)
        for (i in 0 until n) {
            System.arraycopy(rows[i], 0, flat, i * f, f)
        }
        val shape = longArrayOf(n.toLong(), f.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
        tensor.use {
            val result = session.run(mapOf(inputName to tensor))
            result.use { map ->
                return extractPositiveProba(map, n)
            }
        }
    }

    private fun extractPositiveProba(map: OrtSession.Result, batch: Int): FloatArray {
        val out = FloatArray(batch)
        val first = map[0] as? OnnxTensor ?: return out
        val info = first.info as? ai.onnxruntime.TensorInfo ?: return out
        val shape = info.shape
        val buf = first.floatBuffer
        when {
            shape.size == 2 && shape[1] == 2L -> {
                for (i in 0 until batch) {
                    buf.position(i * 2 + 1)
                    out[i] = buf.get()
                }
            }
            shape.size == 2 && shape[1] == 1L -> {
                for (i in 0 until minOf(batch, buf.remaining())) {
                    out[i] = buf.get()
                }
            }
            shape.size == 1 -> {
                for (i in 0 until minOf(batch, buf.remaining())) {
                    out[i] = buf.get()
                }
            }
            else -> {
                val rem = buf.remaining()
                if (rem >= batch * 2) {
                    for (i in 0 until batch) {
                        buf.position(i * 2 + 1)
                        out[i] = buf.get()
                    }
                } else if (rem >= batch) {
                    for (i in 0 until batch) out[i] = buf.get()
                }
            }
        }
        buf.rewind()
        return out
    }

    private fun runRegression(session: OrtSession, inputName: String, rows: Array<FloatArray>): FloatArray {
        val n = rows.size
        if (n == 0) return floatArrayOf()
        val f = OrefModelFeatures.COUNT
        val flat = FloatArray(n * f)
        for (i in 0 until n) {
            System.arraycopy(rows[i], 0, flat, i * f, f)
        }
        val shape = longArrayOf(n.toLong(), f.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
        tensor.use {
            val result = session.run(mapOf(inputName to tensor))
            result.use { map ->
                val first = map[0] as? OnnxTensor ?: return FloatArray(n)
                val buf = first.floatBuffer
                val out = FloatArray(n)
                for (i in 0 until minOf(n, buf.remaining())) {
                    out[i] = buf.get()
                }
                buf.rewind()
                return out
            }
        }
    }

    override fun close() {
        hypoSession.close()
        hyperSession.close()
        bgChangeSession.close()
    }

    companion object {

        const val ASSET_DIR = "oref"
        const val HYPO = "oref/hypo_lgbm.onnx"
        const val HYPER = "oref/hyper_lgbm.onnx"
        const val BG_CHANGE = "oref/bg_change_lgbm.onnx"

        fun assetModelsPresent(context: Context): Boolean =
            assetReadable(context, HYPO) && assetReadable(context, HYPER) && assetReadable(context, BG_CHANGE)

        private fun assetReadable(context: Context, path: String): Boolean = try {
            context.assets.open(path).use { it.readBytes().isNotEmpty() }
        } catch (_: Exception) {
            false
        }

        fun tryCreate(context: Context): OrefOnnxScorer? {
            if (!assetModelsPresent(context)) return null
            var hypoS: OrtSession? = null
            var hyperS: OrtSession? = null
            var bgS: OrtSession? = null
            try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                hypoS = env.createSession(context.assets.open(HYPO).readBytes(), opts)
                hyperS = env.createSession(context.assets.open(HYPER).readBytes(), opts)
                bgS = env.createSession(context.assets.open(BG_CHANGE).readBytes(), opts)
                val hi = hypoS.inputNames.firstOrNull()
                val he = hyperS.inputNames.firstOrNull()
                val bi = bgS.inputNames.firstOrNull()
                if (hi == null || he == null || bi == null) {
                    hypoS.close(); hyperS.close(); bgS.close()
                    return null
                }
                return OrefOnnxScorer(env, hypoS, hyperS, bgS, hi, he, bi).also {
                    hypoS = null
                    hyperS = null
                    bgS = null
                }
            } catch (_: Throwable) {
                hypoS?.close()
                hyperS?.close()
                bgS?.close()
                return null
            }
        }
    }
}

private inline fun <T : OnnxValue, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        try {
            this.close()
        } catch (_: Exception) {
        }
    }
}

private inline fun <R> OrtSession.Result.use(block: (OrtSession.Result) -> R): R {
    try {
        return block(this)
    } finally {
        try {
            this.close()
        } catch (_: Exception) {
        }
    }
}
