package app.aaps.plugins.aps.openAPSAIMI.wcycle

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WCycleCsvLogger(private val ctx: Context) {
    private val TAG = "WCycleCsvLogger"

    // 1) Chemin public (même principe que PkPdCsvLogger)
    private val publicDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val publicFile = File(publicDir, "oapsaimi_wcycle.csv")

    // 2) Fallback app-specific (aucune permission, toujours accessible par l’app)
    private val appDir = File(ctx.getExternalFilesDir(null), "Documents/AAPS")
    private val appFile = File(appDir, "oapsaimi_wcycle.csv")

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(row: Map<String, Any?>): Boolean {
        val headerNeededPublic = !publicFile.exists()
        val headerNeededApp = !appFile.exists()
        val line = build(row, headerNeededPublic || headerNeededApp)

        // Essai en public
        val publicOk = runCatching {
            ensureDir(publicDir)
            publicFile.appendText(line)
        }.onFailure { t ->
            Log.w(TAG, "Public write failed at ${publicFile.absolutePath}", t)
        }.isSuccess

        if (publicOk) return true

        // Fallback en app-specific
        val appOk = runCatching {
            ensureDir(appDir)
            appFile.appendText(line)
        }.onFailure { t ->
            Log.w(TAG, "App-specific write failed at ${appFile.absolutePath}", t)
        }.isSuccess

        return appOk
    }

    private fun ensureDir(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            error("Unable to create directory ${dir.absolutePath}")
        }
    }

    private fun build(row: Map<String, Any?>, header: Boolean): String {
        // ⚠️ Si tu as déjà ajouté les colonnes needBasalScale/needSmbScale, garde-les.
        // Sinon, tu peux revenir à l’en-tête plus simple (mais je recommande de les garder).
        val keys = listOf(
            "ts","trackingMode","cycleDay","phase","contraceptive","thyroid","verneuil",
            "bg","delta5","iob","tdd24h","isfProfile","dynIsf",
            "basalBase","smbBase","basalLearn","smbLearn",
            "basalApplied","smbApplied",
            "needBasalScale","needSmbScale",   // ← colonnes utiles pour l’apprentissage offline
            "applied","reason"
        )
        val sb = StringBuilder()
        if (header) sb.append(keys.joinToString(",")).append("\n")
        val map = row.toMutableMap(); map["ts"] = sdf.format(Date())
        sb.append(keys.joinToString(",") { (map[it] ?: "").toString() }).append("\n")
        return sb.toString()
    }
}
