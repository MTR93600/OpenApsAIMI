package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import android.util.Log
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max
import android.content.Context
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.R
import app.aaps.core.keys.interfaces.Preferences

/**
 * Handler UAM-only :
 *  - 1 seul modèle : Documents/AAPS/ml/modelUAM.tflite
 *  - Lazy init de l'Interpreter (persistant)
 *  - Cache résultat (clé SHA-256 des features)
 *  - Sanitize des entrées (NaN/Inf -> 0)
 *  - Logs détaillés dans rT.reason (optionnels) + Logcat
 *
 * Utilisation :
 *   val smb = AimiUamHandler.predictSmbUam(features, rT.reason).coerceAtLeast(0f)
 *   // en lifecycle plugin : onStart -> clearCache ; onStop -> close()
 */
object AimiUamHandler {
    private const val TAG = "AIMI-UAM"
    // Emplacement standard du modèle
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val modelUamFile = File(externalDir, "ml/modelUAM.tflite")
    // Interpreter TFLite (lazy/persistant)
    @Volatile private var interpreter: Interpreter? = null
    private val lock = Any()
    // Cache des prédictions
    private val smbCache: Cache<String, Float> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    // État de santé du modèle
    @Volatile private var lastModelPath: String? = null
    @Volatile private var lastLoadOk: Boolean = false
    @Volatile private var lastLoadError: String? = null
    @Volatile private var lastLoadTime: Long = 0L
    @Volatile private var runtimeConfidence: Double? = null
    @Volatile private var confidenceSupplier: (() -> Double?)? = null

    /** Appelé par le moteur UAM ou la logique de détection pour pousser une confiance live (0..1). */
    fun updateRuntimeConfidence(value: Double?) {
        runtimeConfidence = value?.coerceIn(0.0, 1.0)
    }

    /** Installé par le plugin (qui a accès à Preferences) pour récupérer une confiance persistée. */
    fun installConfidenceSupplier(supplier: (() -> Double?)?) {
        confidenceSupplier = supplier
    }

    /** Lecture "safe" sans dépendance au contexte/DI. Ordre: runtime -> supplier -> 0.0 */
    fun confidenceOrZero(): Double {
        runtimeConfidence?.let { return it.coerceIn(0.0, 1.0) }
        val fromSupplier = try { confidenceSupplier?.invoke() } catch (_: Throwable) { null }
        return (fromSupplier ?: 0.0).coerceIn(0.0, 1.0)
    }
    // Pour compat avec code existant qui attend un "getInstance()"
    fun getInstance(): AimiUamHandler = this

    /** Ligne de statut prête à logguer dans rT.reason */
    fun statusLine(context: Context): String {
        val path = lastModelPath ?: modelUamFile.absolutePath
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
        val documentsFolderName = context.getString(R.string.folder_documents)
        val relativePath = if (path.startsWith(documentsDir)) {
            documentsFolderName  + path.removePrefix(documentsDir)
        } else {
           path
        }
        //val flag = if (lastLoadOk) "✅" else "❌"
        val flag = if (lastLoadOk) "✔" else "✘"
        //val size = if (modelUamFile.exists()) "${modelUamFile.length()} B" else "missing"
        val size = if (modelUamFile.exists()) String.format("%.1f KB", modelUamFile.length().toDouble() / 1024) else "missing"
        //return "📦 UAM model: $flag ($path, $size)"
        return context.getString(R.string.uam_model_status, flag, relativePath, size)
    }

    /** Ajoute la ligne de statut dans un StringBuilder (ex: rT.reason) */
    fun appendStatus(to: StringBuilder?, context: Context) {
        to?.appendLine(statusLine(context))
    }

    /** Vide le cache des prédictions. À appeler par ex. dans onStart(). */
    fun clearCache(context: Context) {
        smbCache.invalidateAll()
      //Log.i(TAG, "SMB cache cleared")
        Log.i(TAG, context.getString(R.string.log_smb_cache_cleared))
    }

    /** Ferme l'interpréteur. À appeler dans onStop() du plugin. */
    fun close(context: Context) {
        try {
            synchronized(lock) {
                interpreter?.close()
                interpreter = null
            }
          //Log.i(TAG, "Interpreter closed")
            Log.i(TAG, context.getString(R.string.log_interpreter_closed))
        } catch (e: Throwable) {
          //Log.w(TAG, "Error closing interpreter: ${e.message}")
            Log.w(TAG, context.getString(R.string.log_error_closing_interpreter, e.message ?: "Unknown error"))
        }
    }

    /** Force un autre fichier modèle (test / debug), puis purge et re-lazy-init au prochain run. */
    fun configureUamModel(file: File?, context: Context) {
        synchronized(lock) {
            close(context)
            if (file != null) {
                if (file.exists()) {
                    modelUamFile.parentFile?.mkdirs()
                    // on ne copie pas : on pointe directement
                    // (si tu veux copier dans le dossier AAPS, ajoute une copie ici)
                }
                lastModelPath = file.absolutePath
            } else {
                lastModelPath = modelUamFile.absolutePath
            }
            lastLoadOk = false
            lastLoadError = null
            clearCache(context)
        }
    }

