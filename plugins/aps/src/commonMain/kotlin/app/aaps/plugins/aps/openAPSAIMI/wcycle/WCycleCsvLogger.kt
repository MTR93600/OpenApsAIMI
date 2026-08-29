package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiCsvTimestamp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

class WCycleCsvLogger(
    private val storage: AimiStorage,
    private val log: AAPSLogger? = null
) {

    private val file: AimiPath by lazy { storage.file("oapsaimi_wcycle.csv") }

    fun append(row: Map<String, Any?>): Boolean {
        val headerNeeded = !storage.exists(file)
        val line = build(row, headerNeeded)

        if (!storage.createDirectories(storage.directory())) {
            log?.warn(LTag.AIMI, "WCycleCsvLogger: cannot create directory for ${storage.displayPath(file)}")
            return false
        }
        val ok = storage.appendText(file, line)
        if (!ok) log?.warn(LTag.AIMI, "WCycleCsvLogger: write failed at ${storage.displayPath(file)}")
        return ok
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
        val map = row.toMutableMap(); map["ts"] = aimiCsvTimestamp()
        sb.append(keys.joinToString(",") { (map[it] ?: "").toString() }).append("\n")
        return sb.toString()
    }
}
