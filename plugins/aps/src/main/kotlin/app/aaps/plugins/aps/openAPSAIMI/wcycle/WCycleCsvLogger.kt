package app.aaps.plugins.aps.openAPSAIMI.wcycle

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class WCycleCsvLogger(ctx: Context) {
    private val dir = File(ctx.getExternalFilesDir(null), "Documents/AAPS")
    private val file = File(dir, "oapsaimi_wcycle.csv")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun append(row: Map<String, Any?>): Boolean = try {
        val headerNeeded = !file.exists()
        dir.mkdirs()
        file.appendText(build(row, headerNeeded))
        true
    } catch (_: Throwable) { false }

    private fun build(row: Map<String, Any?>, header: Boolean): String {
        val keys = listOf(
            "ts","trackingMode","cycleDay","phase","contraceptive","thyroid","verneuil",
            "bg","delta5","iob","tdd24h","isfProfile","dynIsf",
            "basalBase","smbBase","basalLearn","smbLearn",
            "basalApplied","smbApplied",
            "needBasalScale","needSmbScale",   // <-- NEW
            "applied","reason"
        )
        val sb = StringBuilder()
        if (header) sb.append(keys.joinToString(",")).append("\n")
        val map = row.toMutableMap(); map["ts"] = sdf.format(Date())
        sb.append(keys.joinToString(",") { (map[it] ?: "").toString() }).append("\n")
        return sb.toString()
    }
}
