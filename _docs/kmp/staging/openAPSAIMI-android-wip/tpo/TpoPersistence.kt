package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import java.io.File

internal class TpoPersistence(
    private val storageHelper: AimiStorageHelper,
) {
    private val directoryName = "tpo"
    private val sessionFileName = "tpo_session.json"
    private val ledgerFileName = "tpo_episode_ledger.json"
    private val metaFileName = "tpo_meta.json"

    fun loadSession(): TpoSessionDocument? {
        val file = sessionFile()
        if (!file.exists()) return null
        return runCatching {
            TpoSessionDocument.fromJsonObject(JSONObject(file.readText()))
        }.getOrNull()
    }

    fun saveSession(document: TpoSessionDocument?) {
        val file = sessionFile()
        if (document == null) {
            if (file.exists()) file.delete()
            return
        }
        storageHelper.saveFileSafe(file, document.toJsonObject().toString(2))
    }

    fun loadLedger(): TpoEpisodeLedger {
        val file = ledgerFile()
        if (!file.exists()) return TpoEpisodeLedger()
        return runCatching {
            TpoEpisodeLedger.fromJsonObject(JSONObject(file.readText()))
        }.getOrDefault(TpoEpisodeLedger())
    }

    fun saveLedger(ledger: TpoEpisodeLedger) {
        storageHelper.saveFileSafe(ledgerFile(), ledger.toJsonObject().toString(2))
    }

    fun loadLastRevertAtMsByPack(): Map<TpoPackId, Long> {
        val file = metaFile()
        if (!file.exists()) return emptyMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        val revertObj = json.optJSONObject("last_revert_at_ms_by_pack") ?: return emptyMap()
        return buildMap {
            TpoPackId.entries.forEach { pack ->
                if (revertObj.has(pack.name)) {
                    put(pack, revertObj.getLong(pack.name))
                }
            }
        }
    }

    fun saveLastRevertAtMsByPack(map: Map<TpoPackId, Long>) {
        val json = JSONObject().apply {
            put(
                "last_revert_at_ms_by_pack",
                JSONObject().apply {
                    map.forEach { (pack, value) -> put(pack.name, value) }
                },
            )
        }
        storageHelper.saveFileSafe(metaFile(), json.toString(2))
    }

    private fun sessionFile(): File = storageHelper.getAimiFile(directoryName, sessionFileName)

    private fun ledgerFile(): File = storageHelper.getAimiFile(directoryName, ledgerFileName)

    private fun metaFile(): File = storageHelper.getAimiFile(directoryName, metaFileName)
}
