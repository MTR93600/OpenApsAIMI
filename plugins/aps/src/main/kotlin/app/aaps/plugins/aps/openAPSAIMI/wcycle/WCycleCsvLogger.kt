package app.aaps.plugins.aps.openAPSAIMI.wcycle

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WCycleCsvLogger(private val ctx: Context) {
    private val TAG = "WCycleCsvLogger"

    // 🔧 FIX: Use ONLY standard /Documents/AAPS path (no app-specific fallback)
    private val dir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val file = File(dir, "oapsaimi_wcycle.csv")

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(row: Map<String, Any?>): Boolean {
        val headerNeeded = !file.exists()
        val line = build(row, headerNeeded)

        return runCatching {
            ensureDir(dir)
            file.appendText(line)
        }.onFailure { t ->
            Log.w(TAG, "Write failed at ${file.absolutePath}", t)
        }.isSuccess
    }

    private fun ensureDir(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            error("Unable to create directory ${dir.absolutePath}")
        }
    }

    private fun build(row: Map<String, Any?>, header: Boolean): String {
        val keys = listOf(
            "ts","trackingMode","cycleDay","phase","contraceptive","thyroid","verneuil",
            "bg","delta5","iob","tdd24h","isfProfile","dynIsf",
            "basalBase","smbBase","basalLearn","smbLearn",
            "basalApplied","smbApplied",
            "needBasalScale","needSmbScale",   // ← colonnes utiles pour l'apprentissage offline
            "applied","reason"
        )
        val sb = StringBuilder()
        if (header) sb.append(keys.joinToString(",")).append("\n")
        val map = row.toMutableMap(); map["ts"] = sdf.format(Date())
        sb.append(keys.joinToString(",") { (map[it] ?: "").toString() }).append("\n")
        return sb.toString()
    }
}