    /**
     * Exécute le modèle UAM sur les features données.
     * @param features FloatArray prêt (taille/ordre UAM)
     * @param reason   (optionnel) StringBuilder pour logs visibles (ex: rT.reason)
     * @return SMB brut (>=0 recommandé de faire .coerceAtLeast(0f) côté appelant)
     */
    fun predictSmbUam(
        features: FloatArray,
        reason: StringBuilder? = null,
        context: Context
    ): Float {
        appendStatus(reason, context) // affiche d'entrée l'état du modèle

        val (inputs, replaced) = sanitizeWithCount(features)
        if (replaced > 0) {
            //reason?.appendLine("🧹 Sanitize: $replaced entrées non finies -> 0")
            reason?.appendLine(context.getString(R.string.sanitize_info, replaced))
        }

        val key = cacheKey("UAM", inputs)
        smbCache.getIfPresent(key)?.let { cached ->
            if (isUsable(cached)) {
                //reason?.appendLine("⚡ Cache HIT → ${"%.4f".format(cached)} U")
                reason?.appendLine(context.getString(R.string.cache_hit, "%.4f".format(cached)))
                return cached
            } else {
                //reason?.appendLine("⚠️ Cache HIT non exploitable (NaN/Inf), recalcul…")
                reason?.appendLine(context.getString(R.string.cache_hit_invalid))
            }
        }

        val itp = ensureInterpreter(reason, context) ?: run {
            //reason?.appendLine("❌ Modèle UAM indisponible → SMB=0")
            reason?.appendLine(context.getString(R.string.uam_unavailable))
            return 0f
        }

        val raw = try {
            runModel(itp, inputs)
        } catch (e: Throwable) {
            //reason?.appendLine("💥 TFLite run échoué: ${e.message} → SMB=0")
            reason?.appendLine(context.getString(R.string.tflite_failed, e.message ?: "Unknown error"))
          //Log.e(TAG, "TFLite run failed: ${e.message}")
            Log.e(TAG, context.getString(R.string.log_tflite_failed, e.message ?: "Unknown error"))
            return 0f
        }

        val result = if (isUsable(raw)) round4(raw) else 0f
        if (isUsable(result)) {
            smbCache.put(key, result)
            //reason?.appendLine("✅ UAM exécuté → ${"%.4f".format(result)} U")
            reason?.appendLine(context.getString(R.string.uam_executed, "%.2f".format(result)))
        } else {
            //reason?.appendLine("⚠️ Résultat non exploitable (raw=$raw) → SMB=0")
            reason?.appendLine(context.getString(R.string.uam_invalid, raw))
        }
        return max(0f, result)
    }

    // ────────────────────────── Privé : init & exécution ──────────────────────────

    private fun ensureInterpreter(reason: StringBuilder? = null, context: Context): Interpreter? {
        interpreter?.let { return it }
        synchronized(lock) {
            interpreter?.let { return it }

            val file = File(lastModelPath ?: modelUamFile.absolutePath)
            if (!file.exists()) {
                lastLoadOk = false
                lastLoadError = "file not found"
                //reason?.appendLine("❌ Fichier modèle introuvable : ${file.absolutePath}")
                reason?.appendLine(context.getString(R.string.model_missing, file.absolutePath))
              //Log.e(TAG, "Model file not found: ${file.absolutePath}")
                Log.e(TAG, context.getString(R.string.log_model_file_not_found, file.absolutePath))
                return null
            }
            return try {
                val mapped = loadModel(file)
                Interpreter(mapped).also {
                    interpreter = it
                    lastLoadOk = true
                    lastLoadError = null
                    lastLoadTime = System.currentTimeMillis()
                    lastModelPath = file.absolutePath
                    //reason?.appendLine("📦 Chargé ✓ : ${file.name} (${file.length()} B)")
                    reason?.appendLine(context.getString(R.string.model_loaded, file.name, "%.1f".format(file.length().toDouble() / 1024)))
                  //Log.i(TAG, "Interpreter initialized from ${file.absolutePath} (${file.length()} bytes)")
                  //Log.i(TAG, context.getString(R.string.log_interpreter_initialized, file.absolutePath, file.length()))
                    val sizeKb = String.format("%.1f KB", file.length().toDouble() / 1024)
                    Log.i(TAG, context.getString(R.string.log_interpreter_initialized, file.absolutePath, sizeKb))
                }
            } catch (e: Throwable) {
                lastLoadOk = false
                lastLoadError = e.message
                //reason?.appendLine("❌ Échec chargement modèle: ${e.message}")
                reason?.appendLine(context.getString(R.string.model_load_failed, e.message ?: "Unknown error"))
              //Log.e(TAG, "Failed to init UAM model: ${e.message}")
                Log.e(TAG, context.getString(R.string.log_failed_init_uam, e.message ?: "Unknown error"))
                null
            }
        }
    }

    private fun runModel(interpreter: Interpreter, features: FloatArray): Float {
        val input = arrayOf(features)         // 1 x N
        val out = Array(1) { FloatArray(1) }  // 1 x 1
        interpreter.run(input, out)
        return out[0][0]
    }

    private fun loadModel(file: File): MappedByteBuffer {
        FileInputStream(file).use { fis ->
            val ch = fis.channel
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }

    // ────────────────────────── Utilitaires ──────────────────────────

    private fun sanitizeWithCount(values: FloatArray): Pair<FloatArray, Int> {
        var replaced = 0
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val v = values[i]
            val ok = v.isFinite() && !v.isNaN()
            out[i] = if (ok) v else {
                replaced += 1
                0f
            }
        }
        return out to replaced
    }

    private fun isUsable(v: Float): Boolean = v.isFinite() && !v.isNaN()

    private fun round4(v: Float): Float = (v * 10000f).toInt() / 10000f.toFloat()

    private fun cacheKey(modelName: String, inputs: FloatArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(modelName.toByteArray(StandardCharsets.UTF_8))
        val bb = ByteBuffer.allocate(inputs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        inputs.forEach { bb.putFloat(it) }
        md.update(bb.array())
        return modelName + "_" + md.digest().joinToString("") { "%02x".format(it) }
    }
}